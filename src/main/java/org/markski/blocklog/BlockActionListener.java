package org.markski.blocklog;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlockActionListener implements Listener {

    private final Main plugin;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Map<UUID, OpenContainerSession> openContainers = new HashMap<>();
    private final Map<UUID, PendingContainerSession> pendingContainers = new HashMap<>();
    private final Map<Inventory, UUID> trackedInventories = new HashMap<>();
    private final Set<Inventory> conflictedInventories = new HashSet<>();
    private final Map<UUID, InspectionTarget> pendingInspections = new HashMap<>();
    private final Set<UUID> inspectionsInFlight = new HashSet<>();

    public BlockActionListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreakInspect(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (plugin.isInspecting(player.getUniqueId())) {
            event.setCancelled(true);
            inspectBlock(player, event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        logAction(event.getPlayer(), event.getBlock(), BlockActionType.BROKEN);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlaceInspect(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (plugin.isInspecting(player.getUniqueId())) {
            event.setCancelled(true);
            inspectBlock(player, event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        logAction(event.getPlayer(), event.getBlockPlaced(), BlockActionType.PLACED);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractInspect(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!plugin.isInspecting(player.getUniqueId())
                || (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            event.setCancelled(true);
            inspectBlock(player, clicked);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            if (plugin.isInspecting(player.getUniqueId())) {
                return;
            }

            if (action == Action.RIGHT_CLICK_BLOCK) {
                BlockState state = clicked.getState();
                boolean isContainer = state instanceof InventoryHolder;
                if (!isContainer && !isInteractiveBlock(clicked)) {
                    return;
                }

                String eventId = logAction(player, clicked, BlockActionType.INTERACTION);

                if (isContainer && eventId != null) {
                    UUID playerId = player.getUniqueId();
                    PendingContainerSession pending = new PendingContainerSession(
                            eventId,
                            clicked.getWorld().getName(),
                            clicked.getX(),
                            clicked.getY(),
                            clicked.getZ()
                    );
                    pendingContainers.put(playerId, pending);
                    plugin.getServer().getScheduler().runTask(
                            plugin,
                            () -> pendingContainers.remove(playerId, pending)
                    );
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        discardContainerSession(playerId);
        pendingContainers.remove(playerId);
        pendingInspections.remove(playerId);
        inspectionsInFlight.remove(playerId);
        plugin.removeInspecting(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        PendingContainerSession pending = pendingContainers.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        if (conflictedInventories.contains(inventory)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID existingOwner = trackedInventories.get(inventory);
        if (existingOwner != null && !existingOwner.equals(playerId)) {
            discardContainerSession(existingOwner);
            conflictedInventories.add(inventory);
            return;
        }

        discardContainerSession(playerId);
        trackedInventories.put(inventory, playerId);
        openContainers.put(
                playerId,
                new OpenContainerSession(
                        pending.eventId(),
                        pending.worldName(),
                        pending.x(),
                        pending.y(),
                        pending.z(),
                        inventory,
                        countItems(inventory.getContents())
                )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        if (conflictedInventories.contains(inventory)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (inventory.getViewers().isEmpty()) {
                    conflictedInventories.remove(inventory);
                }
            });
            return;
        }

        OpenContainerSession session = openContainers.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!session.inventory().equals(inventory)) {
            return;
        }
        trackedInventories.remove(inventory);

        Map<Material, Integer> after = countItems(inventory.getContents());

        // figure out deltas.
        Map<Material, Integer> deltas = new HashMap<>();
        for (var e : session.snapshot().entrySet()) {
            deltas.put(e.getKey(), -e.getValue());
        }
        for (var e : after.entrySet()) {
            deltas.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        var db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
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

    private void discardContainerSession(UUID playerId) {
        OpenContainerSession session = openContainers.remove(playerId);
        if (session != null) {
            trackedInventories.remove(session.inventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        List<ExplosionBlockData> blocks = new ArrayList<>();
        for (Block block : event.blockList()) {
            blocks.add(new ExplosionBlockData(
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    block.getType().name(),
                    block.getBlockData().getAsString()
            ));
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var db = plugin.getDatabase();
            if (db == null || !db.isOpen()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (ExplosionBlockData data : blocks) {
                db.enqueueBlockAction(
                        "00000000-0000-0000-0000-000000000000",
                        "[EXPLOSION]",
                        data.worldName(),
                        data.x(), data.y(), data.z(),
                        data.blockType(),
                        data.blockData(),
                        BlockActionType.BROKEN,
                        now,
                        BlockActionCause.EXPLOSION
                );
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        List<ExplosionBlockData> blocks = new ArrayList<>();
        for (Block block : event.blockList()) {
            blocks.add(new ExplosionBlockData(
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    block.getType().name(),
                    block.getBlockData().getAsString()
            ));
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var db = plugin.getDatabase();
            if (db == null || !db.isOpen()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (ExplosionBlockData data : blocks) {
                db.enqueueBlockAction(
                        "00000000-0000-0000-0000-000000000000",
                        "[EXPLOSION]",
                        data.worldName(),
                        data.x(), data.y(), data.z(),
                        data.blockType(),
                        data.blockData(),
                        BlockActionType.BROKEN,
                        now,
                        BlockActionCause.EXPLOSION
                );
            }
        });
    }

    private void inspectBlock(Player player, Block block) {
        var db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            player.sendMessage("\u00A7cDatabase not available.");
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingInspections.put(
                playerId,
                new InspectionTarget(
                        block.getWorld().getName(),
                        block.getX(),
                        block.getY(),
                        block.getZ()
                )
        );
        if (!inspectionsInFlight.add(playerId)) {
            return;
        }
        runInspectionQuery(player, playerId);
    }

    private void runInspectionQuery(Player player, UUID playerId) {
        InspectionTarget target = pendingInspections.remove(playerId);
        if (target == null) {
            inspectionsInFlight.remove(playerId);
            return;
        }

        var server = plugin.getServer();
        var db = plugin.getDatabase();
        server.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.BlockLogEntry> entries = List.of();
            SQLException queryError = null;
            try {
                entries = db.getRecentActionsAtBlock(
                        target.worldName(),
                        target.x(),
                        target.y(),
                        target.z(),
                        10
                );
            } catch (SQLException e) {
                queryError = e;
            }

            List<Database.BlockLogEntry> finalEntries = entries;
            SQLException finalQueryError = queryError;
            server.getScheduler().runTask(plugin, () -> {
                boolean superseded = pendingInspections.containsKey(playerId);
                if (player.isOnline() && plugin.isInspecting(playerId) && !superseded) {
                    if (finalQueryError == null) {
                        sendInspectionResult(player, target, finalEntries);
                    } else {
                        plugin.getLogger().warning("Failed to query block history: "
                                + finalQueryError.getMessage());
                        player.sendMessage("\u00A7cFailed to query block history. Try again shortly.");
                    }
                }

                if (pendingInspections.containsKey(playerId)
                        && player.isOnline()
                        && plugin.isInspecting(playerId)) {
                    runInspectionQuery(player, playerId);
                } else {
                    inspectionsInFlight.remove(playerId);
                }
            });
        });
    }

    private void sendInspectionResult(
            Player player,
            InspectionTarget target,
            List<Database.BlockLogEntry> entries
    ) {
        player.sendMessage("\u00A7e[History] \u00A7f(" + target.x() + ", "
                + target.y() + ", " + target.z() + "):");

        if (entries.isEmpty()) {
            player.sendMessage("\u00A77No logged actions for this block.");
            return;
        }

        for (Database.BlockLogEntry entry : entries) {
            String timeStr = timeFormatter.format(Instant.ofEpochMilli(entry.createdAt()));
            String actionStr = switch (entry.action()) {
                case PLACED -> "\u00A7aPLACED";
                case BROKEN -> "\u00A7cBROKEN";
                case INTERACTION -> "\u00A7bINTERACTED";
            };

            String causeStr = entry.cause() != null ? entry.cause().name() : "UNKNOWN";
            player.sendMessage("\u00A77[" + timeStr + "] "
                    + "\u00A7b" + entry.playerName() + " \u00A77"
                    + actionStr + " \u00A7f" + entry.blockType()
                    + " \u00A78(" + causeStr + ")");
        }
    }

    private String logAction(Player player, Block block, BlockActionType action) {
        var db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            return null;
        }

        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String blockType = block.getType().name();
        String blockData = block.getBlockData().getAsString();
        long now = System.currentTimeMillis();

        return db.enqueueBlockAction(
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                blockType,
                blockData,
                action,
                now,
                BlockActionCause.PLAYER
        );
    }

    private boolean isInteractiveBlock(Block block) {
        Material type = block.getType();

        // doors and gates of any type
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) {
            return true;
        }

        return type == Material.LEVER || Tag.BUTTONS.isTagged(type);
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
            Inventory inventory,
            Map<Material, Integer> snapshot
    ) {}

    private record PendingContainerSession(String eventId, String worldName, int x, int y, int z) {}

    private record InspectionTarget(String worldName, int x, int y, int z) {}

    private record ExplosionBlockData(
            String worldName,
            int x,
            int y,
            int z,
            String blockType,
            String blockData
    ) {}
}
