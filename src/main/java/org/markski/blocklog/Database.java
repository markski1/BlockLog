package org.markski.blocklog;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;

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
                    action       TEXT    NOT NULL,
                    created_at   INTEGER NOT NULL,
                    cause        TEXT
                );
                """;

        try (Statement stmt = connection.createStatement()) {
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
            String action,
            long createdAt,
            String cause
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
            ps.setString(8, action);
            ps.setLong(9, createdAt);
            if (cause != null) {
                ps.setString(10, cause);
            } else {
                ps.setNull(10, java.sql.Types.VARCHAR);
            }
            ps.executeUpdate();
        }
    }
}