package org.markski.blocklog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlockActionListener implements Listener {

    private static final String AUTOMATION_UUID = "00000000-0000-0000-0000-000000000001";
    private static final int INSPECTION_PAGE_SIZE = 8;

    private final Main plugin;

    private final Map<UUID, OpenContainerSession> openContainers = new HashMap<>();
    private final Map<UUID, PendingContainerSession> pendingContainers = new HashMap<>();
    private final Map<UUID, InspectionRequest> pendingInspections = new HashMap<>();
    private final Map<UUID, InspectionTarget> lastInspections = new HashMap<>();
    private final Set<UUID> inspectionsInFlight = new java.util.HashSet<>();

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
        lastInspections.remove(playerId);
        inspectionsInFlight.remove(playerId);
        plugin.removeInspecting(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        PendingContainerSession pending = pendingContainers.remove(player.getUniqueId());
        Inventory inventory = event.getView().getTopInventory();
        UUID playerId = player.getUniqueId();
        ContainerTarget target;
        String eventId;
        if (pending != null) {
            target = new ContainerTarget(pending.worldName(), pending.x(), pending.y(), pending.z());
            eventId = pending.eventId();
        } else {
            target = containerTarget(inventory);
            if (target == null) {
                return;
            }
            var world = plugin.getServer().getWorld(target.worldName());
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(target.x(), target.y(), target.z());
            eventId = logAction(player, block, BlockActionType.INTERACTION);
            if (eventId == null) {
                return;
            }
        }

        openContainers.put(
                playerId,
                new OpenContainerSession(
                        eventId,
                        target.worldName(),
                        target.x(),
                        target.y(),
                        target.z(),
                        inventory
                )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        OpenContainerSession session = openContainers.remove(player.getUniqueId());
        if (session != null && !session.inventory().equals(event.getView().getTopInventory())) {
            openContainers.put(player.getUniqueId(), session);
        }
    }

    private void discardContainerSession(UUID playerId) {
        openContainers.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OpenContainerSession session = openContainers.get(player.getUniqueId());
        if (session == null || !session.inventory().equals(event.getView().getTopInventory())) {
            return;
        }

        Map<Material, Integer> deltas = clickDeltas(event);
        enqueuePlayerDeltas(player, session, deltas);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OpenContainerSession session = openContainers.get(player.getUniqueId());
        if (session == null || !session.inventory().equals(event.getView().getTopInventory())) {
            return;
        }

        Map<Material, Integer> deltas = new HashMap<>();
        int topSize = session.inventory().getSize();
        event.getNewItems().forEach((rawSlot, replacement) -> {
            if (rawSlot < topSize) {
                addStackDelta(deltas, event.getView().getItem(rawSlot), -1);
                addStackDelta(deltas, replacement, 1);
            }
        });
        enqueuePlayerDeltas(player, session, deltas);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (event.getSource().equals(event.getDestination())) {
            return;
        }
        int moved = Math.min(event.getItem().getAmount(), availableCapacity(event.getDestination(), event.getItem()));
        enqueueAutomationDelta(event.getSource(), event.getItem(), -moved, "[TRANSFER]");
        enqueueAutomationDelta(event.getDestination(), event.getItem(), moved, "[TRANSFER]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        int pickedUp = Math.min(item.getAmount(), availableCapacity(event.getInventory(), item));
        enqueueAutomationDelta(event.getInventory(), item, pickedUp, "[ITEM PICKUP]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (event.willConsumeFuel()) {
            Map<Material, Integer> deltas = new HashMap<>();
            addAmountDelta(deltas, event.getFuel(), -1);
            Material remainder = event.getFuel().getType().getCraftingRemainingItem();
            if (remainder != null && remainder != Material.AIR) {
                deltas.merge(remainder, 1, Integer::sum);
            }
            enqueueAutomationDeltas(event.getBlock(), deltas, "[FURNACE]");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        Map<Material, Integer> deltas = new HashMap<>();
        addAmountDelta(deltas, event.getSource(), -1);
        addStackDelta(deltas, event.getResult(), 1);
        enqueueAutomationDeltas(event.getBlock(), deltas, "[FURNACE]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (event.isConsuming()) {
            enqueueAutomationDelta(event.getBlock(), event.getFuel(), -1, "[BREWING]");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        Map<Material, Integer> deltas = new HashMap<>();
        addAmountDelta(deltas, event.getContents().getIngredient(), -1);
        for (int slot = 0; slot < Math.min(3, event.getResults().size()); slot++) {
            addStackDelta(deltas, event.getContents().getItem(slot), -1);
            addStackDelta(deltas, event.getResults().get(slot), 1);
        }
        enqueueAutomationDeltas(event.getBlock(), deltas, "[BREWING]");
    }

    private Map<Material, Integer> clickDeltas(InventoryClickEvent event) {
        Map<Material, Integer> deltas = new HashMap<>();
        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().equals(top);
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (current == null || current.getType() == Material.AIR) {
                return deltas;
            }
            Inventory destination = clickedTop ? event.getView().getBottomInventory() : top;
            if (availableCapacity(destination, current) >= current.getAmount()) {
                addStackDelta(deltas, current, clickedTop ? -1 : 1);
            }
            return deltas;
        }
        if (!clickedTop) {
            return deltas;
        }

        switch (event.getAction()) {
            case PICKUP_ALL -> addAmountDelta(deltas, current, -amount(current));
            case PICKUP_HALF -> addAmountDelta(deltas, current, -(amount(current) + 1) / 2);
            case PICKUP_ONE -> addAmountDelta(deltas, current, -Math.min(1, amount(current)));
            case PICKUP_SOME -> {
                int capacity = cursor == null || cursor.getType() == Material.AIR
                        ? amount(current)
                        : Math.max(0, cursor.getMaxStackSize() - cursor.getAmount());
                addAmountDelta(deltas, current, -Math.min(amount(current), capacity));
            }
            case PLACE_ALL -> addAmountDelta(deltas, cursor, amount(cursor));
            case PLACE_ONE -> addAmountDelta(deltas, cursor, Math.min(1, amount(cursor)));
            case PLACE_SOME -> {
                int capacity = current == null || current.getType() == Material.AIR
                        ? amount(cursor)
                        : Math.max(0, current.getMaxStackSize() - current.getAmount());
                addAmountDelta(deltas, cursor, Math.min(amount(cursor), capacity));
            }
            case SWAP_WITH_CURSOR -> {
                addStackDelta(deltas, current, -1);
                addStackDelta(deltas, cursor, 1);
            }
            case DROP_ALL_SLOT -> addAmountDelta(deltas, current, -amount(current));
            case DROP_ONE_SLOT -> addAmountDelta(deltas, current, -Math.min(1, amount(current)));
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                addStackDelta(deltas, current, -1);
                int hotbarButton = event.getHotbarButton();
                if (hotbarButton >= 0) {
                    addStackDelta(deltas, event.getWhoClicked().getInventory().getItem(hotbarButton), 1);
                }
            }
            default -> {
            }
        }
        return deltas;
    }

    private void enqueuePlayerDeltas(
            Player player,
            OpenContainerSession session,
            Map<Material, Integer> deltas
    ) {
        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        deltas.forEach((material, delta) -> {
            if (delta != 0) {
                db.enqueueContainerTransaction(
                        session.eventId(),
                        player.getUniqueId().toString(),
                        player.getName(),
                        session.worldName(),
                        session.x(), session.y(), session.z(),
                        material.name(),
                        delta,
                        now
                );
            }
        });
    }

    private void enqueueAutomationDelta(Inventory inventory, ItemStack item, int delta, String actor) {
        if (item == null || item.getType() == Material.AIR || delta == 0) {
            return;
        }
        ContainerTarget target = containerTarget(inventory);
        if (target == null) {
            return;
        }
        var world = plugin.getServer().getWorld(target.worldName());
        if (world == null) {
            return;
        }
        enqueueAutomationDelta(world.getBlockAt(target.x(), target.y(), target.z()), item, delta, actor);
    }

    private void enqueueAutomationDelta(Block block, ItemStack item, int delta, String actor) {
        Map<Material, Integer> deltas = new HashMap<>();
        addAmountDelta(deltas, item, delta);
        enqueueAutomationDeltas(block, deltas, actor);
    }

    private void enqueueAutomationDeltas(Block block, Map<Material, Integer> deltas, String actor) {
        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen() || deltas.values().stream().allMatch(delta -> delta == 0)) {
            return;
        }
        long now = System.currentTimeMillis();
        String eventId = db.enqueueBlockAction(
                AUTOMATION_UUID,
                actor,
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(),
                block.getType().name(),
                block.getBlockData().getAsString(),
                null,
                BlockActionType.INTERACTION,
                now,
                BlockActionCause.AUTOMATION
        );
        deltas.forEach((material, delta) -> {
            if (delta != 0) {
                db.enqueueContainerTransaction(
                        eventId,
                        AUTOMATION_UUID,
                        actor,
                        block.getWorld().getName(),
                        block.getX(), block.getY(), block.getZ(),
                        material.name(),
                        delta,
                        now
                );
            }
        });
    }

    private static int availableCapacity(Inventory inventory, ItemStack item) {
        int capacity = 0;
        int stackLimit = Math.min(inventory.getMaxStackSize(), item.getMaxStackSize());
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType() == Material.AIR) {
                capacity += stackLimit;
            } else if (existing.isSimilar(item)) {
                capacity += Math.max(0, Math.min(stackLimit, existing.getMaxStackSize()) - existing.getAmount());
            }
            if (capacity >= item.getAmount()) {
                return capacity;
            }
        }
        return capacity;
    }

    private static int amount(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
    }

    private static void addStackDelta(Map<Material, Integer> deltas, ItemStack item, int multiplier) {
        addAmountDelta(deltas, item, amount(item) * multiplier);
    }

    private static void addAmountDelta(Map<Material, Integer> deltas, ItemStack item, int delta) {
        if (item != null && item.getType() != Material.AIR && delta != 0) {
            deltas.merge(item.getType(), delta, Integer::sum);
        }
    }

    private static ContainerTarget containerTarget(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (!(holder instanceof BlockState) && !(holder instanceof DoubleChest)) {
            return null;
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new ContainerTarget(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        List<ExplosionBlockData> blocks = new ArrayList<>();
        for (Block block : event.blockList()) {
            blocks.add(new ExplosionBlockData(
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    block.getType().name(),
                    block.getBlockData().getAsString(),
                    rollbackSkipReason(block)
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
                        data.rollbackSkipReason(),
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
                    block.getBlockData().getAsString(),
                    rollbackSkipReason(block)
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
                        data.rollbackSkipReason(),
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
            player.sendMessage(Messages.error("Database not available."));
            return;
        }

        UUID playerId = player.getUniqueId();
        InspectionTarget target = new InspectionTarget(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
        lastInspections.put(playerId, target);
        queueInspection(player, target, 1);
    }

    public void showInspectionPage(Player player, int page) {
        if (!plugin.isInspecting(player.getUniqueId())) {
            player.sendMessage(Messages.error("Enable inspect mode before selecting a history page."));
            return;
        }
        InspectionTarget target = lastInspections.get(player.getUniqueId());
        if (target == null) {
            player.sendMessage(Messages.error("Inspect a block before selecting a history page."));
            return;
        }
        queueInspection(player, target, page);
    }

    private void queueInspection(Player player, InspectionTarget target, int page) {
        UUID playerId = player.getUniqueId();
        pendingInspections.put(playerId, new InspectionRequest(target, page));
        if (!inspectionsInFlight.add(playerId)) {
            return;
        }
        runInspectionQuery(player, playerId);
    }

    private void runInspectionQuery(Player player, UUID playerId) {
        InspectionRequest request = pendingInspections.remove(playerId);
        if (request == null) {
            inspectionsInFlight.remove(playerId);
            return;
        }

        var server = plugin.getServer();
        var db = plugin.getDatabase();
        server.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.BlockHistoryPage historyPage = null;
            SQLException queryError = null;
            try {
                InspectionTarget target = request.target();
                historyPage = db.getActionsAtBlockPage(
                        target.worldName(),
                        target.x(),
                        target.y(),
                        target.z(),
                        request.page(),
                        INSPECTION_PAGE_SIZE
                );
            } catch (SQLException e) {
                queryError = e;
            }

            Database.BlockHistoryPage finalHistoryPage = historyPage;
            SQLException finalQueryError = queryError;
            server.getScheduler().runTask(plugin, () -> {
                boolean superseded = pendingInspections.containsKey(playerId);
                if (player.isOnline() && plugin.isInspecting(playerId) && !superseded) {
                    if (finalQueryError == null) {
                        sendInspectionResult(player, request.target(), finalHistoryPage);
                    } else {
                        plugin.getLogger().warning("Failed to query block history: "
                                + finalQueryError.getMessage());
                        player.sendMessage(Messages.error("Failed to query block history. Try again shortly."));
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
            Database.BlockHistoryPage historyPage
    ) {
        Component header = Component.text("[History] ", NamedTextColor.YELLOW)
                .append(Component.text("(" + target.x() + ", " + target.y() + ", " + target.z() + ") ",
                        NamedTextColor.WHITE))
                .append(Component.text("Page " + historyPage.page() + "/" + historyPage.totalPages()
                        + " · " + historyPage.totalEntries() + " entries", NamedTextColor.GRAY));
        player.sendMessage(header);

        if (historyPage.entries().isEmpty()) {
            player.sendMessage(Messages.muted("No logged actions for this block."));
            return;
        }

        for (Database.BlockLogEntry entry : historyPage.entries()) {
            String time = plugin.getTimestampFormatter().format(Instant.ofEpochMilli(entry.createdAt()));
            NamedTextColor actionColor = switch (entry.action()) {
                case PLACED -> NamedTextColor.GREEN;
                case BROKEN -> NamedTextColor.RED;
                case INTERACTION -> NamedTextColor.AQUA;
            };
            String cause = entry.cause() != null ? entry.cause().name() : "UNKNOWN";
            String details = time + " | " + entry.playerName() + " | " + entry.action().name()
                    + " | " + entry.blockType() + " | " + cause;
            if (entry.transactionSummary() != null) {
                details += " | " + entry.transactionSummary();
                if (entry.transactionCount() > 20) {
                    details += " | " + (entry.transactionCount() - 20) + " more item changes";
                }
            }
            Component hover = Component.text("Click to copy details", NamedTextColor.YELLOW);
            if (entry.transactionSummary() != null) {
                hover = hover.appendNewline()
                        .append(Component.text("Items: " + entry.transactionSummary(), NamedTextColor.GRAY));
                if (entry.transactionCount() > 20) {
                    hover = hover.appendNewline().append(Component.text(
                            "+" + (entry.transactionCount() - 20) + " older item changes",
                            NamedTextColor.DARK_GRAY
                    ));
                }
            }
            Component row = Component.text("[" + time + "] ", NamedTextColor.GRAY)
                    .append(Component.text(entry.playerName() + " ", NamedTextColor.AQUA))
                    .append(Component.text(entry.action().name() + " ", actionColor))
                    .append(Component.text(entry.blockType() + " ", NamedTextColor.WHITE))
                    .append(Component.text("(" + cause + ")", NamedTextColor.DARK_GRAY))
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.copyToClipboard(details));
            player.sendMessage(row);
        }

        Component navigation = Component.empty();
        if (historyPage.page() > 1) {
            navigation = navigation.append(pageButton("< Previous", historyPage.page() - 1));
        }
        if (historyPage.page() > 1 && historyPage.page() < historyPage.totalPages()) {
            navigation = navigation.append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
        }
        if (historyPage.page() < historyPage.totalPages()) {
            navigation = navigation.append(pageButton("Next >", historyPage.page() + 1));
        }
        if (!navigation.equals(Component.empty())) {
            player.sendMessage(navigation);
        }
    }

    private static Component pageButton(String label, int page) {
        return Component.text(label, NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(Component.text("Open page " + page, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand("/bkl page " + page));
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
        String rollbackSkipReason = action == BlockActionType.INTERACTION
                ? null
                : rollbackSkipReason(block);
        long now = System.currentTimeMillis();

        return db.enqueueBlockAction(
                playerUuid,
                playerName,
                worldName,
                x, y, z,
                blockType,
                blockData,
                rollbackSkipReason,
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

    private static String rollbackSkipReason(Block block) {
        if (block.getState() instanceof TileState) {
            return "TILE_ENTITY";
        }

        Material type = block.getType();
        if (type.name().endsWith("_BED")) {
            return "MULTI_BLOCK";
        }
        if (block.getBlockData() instanceof Bisected && !Tag.TRAPDOORS.isTagged(type)) {
            return "MULTI_BLOCK";
        }
        return null;
    }

    private record OpenContainerSession(
            String eventId,
            String worldName,
            int x,
            int y,
            int z,
            Inventory inventory
    ) {}

    private record PendingContainerSession(String eventId, String worldName, int x, int y, int z) {}

    private record ContainerTarget(String worldName, int x, int y, int z) {}

    private record InspectionTarget(String worldName, int x, int y, int z) {}

    private record InspectionRequest(InspectionTarget target, int page) {}

    private record ExplosionBlockData(
            String worldName,
            int x,
            int y,
            int z,
            String blockType,
            String blockData,
            String rollbackSkipReason
    ) {}
}
