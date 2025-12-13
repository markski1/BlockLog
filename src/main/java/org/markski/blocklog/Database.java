package org.markski.blocklog;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Database {
    private final Plugin plugin;
    private Connection connection;

    private final Queue<PendingBlockAction> pendingActions = new ConcurrentLinkedQueue<>();
    private final Queue<PendingContainerTransaction> pendingContainerTransactions = new ConcurrentLinkedQueue<>();
    private BukkitTask flushTask;

    // 500 ticks seems to be a little under 30 seconds.
    private static final long FLUSH_INTERVAL_TICKS = 500L;
    private static final int MAX_QUEUE_SIZE = 50000;

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException {
        if (!plugin.getDataFolder().exists()) {
            boolean created = plugin.getDataFolder().mkdirs();
            if (!created) {
                throw new SQLException("Could not create plugin data folder: " +
                        plugin.getDataFolder().getAbsolutePath());
            }
        }

        File dbFile = new File(plugin.getDataFolder(), "blocklog.sqlite");
        boolean existedBefore = dbFile.exists();

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        if (!existedBefore) {
            plugin.getLogger().info("Created db: " + dbFile.getName());
        } else {
            plugin.getLogger().info("Using db: " + dbFile.getName());
        }

        createTables();
        startAsyncFlushTask();
    }

    public void close() {
        // Try to flush everything before shutting down.
        stopAsyncFlushTaskAndFlushNow();

        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("SQLite connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Error closing SQLite connection: " + e.getMessage());
            }
            connection = null;
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void flushPendingActionsNow() {
        flushPendingActionsSafe();
    }

    private void createTables() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS events (
                        id           TEXT PRIMARY KEY NOT NULL,
                        player_uuid  TEXT    NOT NULL,
                        player_name  TEXT    NOT NULL,
                        world        TEXT    NOT NULL,
                        x            INTEGER NOT NULL,
                        y            INTEGER NOT NULL,
                        z            INTEGER NOT NULL,
                        block_type   TEXT    NOT NULL,
                        action       INTEGER NOT NULL,  -- BlockActionType code
                        created_at   INTEGER NOT NULL,
                        cause        INTEGER            -- BlockActionCause code, nullable
                    );
                    """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_events_world_xyz
                    ON events (world, x, y, z);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_events_player_time
                    ON events (player_uuid, created_at);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_events_created_at
                    ON events (created_at);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE TABLE IF NOT EXISTS container_transactions (
                        id          TEXT PRIMARY KEY NOT NULL,
                        event_id    TEXT    NOT NULL,
                        player_uuid TEXT    NOT NULL,
                        player_name TEXT    NOT NULL,
                        world       TEXT    NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        item_type   TEXT    NOT NULL,
                        delta       INTEGER NOT NULL,
                        created_at  INTEGER NOT NULL,
                        FOREIGN KEY (event_id) REFERENCES events(id)
                    );
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_container_transactions_event
                    ON container_transactions (event_id);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_container_transactions_world_xyz_time
                    ON container_transactions (world, x, y, z, created_at);
                    """;
            stmt.execute(sql);
        }
    }

    public String enqueueBlockAction(
            String playerUuid,
            String playerName,
            String worldName,
            int x,
            int y,
            int z,
            String blockType,
            BlockActionType action,
            long createdAt,
            BlockActionCause cause
    ) {
        if (connection == null) {
            return null;
        }

        // JetBrains says .size() is O(n). Carrying a counter could be easy enough. If it matters.
        if (pendingActions.size() >= MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("BlockLog queue full, event dropped.");
            return null;
        }

        UUID uuid = UUID.randomUUID();

        pendingActions.add(new PendingBlockAction(
                uuid.toString(),
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                blockType,
                action,
                createdAt,
                cause
        ));

        return uuid.toString();
    }

    public void enqueueContainerTransaction(
            String eventId,
            String playerUuid,
            String playerName,
            String worldName,
            int x,
            int y,
            int z,
            String itemType,
            int delta,
            long createdAt
    ) {
        if (connection == null) {
            return;
        }
        if (eventId == null) {
            return;
        }
        if (delta == 0) {
            return;
        }

        if (pendingContainerTransactions.size() >= MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("BlockLog queue full, container transaction dropped.");
            return;
        }

        UUID id = UUID.randomUUID();
        pendingContainerTransactions.add(new PendingContainerTransaction(
                id.toString(),
                eventId,
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                itemType,
                delta,
                createdAt
        ));
    }

    private void startAsyncFlushTask() {
        if (flushTask != null) {
            return;
        }

        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushPendingActionsSafe,
                FLUSH_INTERVAL_TICKS,
                FLUSH_INTERVAL_TICKS
        );
    }

    private void stopAsyncFlushTaskAndFlushNow() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushPendingActionsSafe();
    }

    private void flushPendingActionsSafe() {
        try {
            flushPendingActions();
        } catch (Exception e) {
            plugin.getLogger().severe("Error flushing block actions: " + e.getMessage());
        }
    }

    private void flushPendingActions() throws SQLException {
        if (connection == null) {
            pendingActions.clear();
            pendingContainerTransactions.clear();
            return;
        }

        List<PendingBlockAction> eventsBatch = new ArrayList<>();
        PendingBlockAction action;
        while ((action = pendingActions.poll()) != null) {
            eventsBatch.add(action);
        }

        List<PendingContainerTransaction> txBatch = new ArrayList<>();
        PendingContainerTransaction tx;
        while ((tx = pendingContainerTransactions.poll()) != null) {
            txBatch.add(tx);
        }

        if (eventsBatch.isEmpty() && txBatch.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO events (
                    id,
                    player_uuid,
                    player_name,
                    world,
                    x,
                    y,
                    z,
                    block_type,
                    action,
                    created_at,
                    cause
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;

        String txSql = """
                INSERT INTO container_transactions (
                    id,
                    event_id,
                    player_uuid,
                    player_name,
                    world,
                    x,
                    y,
                    z,
                    item_type,
                    delta,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;

        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (PreparedStatement eventsPs = connection.prepareStatement(sql);
             PreparedStatement txPs = connection.prepareStatement(txSql)) {

            for (PendingBlockAction a : eventsBatch) {
                eventsPs.setString(1, a.id());
                eventsPs.setString(2, a.playerUuid());
                eventsPs.setString(3, a.playerName());
                eventsPs.setString(4, a.worldName());
                eventsPs.setInt(5, a.x());
                eventsPs.setInt(6, a.y());
                eventsPs.setInt(7, a.z());
                eventsPs.setString(8, a.blockType());
                eventsPs.setInt(9, a.action().getCode());
                eventsPs.setLong(10, a.createdAt());
                if (a.cause() != null) {
                    eventsPs.setInt(11, a.cause().getCode());
                } else {
                    eventsPs.setNull(11, java.sql.Types.INTEGER);
                }
                eventsPs.addBatch();
            }

            for (PendingContainerTransaction t : txBatch) {
                txPs.setString(1, t.id());
                txPs.setString(2, t.eventId());
                txPs.setString(3, t.playerUuid());
                txPs.setString(4, t.playerName());
                txPs.setString(5, t.worldName());
                txPs.setInt(6, t.x());
                txPs.setInt(7, t.y());
                txPs.setInt(8, t.z());
                txPs.setString(9, t.itemType());
                txPs.setInt(10, t.delta());
                txPs.setLong(11, t.createdAt());
                txPs.addBatch();
            }

            if (!eventsBatch.isEmpty()) {
                eventsPs.executeBatch();
            }
            if (!txBatch.isEmpty()) {
                txPs.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();

            // Restore batches so we don't silently lose data.
            pendingActions.addAll(eventsBatch);
            pendingContainerTransactions.addAll(txBatch);

            plugin.getLogger().severe("Failed to flush batch: " + e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private record PendingBlockAction(
            String id,
            String playerUuid,
            String playerName,
            String worldName,
            int x,
            int y,
            int z,
            String blockType,
            BlockActionType action,
            long createdAt,
            BlockActionCause cause
    ) {}

    private record PendingContainerTransaction(
            String id,
            String eventId,
            String playerUuid,
            String playerName,
            String worldName,
            int x,
            int y,
            int z,
            String itemType,
            int delta,
            long createdAt
    ) {}

    public List<BlockLogEntry> getRecentActionsAtBlock(String worldName, int x, int y, int z, int limit) throws SQLException {
        String sql = """
                SELECT player_name,
                       block_type,
                       action,
                       created_at,
                       cause
                FROM block_actions
                WHERE world = ?
                  AND x = ?
                  AND y = ?
                  AND z = ?
                ORDER BY created_at DESC
                LIMIT ?;
                """;

        List<BlockLogEntry> result = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worldName);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.setInt(5, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String playerName = rs.getString("player_name");
                    String blockType = rs.getString("block_type");
                    int actionCode = rs.getInt("action");
                    long createdAt = rs.getLong("created_at");
                    int causeCode = rs.getInt("cause");
                    boolean causeWasNull = rs.wasNull();

                    BlockActionType action = BlockActionType.fromCode(actionCode);
                    BlockActionCause cause = null;
                    if (!causeWasNull) {
                        cause = BlockActionCause.fromCode(causeCode);
                    }

                    result.add(new BlockLogEntry(
                            playerName,
                            blockType,
                            action,
                            createdAt,
                            cause
                    ));
                }
            }
        }

        return result;
    }

    public List<RollbackEntry> getActionsForRollback(
            String playerName,
            String worldName,
            long fromTime,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) throws SQLException {
        String sql = """
                SELECT x,
                       y,
                       z,
                       block_type,
                       action,
                       created_at
                FROM block_actions
                WHERE world = ?
                  AND player_name = ?
                  AND created_at >= ?
                  AND x BETWEEN ? AND ?
                  AND y BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                  AND action IN ("PLACED", "BROKEN")
                ORDER BY created_at ASC;
                """;

        List<RollbackEntry> result = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worldName);
            ps.setString(2, playerName);
            ps.setLong(3, fromTime);
            ps.setInt(4, minX);
            ps.setInt(5, maxX);
            ps.setInt(6, minY);
            ps.setInt(7, maxY);
            ps.setInt(8, minZ);
            ps.setInt(9, maxZ);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    String blockType = rs.getString("block_type");
                    int actionCode = rs.getInt("action");
                    long createdAt = rs.getLong("created_at");

                    BlockActionType action = BlockActionType.fromCode(actionCode);

                    result.add(new RollbackEntry(
                            x,
                            y,
                            z,
                            blockType,
                            action,
                            createdAt
                    ));
                }
            }
        }

        return result;
    }

    public record RollbackEntry(int x, int y, int z, String blockType, BlockActionType action, long createdAt) {}
    public record BlockLogEntry(String playerName, String blockType, BlockActionType action, long createdAt, BlockActionCause cause) {}
}