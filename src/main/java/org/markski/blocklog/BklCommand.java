package org.markski.blocklog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class BklCommand implements CommandExecutor {

    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_ROLLBACK_HOURS = 720;
    private static final int MAX_ROLLBACK_RADIUS = 256;
    private static final int MAX_ROLLBACK_ENTRIES = 50000;
    private static final int ROLLBACK_BATCH_SIZE = 64;
    private static final long ROLLBACK_TICK_BUDGET_NANOS = 2_000_000L;
    private static final long PREVIEW_EXPIRY_MILLIS = 60_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 5_000_000_000L;

    private final Main plugin;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean rollbackInProgress = new AtomicBoolean();
    private final Map<UUID, PendingRollback> pendingRollbacks = new HashMap<>();
    private final Set<UUID> previewsInFlight = new HashSet<>();
    private final Set<UUID> cancelledPreviews = new HashSet<>();
    private RollbackTask activeRollback;

    public BklCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("bkl")) {
            return false;
        }
        if (!(sender instanceof Player executor)) {
            sender.sendMessage(Messages.error("This command can only be used by a player."));
            return true;
        }
        if (args.length == 0) {
            Database db = plugin.getDatabase();
            executor.sendMessage(db != null && db.isOpen()
                    ? Messages.success("BlockLog is loaded.")
                    : Messages.info("BlockLog is still initializing."));
            return true;
        }
        if (args[0].equalsIgnoreCase("i")) {
            return toggleInspect(executor);
        }
        if (args[0].equalsIgnoreCase("rollback")) {
            return handleRollback(executor, args);
        }
        if (args[0].equalsIgnoreCase("page")) {
            return showInspectionPage(executor, args);
        }

        sendUsage(executor);
        return true;
    }

    public void shutdown() {
        pendingRollbacks.clear();
        previewsInFlight.clear();
        cancelledPreviews.clear();
        if (activeRollback != null) {
            activeRollback.abortForShutdown();
        }
    }

    private boolean toggleInspect(Player executor) {
        if (!executor.hasPermission("blocklog.inspect")) {
            executor.sendMessage(Messages.error("You don't have permission to use /bkl i."));
            return true;
        }

        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            executor.sendMessage(Messages.error("Database not available."));
            return true;
        }
        db.requestFlush();

        boolean nowInspecting = plugin.toggleInspect(executor.getUniqueId());
        executor.sendMessage(nowInspecting
                ? Messages.success("BlockLog inspect mode enabled. Hit or place blocks to inspect them.")
                : Messages.info("BlockLog inspect mode disabled."));
        return true;
    }

    private boolean showInspectionPage(Player executor, String[] args) {
        if (!executor.hasPermission("blocklog.inspect")) {
            executor.sendMessage(Messages.error("You don't have permission to inspect blocks."));
            return true;
        }
        if (args.length != 2) {
            executor.sendMessage(Messages.error("Usage: /bkl page <number>"));
            return true;
        }
        int page;
        try {
            page = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            executor.sendMessage(Messages.error("Page must be a number."));
            return true;
        }
        if (page < 1 || page > 100_000) {
            executor.sendMessage(Messages.error("Page must be between 1 and 100000."));
            return true;
        }
        BlockActionListener listener = plugin.getBlockActionListener();
        if (listener == null) {
            executor.sendMessage(Messages.error("BlockLog is still initializing."));
            return true;
        }
        listener.showInspectionPage(executor, page);
        return true;
    }

    private boolean handleRollback(Player executor, String[] args) {
        if (!executor.hasPermission("blocklog.rollback")) {
            executor.sendMessage(Messages.error("You don't have permission to use /bkl rollback."));
            return true;
        }
        if (args.length < 2) {
            sendRollbackUsage(executor);
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "preview" -> previewRollback(executor, args);
            case "confirm" -> confirmRollback(executor, args);
            case "cancel" -> cancelRollback(executor);
            case "status" -> rollbackStatus(executor);
            default -> {
                sendRollbackUsage(executor);
                yield true;
            }
        };
    }

    private boolean previewRollback(Player executor, String[] args) {
        if (args.length != 5) {
            executor.sendMessage(Messages.error("Usage: /bkl rollback preview <playerName> <hours> <radius>"));
            return true;
        }
        if (!previewsInFlight.add(executor.getUniqueId())) {
            executor.sendMessage(Messages.error("A rollback preview is already running."));
            return true;
        }

        RollbackRequest request = parseRequest(executor, args[2], args[3], args[4]);
        if (request == null) {
            previewsInFlight.remove(executor.getUniqueId());
            return true;
        }

        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            previewsInFlight.remove(executor.getUniqueId());
            executor.sendMessage(Messages.error("Database not available."));
            return true;
        }

        pendingRollbacks.remove(executor.getUniqueId());
        cancelledPreviews.remove(executor.getUniqueId());
        executor.sendMessage(Messages.info("Calculating rollback preview..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.flushPendingActionsNow();
                List<Database.RollbackEntry> entries = loadEntries(db, request);
                Bukkit.getScheduler().runTask(plugin, () -> finishPreview(executor, request, entries));
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().severe("Rollback preview failed: " + e.getMessage());
                finishPreviewFailure(executor, Messages.error("Rollback preview failed. Check the console."));
            }
        });
        return true;
    }

    private void finishPreview(
            Player executor,
            RollbackRequest request,
            List<Database.RollbackEntry> entries
    ) {
        UUID executorId = executor.getUniqueId();
        previewsInFlight.remove(executorId);
        if (cancelledPreviews.remove(executorId) || !executor.isOnline()) {
            return;
        }
        if (entries.size() > MAX_ROLLBACK_ENTRIES) {
            executor.sendMessage(Messages.error("Preview matched more than " + MAX_ROLLBACK_ENTRIES
                    + " events. Use a smaller time window or radius."));
            return;
        }
        if (entries.isEmpty()) {
            executor.sendMessage(Messages.muted("No actions found in that rollback scope."));
            return;
        }

        RollbackSummary summary = summarize(entries);
        if (summary.supportedEvents() == 0) {
            executor.sendMessage(Messages.error("All matching events are unsupported and will be skipped."));
            executor.sendMessage(Messages.muted("Unsupported: " + formatReasons(summary.unsupportedReasons())));
            return;
        }
        String token = createToken();
        PendingRollback pending = new PendingRollback(
                request,
                summary,
                token,
                System.currentTimeMillis() + PREVIEW_EXPIRY_MILLIS
        );
        pendingRollbacks.put(executorId, pending);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> pendingRollbacks.remove(executorId, pending),
                PREVIEW_EXPIRY_MILLIS / 50L
        );

        executor.sendMessage(Component.text("Rollback preview: ", NamedTextColor.YELLOW)
                .append(Component.text(summary.totalEvents(), NamedTextColor.AQUA))
                .append(Component.text(" events across ", NamedTextColor.YELLOW))
                .append(Component.text(summary.chunkCount(), NamedTextColor.AQUA))
                .append(Component.text(" chunks.", NamedTextColor.YELLOW)));
        executor.sendMessage(Component.text("Supported: " + summary.supportedEvents(), NamedTextColor.GREEN)
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text("Unsupported/skipped: " + summary.unsupportedEvents(), NamedTextColor.RED)));
        if (!summary.unsupportedReasons().isEmpty()) {
            executor.sendMessage(Messages.muted("Unsupported: " + formatReasons(summary.unsupportedReasons())));
        }
        String confirmCommand = "/bkl rollback confirm " + token;
        executor.sendMessage(Component.text("Confirm within 60 seconds: ", NamedTextColor.YELLOW)
                .append(Component.text("[Confirm rollback]", NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(Component.text(confirmCommand, NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand(confirmCommand))));
    }

    private void finishPreviewFailure(Player executor, Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            previewsInFlight.remove(executor.getUniqueId());
            if (executor.isOnline()) {
                executor.sendMessage(message);
            }
        });
    }

    private boolean confirmRollback(Player executor, String[] args) {
        if (args.length != 3) {
            executor.sendMessage(Messages.error("Usage: /bkl rollback confirm <token>"));
            return true;
        }

        UUID executorId = executor.getUniqueId();
        PendingRollback pending = pendingRollbacks.get(executorId);
        if (pending == null
                || pending.expiresAt() < System.currentTimeMillis()
                || !pending.token().equalsIgnoreCase(args[2])) {
            pendingRollbacks.remove(executorId);
            executor.sendMessage(Messages.error("No matching rollback preview exists, or its token expired."));
            return true;
        }
        if (!rollbackInProgress.compareAndSet(false, true)) {
            executor.sendMessage(Messages.error("Another rollback is already running."));
            return true;
        }
        pendingRollbacks.remove(executorId);

        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            rollbackInProgress.set(false);
            executor.sendMessage(Messages.error("Database not available."));
            return true;
        }

        executor.sendMessage(Messages.info("Revalidating rollback scope..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.flushPendingActionsNow();
                List<Database.RollbackEntry> entries = loadEntries(db, pending.request());
                if (entries.isEmpty() || entries.size() > MAX_ROLLBACK_ENTRIES) {
                    finishPreparation(executor, Messages.error("Rollback scope is now empty or too large. Preview it again."));
                    return;
                }

                RollbackSummary actual = summarize(entries);
                if (!actual.targetUuid().equals(pending.summary().targetUuid())
                        || !actual.fingerprint().equals(pending.summary().fingerprint())) {
                    finishPreparation(executor, Messages.error("The rollback scope changed. Preview it again."));
                    return;
                }

                String auditId = UUID.randomUUID().toString();
                Database.RollbackAuditStart audit = createAudit(executor, pending, actual, auditId);
                db.createRollbackAudit(audit).whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().severe("Failed to create rollback audit: " + error.getMessage());
                        finishPreparation(executor, Messages.error("Rollback audit could not be persisted; no blocks were changed."));
                        return;
                    }
                    if (!plugin.isEnabled()) {
                        db.finishRollbackAudit(
                                auditId,
                                Database.RollbackAuditStatus.CANCELLED,
                                0,
                                0,
                                "Plugin disabled before execution"
                        );
                        rollbackInProgress.set(false);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> beginRollback(executor, pending, entries, auditId));
                });
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().severe("Rollback preparation failed: " + e.getMessage());
                finishPreparation(executor, Messages.error("Rollback preparation failed. Check the console."));
            }
        });
        return true;
    }

    private void beginRollback(
            Player executor,
            PendingRollback pending,
            List<Database.RollbackEntry> entries,
            String auditId
    ) {
        World world = Bukkit.getWorld(pending.request().worldName());
        if (world == null || !executor.isOnline()) {
            plugin.getDatabase().finishRollbackAudit(
                    auditId,
                    Database.RollbackAuditStatus.CANCELLED,
                    0,
                    0,
                    "World unavailable or executor disconnected"
            );
            rollbackInProgress.set(false);
            return;
        }

        activeRollback = new RollbackTask(executor, world, entries, auditId);
        executor.sendMessage(Messages.info("Rollback started. Use /bkl rollback cancel to stop it."));
        activeRollback.run();
    }

    private boolean cancelRollback(Player executor) {
        UUID executorId = executor.getUniqueId();
        boolean cancelled = pendingRollbacks.remove(executorId) != null;
        if (previewsInFlight.contains(executorId)) {
            cancelledPreviews.add(executorId);
            cancelled = true;
        }
        if (activeRollback != null && activeRollback.executorId().equals(executorId)) {
            activeRollback.requestCancel();
            cancelled = true;
        }

        executor.sendMessage(cancelled
                ? Messages.info("Rollback preview or execution cancellation requested.")
                : Messages.muted("You have no rollback to cancel."));
        return true;
    }

    private boolean rollbackStatus(Player executor) {
        if (activeRollback != null) {
            executor.sendMessage(activeRollback.progressMessage());
            return true;
        }
        PendingRollback pending = pendingRollbacks.get(executor.getUniqueId());
        if (pending != null && pending.expiresAt() >= System.currentTimeMillis()) {
            executor.sendMessage(Messages.info("A preview is awaiting confirmation for "
                    + Math.max(0, (pending.expiresAt() - System.currentTimeMillis()) / 1000)
                    + " more seconds."));
            return true;
        }
        executor.sendMessage(Messages.muted("No rollback is active."));
        return true;
    }

    private RollbackRequest parseRequest(Player executor, String targetName, String hoursArg, String radiusArg) {
        if (!PLAYER_NAME_PATTERN.matcher(targetName).matches()) {
            executor.sendMessage(Messages.error("Player name must contain 1-16 letters, numbers, or underscores."));
            return null;
        }

        int hours;
        int radius;
        try {
            hours = Integer.parseInt(hoursArg);
            radius = Integer.parseInt(radiusArg);
        } catch (NumberFormatException e) {
            executor.sendMessage(Messages.error("Hours and radius must be numbers."));
            return null;
        }
        if (hours <= 0 || radius <= 0) {
            executor.sendMessage(Messages.error("Hours and radius must be greater than 0."));
            return null;
        }
        if (hours > MAX_ROLLBACK_HOURS || radius > MAX_ROLLBACK_RADIUS) {
            executor.sendMessage(Messages.error("Maximum rollback scope is " + MAX_ROLLBACK_HOURS
                    + " hours and radius " + MAX_ROLLBACK_RADIUS + "."));
            return null;
        }

        World world = executor.getWorld();
        int centerX = executor.getLocation().getBlockX();
        int centerY = executor.getLocation().getBlockY();
        int centerZ = executor.getLocation().getBlockZ();
        return new RollbackRequest(
                targetName,
                world.getName(),
                centerX,
                centerY,
                centerZ,
                radius,
                System.currentTimeMillis() - hours * 60L * 60L * 1000L,
                centerX - radius,
                centerX + radius,
                Math.max(world.getMinHeight(), centerY - radius),
                Math.min(world.getMaxHeight() - 1, centerY + radius),
                centerZ - radius,
                centerZ + radius
        );
    }

    private List<Database.RollbackEntry> loadEntries(Database db, RollbackRequest request) throws SQLException {
        List<Database.RollbackEntry> candidates = db.getActionsForRollback(
                request.targetName(),
                request.worldName(),
                request.fromTime(),
                request.minX(),
                request.maxX(),
                request.minY(),
                request.maxY(),
                request.minZ(),
                request.maxZ(),
                MAX_ROLLBACK_ENTRIES + 1
        );
        if (candidates.size() > MAX_ROLLBACK_ENTRIES) {
            throw new IllegalArgumentException("Rollback bounding box matched too many events.");
        }
        List<Database.RollbackEntry> entries = new ArrayList<>(candidates.size());
        long radiusSquared = (long) request.radius() * request.radius();
        for (Database.RollbackEntry entry : candidates) {
            long dx = (long) entry.x() - request.centerX();
            long dy = (long) entry.y() - request.centerY();
            long dz = (long) entry.z() - request.centerZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static RollbackSummary summarize(List<Database.RollbackEntry> entries) {
        Set<Long> chunks = new HashSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        for (Database.RollbackEntry entry : entries) {
            digest.update(entry.id().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            long chunkKey = ((long) (entry.x() >> 4) << 32) ^ ((entry.z() >> 4) & 0xffffffffL);
            chunks.add(chunkKey);
            if (entry.rollbackSkipReason() != null) {
                reasons.merge(entry.rollbackSkipReason(), 1, Integer::sum);
            }
        }
        int unsupported = reasons.values().stream().mapToInt(Integer::intValue).sum();
        return new RollbackSummary(
                entries.size(),
                entries.size() - unsupported,
                unsupported,
                chunks.size(),
                Map.copyOf(reasons),
                entries.getFirst().playerUuid(),
                HexFormat.of().formatHex(digest.digest())
        );
    }

    private Database.RollbackAuditStart createAudit(
            Player executor,
            PendingRollback pending,
            RollbackSummary actual,
            String auditId
    ) {
        RollbackRequest request = pending.request();
        return new Database.RollbackAuditStart(
                auditId,
                executor.getUniqueId().toString(),
                executor.getName(),
                actual.targetUuid(),
                request.targetName(),
                request.worldName(),
                request.centerX(),
                request.centerY(),
                request.centerZ(),
                request.radius(),
                request.fromTime(),
                pending.summary().totalEvents(),
                pending.summary().unsupportedEvents(),
                System.currentTimeMillis()
        );
    }

    private String createToken() {
        byte[] bytes = new byte[4];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private void finishPreparation(Player executor, Component message) {
        rollbackInProgress.set(false);
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (executor.isOnline()) {
                executor.sendMessage(message);
            }
        });
    }

    private static String formatReasons(Map<String, Integer> reasons) {
        List<String> values = new ArrayList<>();
        reasons.forEach((reason, count) -> values.add(reason + "=" + count));
        return String.join(", ", values);
    }

    private static void sendUsage(Player player) {
        player.sendMessage(Messages.info("Usage: /bkl i | /bkl page <number> | /bkl rollback <preview|confirm|cancel|status>"));
    }

    private static void sendRollbackUsage(Player player) {
        player.sendMessage(Messages.error("Usage: /bkl rollback preview <playerName> <hours> <radius>"));
        player.sendMessage(Messages.error("       /bkl rollback confirm <token> | cancel | status"));
    }

    private final class RollbackTask implements Runnable {
        private final Player executor;
        private final World world;
        private final List<Database.RollbackEntry> entries;
        private final String auditId;
        private int index;
        private int affected;
        private int skipped;
        private int unsupported;
        private boolean cancelRequested;
        private boolean finished;
        private long lastProgressNanos = System.nanoTime();

        private RollbackTask(
                Player executor,
                World world,
                List<Database.RollbackEntry> entries,
                String auditId
        ) {
            this.executor = executor;
            this.world = world;
            this.entries = entries;
            this.auditId = auditId;
        }

        private UUID executorId() {
            return executor.getUniqueId();
        }

        private void requestCancel() {
            cancelRequested = true;
        }

        private Component progressMessage() {
            return Component.text("Rollback progress: ", NamedTextColor.YELLOW)
                    .append(Component.text(index + "/" + entries.size(), NamedTextColor.AQUA))
                    .append(Component.text(" processed, ", NamedTextColor.YELLOW))
                    .append(Component.text(affected + " changed, ", NamedTextColor.GREEN))
                    .append(Component.text(skipped + " skipped.", NamedTextColor.GRAY));
        }

        @Override
        public void run() {
            if (finished) {
                return;
            }
            if (cancelRequested || !executor.isOnline()) {
                finish(Database.RollbackAuditStatus.CANCELLED, "Rollback cancelled.", true);
                return;
            }

            try {
                long deadline = System.nanoTime() + ROLLBACK_TICK_BUDGET_NANOS;
                int processed = 0;
                while (index < entries.size()
                        && processed < ROLLBACK_BATCH_SIZE
                        && System.nanoTime() < deadline) {
                    Database.RollbackEntry entry = entries.get(index);
                    if (entry.rollbackSkipReason() != null) {
                        unsupported++;
                        skipped++;
                        index++;
                        processed++;
                        continue;
                    }

                    int chunkX = entry.x() >> 4;
                    int chunkZ = entry.z() >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        loadChunkAndResume(chunkX, chunkZ);
                        return;
                    }

                    rollback(entry);
                    index++;
                    processed++;
                }

                long now = System.nanoTime();
                if (now - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                    executor.sendMessage(progressMessage());
                    lastProgressNanos = now;
                }
                if (index >= entries.size()) {
                    finish(Database.RollbackAuditStatus.COMPLETED, "Rollback complete.", true);
                } else {
                    Bukkit.getScheduler().runTask(plugin, this);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Rollback execution failed: " + e.getMessage());
                finish(Database.RollbackAuditStatus.FAILED, "Rollback failed. Check the console.", true);
            }
        }

        private void loadChunkAndResume(int chunkX, int chunkZ) {
            world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || chunk == null) {
                        skipped++;
                        index++;
                    }
                    run();
                });
            });
        }

        private void rollback(Database.RollbackEntry entry) {
            Material logged = Material.getMaterial(entry.blockType());
            if (logged == null) {
                skipped++;
                return;
            }

            BlockData loggedData;
            try {
                loggedData = Bukkit.createBlockData(entry.blockData());
            } catch (IllegalArgumentException e) {
                skipped++;
                return;
            }

            var block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (entry.action() == BlockActionType.PLACED) {
                if (block.getType() == logged
                        && block.getBlockData().getAsString().equals(entry.blockData())) {
                    block.setType(Material.AIR, false);
                    affected++;
                } else {
                    skipped++;
                }
            } else if (entry.action() == BlockActionType.BROKEN) {
                if (block.getType() == Material.AIR) {
                    block.setBlockData(loggedData, false);
                    affected++;
                } else {
                    skipped++;
                }
            }
        }

        private void abortForShutdown() {
            finish(Database.RollbackAuditStatus.CANCELLED, "Plugin disabled.", false);
        }

        private void finish(Database.RollbackAuditStatus status, String message, boolean notifyExecutor) {
            if (finished) {
                return;
            }
            finished = true;
            String details = "processed=" + index + ";unsupported=" + unsupported;
            plugin.getDatabase().finishRollbackAudit(auditId, status, affected, skipped, details);
            activeRollback = null;
            rollbackInProgress.set(false);
            if (notifyExecutor && executor.isOnline()) {
                executor.sendMessage(Component.text(message + " ", NamedTextColor.YELLOW)
                        .append(Component.text(affected + " changed, ", NamedTextColor.GREEN))
                        .append(Component.text(skipped + " skipped (" + unsupported + " unsupported).",
                                NamedTextColor.GRAY)));
            }
        }
    }

    private record RollbackRequest(
            String targetName,
            String worldName,
            int centerX,
            int centerY,
            int centerZ,
            int radius,
            long fromTime,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {}

    private record RollbackSummary(
            int totalEvents,
            int supportedEvents,
            int unsupportedEvents,
            int chunkCount,
            Map<String, Integer> unsupportedReasons,
            String targetUuid,
            String fingerprint
    ) {}

    private record PendingRollback(
            RollbackRequest request,
            RollbackSummary summary,
            String token,
            long expiresAt
    ) {}
}
