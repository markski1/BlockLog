package org.markski.blocklog;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Database {
    private final Plugin plugin;

    private Connection writeConnection;
    private String jdbcUrl;
    private String readJdbcUrl;
    private volatile boolean open;
    private volatile boolean closing;

    private final Queue<PendingBlockAction> pendingActions = new ConcurrentLinkedQueue<>();
    private final Queue<PendingContainerTransaction> pendingContainerTransactions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicLong lastQueueWarningNanos = new AtomicLong();

    private static final long FLUSH_INTERVAL_SECONDS = 25L;
    private static final int MAX_QUEUE_SIZE = 50000;
    private static final int MAX_FLUSH_BATCH_SIZE = 5000;
    private static final int WAL_CHECKPOINT_INTERVAL = 10;
    private static final int MAX_READ_POOL_SIZE = 3;
    private static final long QUEUE_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private int flushCount = 0;

    private final Deque<Connection> readPool = new ArrayDeque<>();
    private final Semaphore readPermits = new Semaphore(MAX_READ_POOL_SIZE);

    private static final String EVENTS_INSERT_SQL = """
            INSERT INTO events (
                id,
                player_uuid,
                player_name,
                world,
                x,
                y,
                z,
                block_type,
                block_data,
                rollback_skip_reason,
                action,
                created_at,
                cause
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String TX_INSERT_SQL = """
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

    private PreparedStatement eventsInsertPs;
    private PreparedStatement txInsertPs;

    private final ScheduledExecutorService dbExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BlockLog-DB");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> flushFuture;

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> openAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                open();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, dbExecutor);
    }

    private void open() throws SQLException {
        if (closing) {
            throw new SQLException("Database is closing.");
        }

        if (!plugin.getDataFolder().exists()) {
            boolean created = plugin.getDataFolder().mkdirs();
            if (!created) {
                throw new SQLException("Could not create plugin data folder: " +
                        plugin.getDataFolder().getAbsolutePath());
            }
        }

        File dbFile = new File(plugin.getDataFolder(), "blocklog.sqlite");
        boolean existedBefore = dbFile.exists();

        jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        readJdbcUrl = "jdbc:sqlite:" + dbFile.toPath().toUri() + "?mode=ro";

        // Open write connection. The writer thread owns it.
        writeConnection = DriverManager.getConnection(jdbcUrl);
        applyPragmas(writeConnection);

        if (!existedBefore) {
            plugin.getLogger().info("Created db: " + dbFile.getName());
        } else {
            plugin.getLogger().info("Using db: " + dbFile.getName());
        }

        createTables();
        validateSchema();
        failInterruptedRollbackAudits();
        open = true;
        startDbFlushLoop();
    }

    public void close() {
        closing = true;
        try {
            dbExecutor.submit(this::closeOnDatabaseThread).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to close database cleanly: " + e.getMessage());
        }

        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dbExecutor.shutdownNow();
        }

    }

    private void closeOnDatabaseThread() {
        stopDbFlushLoop();

        try {
            while (pendingCount.get() > 0) {
                flushPendingActions();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed final flush during shutdown: " + e.getMessage());
        }

        open = false;

        if (writeConnection != null) {
            closeQuietly(eventsInsertPs);
            closeQuietly(txInsertPs);
            eventsInsertPs = null;
            txInsertPs = null;

            try {
                writeConnection.close();
                plugin.getLogger().info("SQLite connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Error closing SQLite connection: " + e.getMessage());
            }
            writeConnection = null;
        }

        Connection pooled;
        while ((pooled = readPool.poll()) != null) {
            closeQuietly(pooled);
        }
    }

    private static void closeQuietly(AutoCloseable ac) {
        if (ac != null) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    public boolean isOpen() {
        return open && !closing;
    }

    public void flushPendingActionsNow() {
        if (!isOpen()) {
            return;
        }

        try {
            dbExecutor.submit(this::flushPendingActionsSafe).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Error waiting for flush: " + e.getMessage());
        }
    }

    public void requestFlush() {
        if (!isOpen()) {
            return;
        }
        try {
            dbExecutor.submit(this::flushPendingActionsSafe);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void applyPragmas(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA foreign_keys=ON;");
            // Good lord.
            stmt.execute("PRAGMA busy_timeout=5000;");
        }
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
                        block_data   TEXT    NOT NULL,
                        rollback_skip_reason TEXT,
                        action       INTEGER NOT NULL,  -- BlockActionType code
                        created_at   INTEGER NOT NULL,
                        cause        INTEGER            -- BlockActionCause code, nullable
                    );
                    """;

        try (Statement stmt = writeConnection.createStatement()) {
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
                    CREATE TABLE IF NOT EXISTS rollback_audits (
                        id                  TEXT PRIMARY KEY NOT NULL,
                        executor_uuid       TEXT    NOT NULL,
                        executor_name       TEXT    NOT NULL,
                        target_uuid         TEXT    NOT NULL,
                        target_name         TEXT    NOT NULL,
                        world               TEXT    NOT NULL,
                        center_x            INTEGER NOT NULL,
                        center_y            INTEGER NOT NULL,
                        center_z            INTEGER NOT NULL,
                        radius              INTEGER NOT NULL,
                        from_time           INTEGER NOT NULL,
                        preview_events      INTEGER NOT NULL,
                        preview_unsupported INTEGER NOT NULL,
                        started_at          INTEGER NOT NULL,
                        completed_at        INTEGER,
                        status              TEXT    NOT NULL,
                        affected            INTEGER,
                        skipped             INTEGER,
                        details             TEXT
                    );
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_rollback_audits_started_at
                    ON rollback_audits (started_at DESC);
                    """;
            stmt.execute(sql);

            sql = """
                    CREATE INDEX IF NOT EXISTS idx_events_player_name_time
                    ON events (player_name COLLATE NOCASE, created_at DESC);
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
            String blockData,
            String rollbackSkipReason,
            BlockActionType action,
            long createdAt,
            BlockActionCause cause
    ) {
        if (!isOpen()) {
            return null;
        }

        if (!reserveQueueSlot()) {
            warnQueueFull("event");
            return null;
        }

        UUID uuid = UUID.randomUUID();

        try {
            pendingActions.add(new PendingBlockAction(
                    uuid.toString(),
                    playerUuid,
                    playerName,
                    worldName,
                    x, y, z,
                    blockType,
                    blockData,
                    rollbackSkipReason,
                    action,
                    createdAt,
                    cause
            ));
        } catch (RuntimeException e) {
            pendingCount.decrementAndGet();
            throw e;
        }

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
        if (!isOpen()) {
            return;
        }
        if (eventId == null) {
            return;
        }
        if (delta == 0) {
            return;
        }

        if (!reserveQueueSlot()) {
            warnQueueFull("container transaction");
            return;
        }

        UUID id = UUID.randomUUID();
        try {
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
        } catch (RuntimeException e) {
            pendingCount.decrementAndGet();
            throw e;
        }
    }

    public CompletableFuture<Void> createRollbackAudit(RollbackAuditStart audit) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new SQLException("Database not available."));
        }

        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO rollback_audits (
                        id, executor_uuid, executor_name, target_uuid, target_name,
                        world, center_x, center_y, center_z, radius, from_time,
                        preview_events, preview_unsupported, started_at, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """;
            try (PreparedStatement ps = writeConnection.prepareStatement(sql)) {
                ps.setString(1, audit.id());
                ps.setString(2, audit.executorUuid());
                ps.setString(3, audit.executorName());
                ps.setString(4, audit.targetUuid());
                ps.setString(5, audit.targetName());
                ps.setString(6, audit.worldName());
                ps.setInt(7, audit.centerX());
                ps.setInt(8, audit.centerY());
                ps.setInt(9, audit.centerZ());
                ps.setInt(10, audit.radius());
                ps.setLong(11, audit.fromTime());
                ps.setInt(12, audit.previewEvents());
                ps.setInt(13, audit.previewUnsupported());
                ps.setLong(14, audit.startedAt());
                ps.setString(15, RollbackAuditStatus.RUNNING.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, dbExecutor);
    }

    public void finishRollbackAudit(
            String auditId,
            RollbackAuditStatus status,
            int affected,
            int skipped,
            String details
    ) {
        if (status == RollbackAuditStatus.RUNNING) {
            throw new IllegalArgumentException("A completed audit cannot use RUNNING status.");
        }
        if (!isOpen()) {
            return;
        }

        String safeDetails = details == null
                ? null
                : details.substring(0, Math.min(details.length(), 2000));
        try {
            dbExecutor.execute(() -> {
                String sql = """
                        UPDATE rollback_audits
                        SET completed_at = ?, status = ?, affected = ?, skipped = ?, details = ?
                        WHERE id = ? AND status = ?;
                        """;
                try (PreparedStatement ps = writeConnection.prepareStatement(sql)) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, status.name());
                    ps.setInt(3, affected);
                    ps.setInt(4, skipped);
                    ps.setString(5, safeDetails);
                    ps.setString(6, auditId);
                    ps.setString(7, RollbackAuditStatus.RUNNING.name());
                    if (ps.executeUpdate() != 1) {
                        plugin.getLogger().warning("Rollback audit was already finalized: " + auditId);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Failed to finalize rollback audit: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            plugin.getLogger().severe("Database closed before rollback audit could be finalized: " + auditId);
        }
    }

    private void validateSchema() throws SQLException {
        boolean hasBlockData = false;
        boolean hasRollbackSkipReason = false;
        try (Statement stmt = writeConnection.createStatement();
             ResultSet columns = stmt.executeQuery("PRAGMA table_info(events);")) {
            while (columns.next()) {
                if ("block_data".equals(columns.getString("name"))) {
                    hasBlockData = true;
                } else if ("rollback_skip_reason".equals(columns.getString("name"))) {
                    hasRollbackSkipReason = true;
                }
            }
        }

        if (!hasBlockData || !hasRollbackSkipReason) {
            throw new SQLException(
                    "Unsupported pre-release database schema. Remove blocklog.sqlite and restart."
            );
        }
    }

    private void failInterruptedRollbackAudits() throws SQLException {
        String sql = """
                UPDATE rollback_audits
                SET completed_at = ?, status = ?, details = ?
                WHERE status = ?;
                """;
        try (PreparedStatement ps = writeConnection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, RollbackAuditStatus.FAILED.name());
            ps.setString(3, "Server stopped before rollback completion");
            ps.setString(4, RollbackAuditStatus.RUNNING.name());
            ps.executeUpdate();
        }
    }

    private void applyReadPragmas(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            stmt.execute("PRAGMA query_only=ON;");
            stmt.execute("PRAGMA busy_timeout=5000;");
        }
    }

    private boolean reserveQueueSlot() {
        while (true) {
            int current = pendingCount.get();
            if (current >= MAX_QUEUE_SIZE) {
                return false;
            }
            if (pendingCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void warnQueueFull(String recordType) {
        long now = System.nanoTime();
        long previous = lastQueueWarningNanos.get();
        if ((previous == 0 || now - previous >= QUEUE_WARNING_INTERVAL_NANOS)
                && lastQueueWarningNanos.compareAndSet(previous, now)) {
            plugin.getLogger().warning("BlockLog queue full; dropping " + recordType + " records.");
        }
    }

    private void startDbFlushLoop() {
        if (flushFuture != null) {
            return;
        }

        flushFuture = dbExecutor.scheduleAtFixedRate(
                this::flushPendingActionsSafe,
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void stopDbFlushLoop() {
        if (flushFuture != null) {
            flushFuture.cancel(false);
            flushFuture = null;
        }
    }

    private void flushPendingActionsSafe() {
        try {
            flushPendingActions();
        } catch (Exception e) {
            plugin.getLogger().severe("Error flushing block actions: " + e.getMessage());
        }
    }

    private void flushPendingActions() throws SQLException {
        if (writeConnection == null) {
            pendingActions.clear();
            pendingContainerTransactions.clear();
            pendingCount.set(0);
            return;
        }

        List<PendingBlockAction> eventsBatch = new ArrayList<>();
        PendingBlockAction action;
        while (eventsBatch.size() < MAX_FLUSH_BATCH_SIZE
                && (action = pendingActions.poll()) != null) {
            eventsBatch.add(action);
            pendingCount.decrementAndGet();
        }

        List<PendingContainerTransaction> txBatch = new ArrayList<>();
        PendingContainerTransaction tx;
        if (pendingActions.isEmpty()) {
            int remainingCapacity = MAX_FLUSH_BATCH_SIZE - eventsBatch.size();
            while (txBatch.size() < remainingCapacity
                    && (tx = pendingContainerTransactions.poll()) != null) {
                txBatch.add(tx);
                pendingCount.decrementAndGet();
            }
        }

        if (eventsBatch.isEmpty() && txBatch.isEmpty()) {
            return;
        }

        boolean oldAutoCommit = writeConnection.getAutoCommit();
        writeConnection.setAutoCommit(false);

        try {
            if (eventsInsertPs == null || eventsInsertPs.isClosed()) {
                eventsInsertPs = writeConnection.prepareStatement(EVENTS_INSERT_SQL);
            }
            if (txInsertPs == null || txInsertPs.isClosed()) {
                txInsertPs = writeConnection.prepareStatement(TX_INSERT_SQL);
            }

            for (PendingBlockAction a : eventsBatch) {
                eventsInsertPs.setString(1, a.id());
                eventsInsertPs.setString(2, a.playerUuid());
                eventsInsertPs.setString(3, a.playerName());
                eventsInsertPs.setString(4, a.worldName());
                eventsInsertPs.setInt(5, a.x());
                eventsInsertPs.setInt(6, a.y());
                eventsInsertPs.setInt(7, a.z());
                eventsInsertPs.setString(8, a.blockType());
                eventsInsertPs.setString(9, a.blockData());
                eventsInsertPs.setString(10, a.rollbackSkipReason());
                eventsInsertPs.setInt(11, a.action().getCode());
                eventsInsertPs.setLong(12, a.createdAt());
                if (a.cause() != null) {
                    eventsInsertPs.setInt(13, a.cause().getCode());
                } else {
                    eventsInsertPs.setNull(13, java.sql.Types.INTEGER);
                }
                eventsInsertPs.addBatch();
            }

            for (PendingContainerTransaction t : txBatch) {
                txInsertPs.setString(1, t.id());
                txInsertPs.setString(2, t.eventId());
                txInsertPs.setString(3, t.playerUuid());
                txInsertPs.setString(4, t.playerName());
                txInsertPs.setString(5, t.worldName());
                txInsertPs.setInt(6, t.x());
                txInsertPs.setInt(7, t.y());
                txInsertPs.setInt(8, t.z());
                txInsertPs.setString(9, t.itemType());
                txInsertPs.setInt(10, t.delta());
                txInsertPs.setLong(11, t.createdAt());
                txInsertPs.addBatch();
            }

            if (!eventsBatch.isEmpty()) {
                eventsInsertPs.executeBatch();
                eventsInsertPs.clearBatch();
                eventsInsertPs.clearParameters();
            }
            if (!txBatch.isEmpty()) {
                txInsertPs.executeBatch();
                txInsertPs.clearBatch();
                txInsertPs.clearParameters();
            }

            writeConnection.commit();

            flushCount++;
            if (flushCount % WAL_CHECKPOINT_INTERVAL == 0) {
                try (Statement checkpointStmt = writeConnection.createStatement()) {
                    checkpointStmt.execute("PRAGMA wal_checkpoint(PASSIVE);");
                }
            }

            if (!closing && pendingCount.get() > 0) {
                dbExecutor.execute(this::flushPendingActionsSafe);
            }
        } catch (SQLException e) {
            closeQuietly(eventsInsertPs);
            closeQuietly(txInsertPs);
            eventsInsertPs = null;
            txInsertPs = null;

            try {
                writeConnection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }

            // Restore batches so we don't silently lose data.
            pendingActions.addAll(eventsBatch);
            pendingContainerTransactions.addAll(txBatch);
            pendingCount.addAndGet(eventsBatch.size() + txBatch.size());

            plugin.getLogger().severe("Failed to flush batch: " + e.getMessage());
            throw e;
        } finally {
            writeConnection.setAutoCommit(oldAutoCommit);
        }
    }

    private Connection borrowReadConnection() throws SQLException {
        if (!readPermits.tryAcquire()) {
            throw new SQLException("Database read capacity is temporarily exhausted.");
        }

        try {
            synchronized (readPool) {
                Connection connection;
                while ((connection = readPool.poll()) != null) {
                    if (!connection.isClosed() && connection.isValid(1)) {
                        return connection;
                    }
                    closeQuietly(connection);
                }
            }
            return createReadConnection();
        } catch (SQLException | RuntimeException e) {
            readPermits.release();
            throw e;
        }
    }

    private void returnReadConnection(Connection c) {
        if (c == null) {
            return;
        }
        try {
            synchronized (readPool) {
                if (!closing && readPool.size() < MAX_READ_POOL_SIZE) {
                    readPool.push(c);
                    return;
                }
                closeQuietly(c);
            }
        } finally {
            readPermits.release();
        }
    }

    private Connection createReadConnection() throws SQLException {
        if (readJdbcUrl == null) {
            throw new SQLException("Database not initialized.");
        }
        Connection c = DriverManager.getConnection(readJdbcUrl);
        applyReadPragmas(c);
        return c;
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
            String blockData,
            String rollbackSkipReason,
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

    public BlockHistoryPage getActionsAtBlockPage(
            String worldName,
            int x,
            int y,
            int z,
            int requestedPage,
            int pageSize
    ) throws SQLException {
        if (!isOpen()) {
            throw new SQLException("Database not available.");
        }
        if (requestedPage < 1 || pageSize < 1 || pageSize > 20) {
            throw new IllegalArgumentException("Invalid history page request.");
        }

        String sql = """
                SELECT e.player_name,
                       e.block_type,
                       e.action,
                       e.created_at,
                       e.cause,
                       (
                           SELECT GROUP_CONCAT(tx.item_type || ':' || tx.delta, ',')
                           FROM (
                               SELECT t.item_type, t.delta
                               FROM container_transactions t
                               WHERE t.event_id = e.id
                               ORDER BY t.created_at DESC, t.rowid DESC
                               LIMIT 20
                           ) tx
                       ) AS transaction_summary,
                       (
                           SELECT COUNT(*)
                           FROM container_transactions t
                           WHERE t.event_id = e.id
                       ) AS transaction_count
                FROM events e
                WHERE e.world = ?
                  AND e.x = ?
                  AND e.y = ?
                  AND e.z = ?
                ORDER BY e.created_at DESC, e.rowid DESC
                LIMIT ? OFFSET ?;
                """;
        String countSql = """
                SELECT COUNT(*)
                FROM events
                WHERE world = ? AND x = ? AND y = ? AND z = ?;
                """;

        List<BlockLogEntry> result = new ArrayList<>();

        Connection c = null;
        try {
            c = borrowReadConnection();
            int totalEntries;
            try (PreparedStatement countPs = c.prepareStatement(countSql)) {
                countPs.setString(1, worldName);
                countPs.setInt(2, x);
                countPs.setInt(3, y);
                countPs.setInt(4, z);
                try (ResultSet rs = countPs.executeQuery()) {
                    totalEntries = rs.next() ? rs.getInt(1) : 0;
                }
            }
            int totalPages = Math.max(1, (totalEntries + pageSize - 1) / pageSize);
            int page = Math.min(requestedPage, totalPages);

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, worldName);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setInt(5, pageSize);
                ps.setInt(6, (page - 1) * pageSize);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int causeCode = rs.getInt("cause");
                        boolean causeWasNull = rs.wasNull();
                        result.add(new BlockLogEntry(
                                rs.getString("player_name"),
                                rs.getString("block_type"),
                                BlockActionType.fromCode(rs.getInt("action")),
                                rs.getLong("created_at"),
                                causeWasNull ? null : BlockActionCause.fromCode(causeCode),
                                rs.getString("transaction_summary"),
                                rs.getInt("transaction_count")
                        ));
                    }
                }
            }

            return new BlockHistoryPage(List.copyOf(result), page, totalPages, totalEntries);
        } finally {
            returnReadConnection(c);
        }
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
            int maxZ,
            int limit
    ) throws SQLException {
        if (!isOpen()) {
            throw new SQLException("Database not available.");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Rollback limit must be positive.");
        }

        String sql = """
                SELECT id,
                       x,
                       y,
                       z,
                       block_type,
                       block_data,
                       rollback_skip_reason,
                       player_uuid,
                       action,
                       created_at
                FROM events
                WHERE world = ?
                  AND player_uuid = (
                      SELECT player_uuid
                      FROM events
                      WHERE player_name = ? COLLATE NOCASE
                      ORDER BY created_at DESC, rowid DESC
                      LIMIT 1
                  )
                  AND created_at >= ?
                  AND x BETWEEN ? AND ?
                  AND y BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                  AND action IN (?, ?)
                ORDER BY created_at DESC, rowid DESC
                LIMIT ?;
                """;

        List<RollbackEntry> result = new ArrayList<>();

        Connection c = null;
        try {
            c = borrowReadConnection();
            PreparedStatement ps = c.prepareStatement(sql);

            ps.setString(1, worldName);
            ps.setString(2, playerName);
            ps.setLong(3, fromTime);
            ps.setInt(4, minX);
            ps.setInt(5, maxX);
            ps.setInt(6, minY);
            ps.setInt(7, maxY);
            ps.setInt(8, minZ);
            ps.setInt(9, maxZ);

            // only placed/broken
            ps.setInt(10, BlockActionType.PLACED.getCode());
            ps.setInt(11, BlockActionType.BROKEN.getCode());
            ps.setInt(12, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    String blockType = rs.getString("block_type");
                    String blockData = rs.getString("block_data");
                    String rollbackSkipReason = rs.getString("rollback_skip_reason");
                    String playerUuid = rs.getString("player_uuid");
                    int actionCode = rs.getInt("action");
                    long createdAt = rs.getLong("created_at");

                    BlockActionType action = BlockActionType.fromCode(actionCode);

                    result.add(new RollbackEntry(
                            id,
                            x,
                            y,
                            z,
                            blockType,
                            blockData,
                            rollbackSkipReason,
                            playerUuid,
                            action,
                            createdAt
                    ));
                }
            } finally {
                ps.close();
            }
        } finally {
            returnReadConnection(c);
        }

        return result;
    }

    public record RollbackEntry(
            String id,
            int x,
            int y,
            int z,
            String blockType,
            String blockData,
            String rollbackSkipReason,
            String playerUuid,
            BlockActionType action,
            long createdAt
    ) {}

    public record RollbackAuditStart(
            String id,
            String executorUuid,
            String executorName,
            String targetUuid,
            String targetName,
            String worldName,
            int centerX,
            int centerY,
            int centerZ,
            int radius,
            long fromTime,
            int previewEvents,
            int previewUnsupported,
            long startedAt
    ) {}

    public enum RollbackAuditStatus {
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public record BlockLogEntry(
            String playerName,
            String blockType,
            BlockActionType action,
            long createdAt,
            BlockActionCause cause,
            String transactionSummary,
            int transactionCount
    ) {}

    public record BlockHistoryPage(
            List<BlockLogEntry> entries,
            int page,
            int totalPages,
            int totalEntries
    ) {}
}
