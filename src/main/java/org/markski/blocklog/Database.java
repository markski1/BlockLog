package org.markski.blocklog;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class Database {
    private final Plugin plugin;
    private Connection connection;

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
        }

    public void close() {
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

    public void logBlockAction(
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
    ) throws SQLException {
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

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setString(2, playerName);
            ps.setString(3, worldName);
            ps.setInt(4, x);
            ps.setInt(5, y);
            ps.setInt(6, z);
            ps.setString(7, blockType);
            ps.setInt(8, action.getCode());
            ps.setLong(9, createdAt);
            if (cause != null) {
                ps.setInt(10, cause.getCode());
            } else {
                ps.setNull(10, java.sql.Types.INTEGER);
            }
            ps.executeUpdate();
        }
    }

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

    public static final class BlockLogEntry {
        private final String playerName;
        private final String blockType;
        private final BlockActionType action;
        private final long createdAt;
        private final BlockActionCause cause;

        public BlockLogEntry(String playerName,
                             String blockType,
                             BlockActionType action,
                             long createdAt,
                             BlockActionCause cause) {
            this.playerName = playerName;
            this.blockType = blockType;
            this.action = action;
            this.createdAt = createdAt;
            this.cause = cause;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getBlockType() {
            return blockType;
        }

        public BlockActionType getAction() {
            return action;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public BlockActionCause getCause() {
            return cause;
        }
    }
}