package org.markski.blocklog;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;

public class BklCommand implements CommandExecutor {

    private final Main plugin;

    public BklCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             String[] args) {

        if (!command.getName().equalsIgnoreCase("bkl")) {
            return false;
        }

        if (!(sender instanceof Player executor)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("BlockLog is loaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("i")) {
            if (!sender.hasPermission("blocklog.inspect")) {
                sender.sendMessage("§cYou don't have permission to use /bkl i.");
                return true;
            }

            // Just to ensure the "latter" actions are in there, flush it all to db.
            // "Real" solution would be for inspect to check the db AND also the flush queue, but I don't like
            // how that might affect dev(me) UX as the plugin potentially gets more complex.
            Database db = plugin.getDatabase();
            db.flushPendingActionsNow();

            boolean nowInspecting = plugin.toggleInspect(executor.getUniqueId());
            if (nowInspecting) {
                executor.sendMessage("§aBlockLog inspect mode §aenabled§f. Hit or place blocks to inspect them.");
            } else {
                executor.sendMessage("§cBlockLog inspect mode §cdisabled§f.");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("rollback")) {
            if (!sender.hasPermission("blocklog.rollback")) {
                sender.sendMessage("§cYou don't have permission to use /bkl rollback.");
                return true;
            }

            if (args.length < 4) {
                sender.sendMessage("§cUsage: /bkl rollback <playerName> <hours> <radius>");
                return true;
            }

            String targetPlayerName = args[1];

            int hours;
            int radius;
            try {
                hours = Integer.parseInt(args[2]);
                radius = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cHours and radius must be numbers.");
                return true;
            }

            if (hours <= 0 || radius <= 0) {
                sender.sendMessage("§cHours and radius must be greater than 0.");
                return true;
            }

            Database db = plugin.getDatabase();
            if (db == null || db.getConnection() == null) {
                sender.sendMessage("§cDatabase not available.");
                return true;
            }

            World world = executor.getWorld();
            int cx = executor.getLocation().getBlockX();
            int cy = executor.getLocation().getBlockY();
            int cz = executor.getLocation().getBlockZ();

            long now = System.currentTimeMillis();
            long fromTime = now - hours * 60L * 60L * 1000L;

            int minX = cx - radius;
            int maxX = cx + radius;
            int minY = Math.max(0, cy - radius);
            int maxY = Math.min(world.getMaxHeight(), cy + radius);
            int minZ = cz - radius;
            int maxZ = cz + radius;

            sender.sendMessage("§eStarting rollback for §b" + targetPlayerName + "§e, last §b" + hours + "§eh, radius §b" + radius + "§e...");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                db.flushPendingActionsNow();

                List<Database.RollbackEntry> entries;
                try {
                    entries = db.getActionsForRollback(
                            targetPlayerName,
                            world.getName(),
                            fromTime,
                            minX, maxX,
                            minY, maxY,
                            minZ, maxZ
                    );
                } catch (SQLException e) {
                    plugin.getLogger().severe("Rollback query failed: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cRollback failed, check console."));
                    return;
                }

                if (entries.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7No actions found to rollback."));
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    int affected = 0;
                    int skipped = 0;
                    int radiusSq = radius * radius;

                    for (Database.RollbackEntry e : entries) {
                        int x = e.x();
                        int y = e.y();
                        int z = e.z();

                        int dx = x - cx;
                        int dy = y - cy;
                        int dz = z - cz;

                        if (dx * dx + dy * dy + dz * dz > radiusSq) {
                            continue;
                        }

                        var block = world.getBlockAt(x, y, z);
                        Material current = block.getType();
                        Material logged = Material.matchMaterial(e.blockType());

                        if (logged == null) {
                            skipped++;
                            continue;
                        }

                        if (e.action() == BlockActionType.PLACED) {
                            if (current == logged) {
                                block.setType(Material.AIR, false);
                                affected++;
                            } else {
                                skipped++;
                            }
                        } else if (e.action() == BlockActionType.BROKEN) {
                            if (current == Material.AIR) {
                                block.setType(logged, false);
                                affected++;
                            } else {
                                skipped++;
                            }
                        }
                    }

                    sender.sendMessage("§aRollback complete. §b" + affected + "§a blocks changed, §7" + skipped + " skipped.");
                });
            });

            return true;
        }

        sender.sendMessage("Usage: /bkl ['i' for inspect, 'rollback' for rollback.]");
        return true;
    }
}