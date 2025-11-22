package org.markski.blocklog;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("BlockLog loaded.");
    }

    @Override
    public void onDisable() {
        getLogger().info("BlockLog unloaded.");
    }
}