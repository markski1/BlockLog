package org.markski.blocklog;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class BlockActionListener implements Listener {

    private static final int INSPECT_MAX_RESULTS = 10;

    private final Main plugin;
    private final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public BlockActionListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (plugin.isInspecting(player.getUniqueId())) {
            // If inspecting, don't let actions go through, just inspect the coords.
            event.setCancelled(true);
            inspectBlock(player, block);
            return;
        }

        logAction(player, block, BlockActionType.BROKEN, BlockActionCause.PLAYER);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block placed = event.getBlockPlaced();

        if (plugin.isInspecting(player.getUniqueId())) {
            // If inspecting, don't let actions go through, just inspect the coords.
            event.setCancelled(true);
            inspectBlock(player, placed);
            return;
        }

        logAction(player, placed, BlockActionType.PLACED, BlockActionCause.PLAYER);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!plugin.isInspecting(player.getUniqueId())) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        // Inspect on click (no cancel)
        inspectBlock(player, clicked);
    }

    private void inspectBlock(Player player, Block block) {
        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            player.sendMessage("§cDatabase not available.");
            return;
        }

        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String playerName = player.getName(); // for logging if needed
        var server = plugin.getServer();

        // Query db async so we don't block the main thread
        server.getScheduler().runTaskAsynchronously(plugin, () -> {
            var entries = java.util.Collections.<org.markski.blocklog.Database.BlockLogEntry>emptyList();
            try {
                entries = db.getRecentActionsAtBlock(worldName, x, y, z, INSPECT_MAX_RESULTS);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to query block history for " +
                        playerName + " at " + worldName + " (" + x + "," + y + "," + z + "): " +
                        e.getMessage());

                // Must send messages at the main thread.
                server.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cFailed to query block history. See console.")
                );
                return;
            }

            // Must send messages at the main thread.
            var finalEntries = entries;
            server.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§e[Inspect] Block history at §7" + worldName +
                        " §f(" + x + ", " + y + ", " + z + "):");

                if (finalEntries.isEmpty()) {
                    player.sendMessage("§7No logged actions for this block.");
                    return;
                }

                for (var entry : finalEntries) {
                    String timeStr = timeFormatter.format(
                            Instant.ofEpochMilli(entry.createdAt())
                    );
                    String actionStr = switch (entry.action()) {
                        case PLACED -> "§aPLACED";
                        case BROKEN -> "§cBROKEN";
                        case INTERACTION -> "§bINTERACTED";
                    };

                    String causeStr = entry.cause() != null
                            ? entry.cause().name()
                            : "UNKNOWN";

                    player.sendMessage("§7[" + timeStr + "] " +
                            "§b" + entry.playerName() + " §7" +
                            actionStr + " §f" + entry.blockType() +
                            " §8(" + causeStr + ")");
                }
            });
        });
    }

    private void logAction(Player player,
                           Block block,
                           BlockActionType action,
                           BlockActionCause cause) {
        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
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

        db.enqueueBlockAction(
            playerUuid,
            playerName,
            worldName,
            x, y, z,
            blockType,
            action,
            now,
            cause
        );
    }
}