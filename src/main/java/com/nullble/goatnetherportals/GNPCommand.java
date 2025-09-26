package com.nullble.goatnetherportals;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.UUID;

public class GNPCommand implements CommandExecutor {

    private final GoatNetherPortals plugin;

    public GNPCommand(GoatNetherPortals plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        PortalManager manager = plugin.getPortalManager();

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "link":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 3) {
                    player.sendMessage("§cUsage: /gnp link <linkCode1> <linkCode2>");
                    return true;
                }
                plugin.getPortalManager().linkPortals(args[1], args[2], player);
                return true;
            case "delete":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /gnp delete <linkCode>");
                    return true;
                }
                manager.deletePortal(args[1], player);
                return true;
            case "setowner": {
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 3) {
                    player.sendMessage("§cUsage: /gnp setowner <linkCode> <player>");
                    return true;
                }
                String linkCode = args[1];
                String newOwnerName = args[2];
                UUID newOwnerUUID = manager.getUUIDFromName(newOwnerName);
                if (newOwnerUUID == null) {
                    player.sendMessage("§cPlayer not found: " + newOwnerName);
                    return true;
                }
                manager.setOwner(linkCode, newOwnerUUID, player);
                return true;
            }
            case "find":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /gnp find <player|linkCode>");
                    return true;
                }
                String query = args[1];

                // First, try to find portals by link code
                java.util.List<Portal> portalsByLinkCode = manager.findPortalsByLinkCode(query);
                if (!portalsByLinkCode.isEmpty()) {
                    player.sendMessage("§6Portals found for link code: §f" + query);
                    for (Portal p : portalsByLinkCode) {
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(p.getOwner());
                        player.sendMessage("§e- Owner: §f" + (owner.getName() != null ? owner.getName() : "Unknown"));
                        Location loc = p.getLocation();

                        TextComponent locationComponent = new TextComponent("  §e- Location: §f" + loc.getWorld().getName() + " at " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                        locationComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/teleport " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()));
                        player.spigot().sendMessage(locationComponent);
                    }
                    return true;
                }

                // If not found by link code, try to find by player name
                UUID targetUUID = manager.getUUIDFromName(query);
                java.util.List<Portal> portalsByOwner = manager.findPortalsByOwner(targetUUID);
                if (!portalsByOwner.isEmpty()) {
                    player.sendMessage("§6Portals for player " + query + ":");
                    java.util.Map<String, java.util.List<Portal>> groupedPortals = new java.util.HashMap<>();
                    for (Portal p : portalsByOwner) {
                        groupedPortals.computeIfAbsent(p.getLinkCode(), k -> new java.util.ArrayList<>()).add(p);
                    }

                    for (java.util.Map.Entry<String, java.util.List<Portal>> entry : groupedPortals.entrySet()) {
                        player.sendMessage("§e- Link Code: §f" + entry.getKey());
                        for (Portal p : entry.getValue()) {
                            Location loc = p.getLocation();
                            TextComponent locationComponent = new TextComponent("  §e- Location: §f" + loc.getWorld().getName() + " at " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                            locationComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/teleport " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()));
                            player.spigot().sendMessage(locationComponent);
                        }
                    }
                } else {
                    player.sendMessage("§cNo portal or player found for query: " + query);
                }
                return true;

            case "inspect":
                plugin.getInspectListener().toggleInspectMode(player);
                return true;


            case "move":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /gnp move <linkCode>");
                    return true;
                }
                manager.movePortal(args[1], player.getLocation(), player);
                return true;


            case "restore":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /gnp restore <linkCode> [worldName]");
                    return true;
                }
                String linkCodeToRestore = args[1];
                if (args.length == 3) {
                    String worldNameToRestore = args[2];
                    manager.restorePortal(linkCodeToRestore, worldNameToRestore, player);
                } else {
                    java.util.List<String> backups = manager.getBackupFiles().stream()
                        .filter(f -> f.startsWith(linkCodeToRestore + "-"))
                        .collect(java.util.stream.Collectors.toList());

                    if (backups.isEmpty()) {
                        player.sendMessage("§cNo backups found for link code: " + linkCodeToRestore);
                    } else if (backups.size() == 1) {
                        String fileName = backups.get(0);
                        String worldName = fileName.substring(linkCodeToRestore.length() + 1, fileName.length() - 4);
                        manager.restorePortal(linkCodeToRestore, worldName, player);
                    } else {
                        player.sendMessage("§eMultiple backups found for that link code. Please specify a world:");
                        for (String backupFile : backups) {
                             String worldName = backupFile.substring(linkCodeToRestore.length() + 1, backupFile.length() - 4);
                             player.sendMessage("§e- " + worldName);
                        }
                        player.sendMessage("§eUsage: /gnp restore " + linkCodeToRestore + " <worldName>");
                    }
                }
                return true;

            /*case "reload":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to reload.");
                    return true;
                }
                plugin.reloadConfig();
                player.sendMessage("§aGoatNetherPortals configuration reloaded.");
                return true;*/
            case "reload":
                sender.sendMessage("§7Reloading GoatNetherPortals...");

                // Reload config
                plugin.reloadConfig();

                // Clear and reload player data detection regions
                plugin.getPortalManager().loadAllInteriorRegionsFromFiles();

                // (Optional) Clear and reload portal map file if you use one
                plugin.getPortalManager().reloadPortalMap(); // only if applicable

                // Clear any cached or queued data (like pending player saves)

                sender.sendMessage("§aGoatNetherPortals config and portal zones reloaded.");
                return true;

            case "debug":
                player.sendMessage("§7[Stub] dumpDebugInfo would run here.");
                return true;

            case "help":
            default:
                sendHelp(player);
                return true;
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6GoatNetherPortals Commands:");
        player.sendMessage("§e/gnp setowner <linkCode> <player> §7- Sets the owner of a portal.");
        player.sendMessage("§e/gnp find <player|linkCode> §7- Finds portals by player or link code.");
        player.sendMessage("§e/gnp delete <linkCode> §7- Deletes a portal and its linked pair.");
        player.sendMessage("§e/gnp move <linkCode> §7- Moves a portal to your current location.");
        player.sendMessage("§e/gnp restore <linkCode> [worldName] §7- Restores a deleted portal from a backup.");
        player.sendMessage("§e/gnp reload §7- Reloads the plugin's configuration.");
        player.sendMessage("§e/gnp help §7- Shows this help message.");
    }
}
