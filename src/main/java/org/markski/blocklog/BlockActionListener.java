package org.markski.blocklog;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public class BlockActionListener implements Listener {

    private final Main plugin;

    public BlockActionListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        logAction(player, block, "BROKEN", "PLAYER");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();

        logAction(player, block, "PLACED", "PLAYER");
    }

    private void logAction(Player player, Block block, String action, String cause) {
        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            // DB not ready; avoid NPEs
            plugin.getLogger().warning("Database not available; skipping block action log.");
            return;
        }

        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String blockType = block.getType().name();
        long now = System.currentTimeMillis();

        try {
            db.logBlockAction(
                    playerUuid,
                    playerName,
                    worldName,
                    x, y, z,
                    blockType,
                    action,
                    now,
                    cause
            );
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to log block action: " + e.getMessage());
        }
    }
}