package com.nullble.goatnetherportals;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class GNPCommandTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 1) {
            return Arrays.asList(
                "portdelete",
                "confirmdelete",
                "cleanup",
                "cleanupself",
                "cleanupmarkers",
                "help",
                "inspect",
                "resyncmap",
                "info",
                "validate",  // Optional
                "link",      // Optional
                "unlink",    // Optional
                "reload",    // Optional (config reload)
                "debug",      // Optional (future debugging)
                "echo",
                "removelink",
                "checkpair",
                "debugzones"
            );
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("portdelete")) {
            return List.of("all");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("portdelete") && args[1].equalsIgnoreCase("all")) {
            if (sender.hasPermission("goatnetherportals.admin")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
