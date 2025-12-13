package org.markski.blocklog;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlockActionListener implements Listener {

    private final Main plugin;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Map<UUID, OpenContainerSession> openContainers = new HashMap<>();

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

        logAction(player, block, BlockActionType.BROKEN);
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

        logAction(player, placed, BlockActionType.PLACED);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            if (plugin.isInspecting(player.getUniqueId())) {
                inspectBlock(player, clicked);
                return;
            }

            if (action == Action.RIGHT_CLICK_BLOCK && isInteractiveBlock(clicked)) {
                String eventId = logAction(player, clicked, BlockActionType.INTERACTION);

                // if it's a container, associate following inventory changes with this open-event UUID.
                BlockState state = clicked.getState();
                if (state instanceof InventoryHolder holder && eventId != null) {
                    Map<Material, Integer> snapshot = countItems(holder.getInventory().getContents());

                    openContainers.put(
                            player.getUniqueId(),
                            new OpenContainerSession(
                                    eventId,
                                    clicked.getWorld().getName(),
                                    clicked.getX(),
                                    clicked.getY(),
                                    clicked.getZ(),
                                    snapshot
                            )
                    );
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        OpenContainerSession session = openContainers.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        // diff snapshot against final container contents.
        Map<Material, Integer> after = countItems(event.getView().getTopInventory().getContents());

        // figure out deltas.
        Map<Material, Integer> deltas = new HashMap<>();
        for (var e : session.snapshot().entrySet()) {
            deltas.put(e.getKey(), -e.getValue());
        }
        for (var e : after.entrySet()) {
            deltas.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();

        for (var entry : deltas.entrySet()) {
            int delta = entry.getValue();
            if (delta == 0) {
                continue;
            }

            db.enqueueContainerTransaction(
                    session.eventId(),
                    playerUuid,
                    playerName,
                    session.worldName(),
                    session.x(), session.y(), session.z(),
                    entry.getKey().name(),
                    delta,
                    now
            );
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            logExplosion(block);
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            logExplosion(block);
        }
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
        var server = plugin.getServer();

        // Query db async so we don't block the main thread
        server.getScheduler().runTaskAsynchronously(plugin, () -> {
            var entries = java.util.Collections.<org.markski.blocklog.Database.BlockLogEntry>emptyList();
            try {
                entries = db.getRecentActionsAtBlock(worldName, x, y, z, 10);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to query block history. - " + e.getMessage());

                server.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cFailed to query block history. See console.")
                );
                return;
            }

            // Must send messages at the main thread.
            var finalEntries = entries;
            server.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§e[History] §7" + "§f(" + x + ", " + y + ", " + y + ", " + z + "):");

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

    private String logAction(Player player, Block block, BlockActionType action) {
        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            return null;
        }

        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String blockType = block.getType().name();
        long now = System.currentTimeMillis();

        return db.enqueueBlockAction(
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                blockType,
                action,
                now,
                BlockActionCause.PLAYER
        );
    }

    private void logExplosion(Block block) {
        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            return;
        }

        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String blockType = block.getType().name();
        long now = System.currentTimeMillis();

        db.enqueueBlockAction(
                "00000000-0000-0000-0000-000000000000",
                "[EXPLOSION]",
                worldName,
                x, y, z,
                blockType,
                BlockActionType.BROKEN,
                now,
                BlockActionCause.EXPLOSION
        );
    }

    private boolean isInteractiveBlock(Block block) {
        Material type = block.getType();

        // doors and gates of any type
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) {
            return true;
        }

        // stuff with inventories
        BlockState state = block.getState();
        if (state instanceof InventoryHolder) {
            return true;
        }

        // buttons
        return switch (type) {
            case LEVER,
                 STONE_BUTTON,
                 OAK_BUTTON,
                 SPRUCE_BUTTON,
                 BIRCH_BUTTON,
                 JUNGLE_BUTTON,
                 ACACIA_BUTTON,
                 DARK_OAK_BUTTON,
                 CRIMSON_BUTTON,
                 WARPED_BUTTON,
                 POLISHED_BLACKSTONE_BUTTON ->
                    true;
            default -> false;
        };
    }

    private static Map<Material, Integer> countItems(ItemStack[] contents) {
        Map<Material, Integer> counts = new HashMap<>();
        if (contents == null) {
            return counts;
        }

        for (ItemStack stack : contents) {
            if (stack == null) {
                continue;
            }
            Material type = stack.getType();
            if (type == Material.AIR) {
                continue;
            }
            counts.merge(type, stack.getAmount(), Integer::sum);
        }

        return counts;
    }

    private record OpenContainerSession(
            String eventId,
            String worldName,
            int x,
            int y,
            int z,
            Map<Material, Integer> snapshot
    ) {}
}