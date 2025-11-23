package org.markski.blocklog;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class Main extends JavaPlugin {

    private Database database;

    @Override
    public void onEnable() {
        getLogger().info("BlockLog loaded.");

        database = new Database(this);
        try {
            database.open();
            getLogger().info("Database initialized.");
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            // Nothing to do without a database, disable.
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        getLogger().info("BlockLog unloaded.");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label,
                             String[] args) {
        if (command.getName().equalsIgnoreCase("bkl")) {
            sender.sendMessage("BlockLog is loaded.");
            return true;
        }
        return false;
    }
}