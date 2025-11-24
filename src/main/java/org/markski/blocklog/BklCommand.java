package org.markski.blocklog;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class BklCommand implements CommandExecutor {

    private static final int BLOCK_LOOK_DISTANCE = 10;
    private static final int MAX_RESULTS = 10;

    private final Main plugin;
    private final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public BklCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        var targetBlock = player.getTargetBlockExact(BLOCK_LOOK_DISTANCE);
        if (targetBlock == null) {
            player.sendMessage("§cNo block found.");
            return true;
        }

        var db = plugin.getDatabase();
        if (db == null || db.getConnection() == null) {
            player.sendMessage("§cError finding database.");
            return true;
        }

        String worldName = targetBlock.getWorld().getName();
        int x = targetBlock.getX();
        int y = targetBlock.getY();
        int z = targetBlock.getZ();

        try {
            var entries = db.getRecentActionsAtBlock(worldName, x, y, z, MAX_RESULTS);

            player.sendMessage("§eBlock history at §7" + worldName +
                    " §f(" + x + ", " + y + ", " + z + "):");

            if (entries.isEmpty()) {
                player.sendMessage("§7No logged actions for this block.");
                return true;
            }

            for (var entry : entries) {
                String timeStr = timeFormatter.format(
                        Instant.ofEpochMilli(entry.getCreatedAt())
                );
                String actionStr = switch (entry.getAction()) {
                    case PLACED -> "§aPLACED";
                    case BROKEN -> "§cBROKEN";
                    case INTERACTION -> "&bINTERACTED";
                };

                String causeStr = entry.getCause() != null
                        ? entry.getCause().name()
                        : "UNKNOWN";

                player.sendMessage("§7[" + timeStr + "] " +
                        "§b" + entry.getPlayerName() + " §7" +
                        actionStr + " §f" + entry.getBlockType() +
                        " §8(" + causeStr + ")");
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get block history: " + e.getMessage());
            player.sendMessage("§cFailed to get block history. See console.");
        }

        return true;
    }
}