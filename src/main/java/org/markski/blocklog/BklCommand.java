package org.markski.blocklog;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class BklCommand implements CommandExecutor {

    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_ROLLBACK_HOURS = 720;
    private static final int MAX_ROLLBACK_RADIUS = 256;
    private static final int MAX_ROLLBACK_ENTRIES = 50000;
    private static final int ROLLBACK_BATCH_SIZE = 64;
    private static final long ROLLBACK_TICK_BUDGET_NANOS = 2_000_000L;

    private final Main plugin;
    private final AtomicBoolean rollbackInProgress = new AtomicBoolean();

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
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (args.length == 0) {
            Database db = plugin.getDatabase();
            sender.sendMessage(db != null && db.isOpen()
                    ? "BlockLog is loaded."
                    : "BlockLog is still initializing.");
            return true;
        }

        if (args[0].equalsIgnoreCase("i")) {
            return toggleInspect(executor);
        }

        if (args[0].equalsIgnoreCase("rollback")) {
            return startRollback(executor, args);
        }

        sender.sendMessage("Usage: /bkl ['i' for inspect, 'rollback' for rollback.]");
        return true;
    }

    private boolean toggleInspect(Player executor) {
        if (!executor.hasPermission("blocklog.inspect")) {
            executor.sendMessage("\u00A7cYou don't have permission to use /bkl i.");
            return true;
        }

        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            executor.sendMessage("\u00A7cDatabase not available.");
            return true;
        }
        db.requestFlush();

        boolean nowInspecting = plugin.toggleInspect(executor.getUniqueId());
        if (nowInspecting) {
            executor.sendMessage("\u00A7aBlockLog inspect mode enabled\u00A7f. Hit or place blocks to inspect them.");
        } else {
            executor.sendMessage("\u00A7cBlockLog inspect mode disabled\u00A7f.");
        }
        return true;
    }

    private boolean startRollback(Player executor, String[] args) {
        if (!executor.hasPermission("blocklog.rollback")) {
            executor.sendMessage("\u00A7cYou don't have permission to use /bkl rollback.");
            return true;
        }

        if (args.length < 4) {
            executor.sendMessage("\u00A7cUsage: /bkl rollback <playerName> <hours> <radius>");
            return true;
        }

        String targetPlayerName = args[1];
        if (!PLAYER_NAME_PATTERN.matcher(targetPlayerName).matches()) {
            executor.sendMessage("\u00A7cPlayer name must contain 1-16 letters, numbers, or underscores.");
            return true;
        }

        int hours;
        int radius;
        try {
            hours = Integer.parseInt(args[2]);
            radius = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            executor.sendMessage("\u00A7cHours and radius must be numbers.");
            return true;
        }

        if (hours <= 0 || radius <= 0) {
            executor.sendMessage("\u00A7cHours and radius must be greater than 0.");
            return true;
        }
        if (hours > MAX_ROLLBACK_HOURS || radius > MAX_ROLLBACK_RADIUS) {
            executor.sendMessage("\u00A7cMaximum rollback scope is " + MAX_ROLLBACK_HOURS
                    + " hours and radius " + MAX_ROLLBACK_RADIUS + ".");
            return true;
        }

        Database db = plugin.getDatabase();
        if (db == null || !db.isOpen()) {
            executor.sendMessage("\u00A7cDatabase not available.");
            return true;
        }
        if (!rollbackInProgress.compareAndSet(false, true)) {
            executor.sendMessage("\u00A7cAnother rollback is already running.");
            return true;
        }

        World world = executor.getWorld();
        int centerX = executor.getLocation().getBlockX();
        int centerY = executor.getLocation().getBlockY();
        int centerZ = executor.getLocation().getBlockZ();
        long fromTime = System.currentTimeMillis() - hours * 60L * 60L * 1000L;

        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minY = Math.max(world.getMinHeight(), centerY - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + radius);
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;

        executor.sendMessage("\u00A7eStarting rollback for \u00A7b" + targetPlayerName
                + "\u00A7e, last \u00A7b" + hours + "\u00A7eh, radius \u00A7b" + radius + "\u00A7e...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            db.flushPendingActionsNow();

            List<Database.RollbackEntry> entries;
            try {
                entries = db.getActionsForRollback(
                        targetPlayerName,
                        world.getName(),
                        fromTime,
                        minX,
                        maxX,
                        minY,
                        maxY,
                        minZ,
                        maxZ,
                        MAX_ROLLBACK_ENTRIES + 1
                );
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().severe("Rollback query failed: " + e.getMessage());
                finishRollback(executor, "\u00A7cRollback failed, check console.");
                return;
            }

            if (entries.isEmpty()) {
                finishRollback(executor, "\u00A77No actions found to rollback.");
                return;
            }
            if (entries.size() > MAX_ROLLBACK_ENTRIES) {
                finishRollback(executor, "\u00A7cRollback matched more than " + MAX_ROLLBACK_ENTRIES
                        + " events. Use a smaller time window or radius.");
                return;
            }

            Bukkit.getScheduler().runTask(
                    plugin,
                    new RollbackTask(executor, world, centerX, centerY, centerZ, radius, entries)
            );
        });

        return true;
    }

    private void finishRollback(Player executor, String message) {
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

    private final class RollbackTask implements Runnable {
        private final Player executor;
        private final World world;
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final long radiusSquared;
        private final List<Database.RollbackEntry> entries;
        private int index;
        private int affected;
        private int skipped;

        private RollbackTask(
                Player executor,
                World world,
                int centerX,
                int centerY,
                int centerZ,
                int radius,
                List<Database.RollbackEntry> entries
        ) {
            this.executor = executor;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radiusSquared = (long) radius * radius;
            this.entries = entries;
        }

        @Override
        public void run() {
            if (!executor.isOnline()) {
                rollbackInProgress.set(false);
                return;
            }

            long deadline = System.nanoTime() + ROLLBACK_TICK_BUDGET_NANOS;
            int processed = 0;
            while (index < entries.size()
                    && processed < ROLLBACK_BATCH_SIZE
                    && System.nanoTime() < deadline) {
                Database.RollbackEntry entry = entries.get(index);

                long dx = (long) entry.x() - centerX;
                long dy = (long) entry.y() - centerY;
                long dz = (long) entry.z() - centerZ;
                if (dx * dx + dy * dy + dz * dz > radiusSquared) {
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

            if (index >= entries.size()) {
                rollbackInProgress.set(false);
                executor.sendMessage("\u00A7aRollback complete. \u00A7b" + affected
                        + "\u00A7a blocks changed, \u00A77" + skipped + " skipped.");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, this);
        }

        private void loadChunkAndResume(int chunkX, int chunkZ) {
            world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) {
                    rollbackInProgress.set(false);
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
    }
}
