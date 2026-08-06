package org.markski.blocklog;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Main extends JavaPlugin {

    private volatile Database database;
    private final Set<UUID> inspectingPlayers =
            ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        database = new Database(this);
        Objects.requireNonNull(getCommand("bkl"), "Command 'bkl' not defined in plugin.yml")
                .setExecutor(new BklCommand(this));

        database.openAsync().whenComplete((ignored, error) -> {
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "Failed to initialize database.", error);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
                if (!isEnabled()) {
                    return;
                }

                getServer().getPluginManager().registerEvents(new BlockActionListener(this), this);
                getLogger().info("BlockLog loaded.");
            });
        });
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
        if (inspectingPlayers.remove(playerId)) {
            return false;
        }
        inspectingPlayers.add(playerId);
        return true;
    }

    public void removeInspecting(UUID playerId) {
        inspectingPlayers.remove(playerId);
    }
}
