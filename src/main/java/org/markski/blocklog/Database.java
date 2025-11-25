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
import java.util.concurrent.ConcurrentLinkedQueue;

public class Database {
    private final Plugin plugin;
    private Connection connection;

    private final Queue<PendingBlockAction> pendingActions = new ConcurrentLinkedQueue<>();
    private BukkitTask flushTask;

    // 1200 ticks seems to be 60 seconds.
    private static final long FLUSH_INTERVAL_TICKS = 1200L;
    private static final int MAX_QUEUE_SIZE = 50_000;

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

    private void createTables() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS block_actions (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
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
                    CREATE INDEX IF NOT EXISTS idx_block_actions_world_xyz
                    ON block_actions (world, x, y, z);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_block_actions_player_time
                    ON block_actions (player_uuid, created_at);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_block_actions_created_at
                    ON block_actions (created_at);
                    """;
            stmt.execute(sql);
        }
    }


    public void enqueueBlockAction(
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
            return;
        }

        if (pendingActions.size() >= MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("BlockLog queue full, event dropped.");
            return;
        }

        pendingActions.add(new PendingBlockAction(
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                blockType,
                action,
                createdAt,
                cause
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
            return;
        }

        List<PendingBlockAction> batch = new ArrayList<>();
        PendingBlockAction action;
        while ((action = pendingActions.poll()) != null) {
            batch.add(action);
        }

        if (batch.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO block_actions (
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;

        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (PendingBlockAction a : batch) {
                ps.setString(1, a.playerUuid());
                ps.setString(2, a.playerName());
                ps.setString(3, a.worldName());
                ps.setInt(4, a.x());
                ps.setInt(5, a.y());
                ps.setInt(6, a.z());
                ps.setString(7, a.blockType());
                ps.setInt(8, a.action().getCode());
                ps.setLong(9, a.createdAt());
                if (a.cause() != null) {
                    ps.setInt(10, a.cause().getCode());
                } else {
                    ps.setNull(10, java.sql.Types.INTEGER);
                }
                ps.addBatch();
            }

            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            plugin.getLogger().severe("Failed to flush block actions batch: " + e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private record PendingBlockAction(
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

    public List<BlockLogEntry> getRecentActionsAtBlock(
            String worldName,
            int x,
            int y,
            int z,
            int limit
    ) throws SQLException {
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

    public record BlockLogEntry(String playerName, String blockType, BlockActionType action, long createdAt,
                                BlockActionCause cause) {
    }
}