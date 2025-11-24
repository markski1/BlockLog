package org.markski.blocklog;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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

        if (args.length == 0) {
            sender.sendMessage("BlockLog is loaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("i")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This subcommand can only be used by a player.");
                return true;
            }

            boolean nowInspecting = plugin.toggleInspect(player.getUniqueId());
            if (nowInspecting) {
                player.sendMessage("§aBlockLog inspect mode §aENABLED§f. " +
                        "Hit or place blocks to inspect them.");
            } else {
                player.sendMessage("§cBlockLog inspect mode §cDISABLED§f.");
            }
            return true;
        }

        sender.sendMessage("Usage: /bkl i");
        return true;
    }
}