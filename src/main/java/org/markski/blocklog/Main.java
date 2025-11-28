package org.markski.blocklog;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends JavaPlugin {

    private Database database;
    private final Set<UUID> inspectingPlayers =
            ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        getLogger().info("BlockLog loaded.");

        database = new Database(this);
        try {
            database.open();
            getLogger().info("Database initialized.");
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new BlockActionListener(this), this);

        if (getCommand("bkl") != null) {
            Objects.requireNonNull(getCommand("bkl")).setExecutor(new BklCommand(this));
        } else {
            getLogger().severe("Command 'bkl' not defined in plugin.yml");
        }

        getLogger().info("BlockLog loaded.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        getLogger().info("BlockLog unloaded.");
    }

    public Database getDatabase() {
        return database;
    }

    public boolean isInspecting(UUID playerId) {
        return inspectingPlayers.contains(playerId);
    }

    public boolean toggleInspect(UUID playerId) {
        if (inspectingPlayers.contains(playerId)) {
            inspectingPlayers.remove(playerId);
            return false;
        } else {
            inspectingPlayers.add(playerId);
            return true;
        }
    }
}