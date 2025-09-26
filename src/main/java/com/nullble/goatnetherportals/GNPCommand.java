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
        PendingDeleteManager pending = plugin.getDeleteManager();

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
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
            case "setowner":
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
                        player.sendMessage("  §e- Location: §f" + loc.getWorld().getName() + " at " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
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
                            player.sendMessage("  §e- Location: §f" + loc.getWorld().getName() + " at " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                        }
                    }
                } else {
                    player.sendMessage("§cNo portal or player found for query: " + query);
                }
                return true;
            case "cleanup":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to run this.");
                    return true;
                }
                manager.cleanupGlobalPortalMap(player);
                return true;

            case "cleanupself":
            case "cleanupme":
                manager.cleanupInvalidPortals(player);
                player.sendMessage("§aYour portal data was cleaned.");
                return true;

            case "cleanupmarkers":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to run this.");
                    return true;
                }
                int total = 0;
                for (World world : Bukkit.getWorlds()) {
                    plugin.getPortalManager().cleanupBrokenMarkers(world);
                    total++;
                }
                player.sendMessage("§aChecked all worlds for orphaned markers.");
                return true;

            case "inspect":
                plugin.getInspectListener().toggleInspectMode(player);
                return true;

            case "resyncmap":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to run this.");
                    return true;
                }
                player.sendMessage("§7[Stub] syncPortalMapToDisk would run here.");
                return true;

            case "validate":
                player.sendMessage("§7[Stub] validateAllPortalData would run here.");
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

            case "unlink":
                player.sendMessage("§7[Stub] unlinkNearestPortal would run here.");
                return true;

            case "restore":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /gnp restore <linkCode>");
                    return true;
                }
                manager.restorePortal(args[1], player);
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
                plugin.getPortalManager().clearQueuedPlayerConfigs(); // optional helper if you've made one

                sender.sendMessage("§aGoatNetherPortals config and portal zones reloaded.");
                return true;

            case "debug":
                player.sendMessage("§7[Stub] dumpDebugInfo would run here.");
                return true;

            case "info":
                showPlayerPortalInfo(player);
                return true;

            case "help":
            default:
                sendHelp(player);
                return true;
                
            case "togglemarkers":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this.");
                    return true;
                }
                boolean visible = plugin.toggleDebugMarkers();
                plugin.updateAllPortalMarkersDebugVisibility();
                player.sendMessage("§7Debug marker visibility is now: " + (visible ? "§aON" : "§cOFF"));
                return true;
                
            case "debugzones":
                if (!player.hasPermission("goatportals.admin")) {
                    player.sendMessage("§cYou do not have permission to use this.");
                    return true;
                }
                plugin.getPortalManager().previewEntryZones(player);
                player.sendMessage("§7Now previewing all §eportal entry detection zones§7 using particles.");
                return true;

            case "echo":
                if (!(sender instanceof Player playerEcho)) {
                    sender.sendMessage("§cOnly players can use this.");
                    return true;
                }

                int found = 0;
                for (Entity e : playerEcho.getWorld().getEntitiesByClass(ArmorStand.class)) {
                    if (!e.getScoreboardTags().contains("gnp_marker")) continue;

                    Location loc = e.getLocation();
                    String linkCode = "unknown";
                    if (e.getPersistentDataContainer().has(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING)) {
                        linkCode = e.getPersistentDataContainer().get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
                    }

                    String coords = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
                    TextComponent msg = new TextComponent("§e[Teleport to Marker] §7(" + coords + ") §f[" + linkCode + "]");
                    msg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/tp " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()));
                    playerEcho.spigot().sendMessage(msg);
                    found++;
                }

                if (found == 0) {
                    playerEcho.sendMessage("§7No markers found in this world.");
                }
                return true;

            case "checkpair":
                if (args.length != 2) {
                    player.sendMessage("§cUsage: /gnp checkpair <linkCode>");
                    return true;
                }

                String linkCode = args[1];
                PortalManager pm = plugin.getPortalManager();
                UUID owner = pm.getPlayerUUIDFromLinkCode(linkCode);

                if (owner == null) {
                    player.sendMessage("§c❌ Could not find player file containing linkCode: §f" + linkCode);
                    return true;
                }

                YamlConfiguration config = pm.getOrCreatePlayerConfig(owner);
                ConfigurationSection link = config.getConfigurationSection("links." + linkCode);
                if (link == null) {
                    player.sendMessage("§c❌ LinkCode found in file but no data section present.");
                    return true;
                }

                player.sendMessage("§e🔗 LinkCode: §f" + linkCode);
                player.sendMessage("§7PendingPair: " + (link.getBoolean("pendingPair", true) ? "§6true" : "§afalse"));

                for (String worldKey : link.getKeys(false)) {
                    if (worldKey.equalsIgnoreCase("pendingPair")) continue;
                    ConfigurationSection worldSec = link.getConfigurationSection(worldKey + ".location");
                    if (worldSec != null) {
                        double x = worldSec.getDouble("x");
                        double y = worldSec.getDouble("y");
                        double z = worldSec.getDouble("z");
                        player.sendMessage("§8↳ §7" + worldKey + ": §f(" + x + ", " + y + ", " + z + ")");
                    }
                }

                return true;

        }
    }

    private void sendConfirmPrompt(Player player, String target) {
        TextComponent comp = new TextComponent("§eClick to confirm deletion of all portals for " + target);
        comp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gnp confirmdelete"));
        player.spigot().sendMessage(comp);
    }

    private void showPlayerPortalInfo(Player player) {
        UUID uuid = player.getUniqueId();
        YamlConfiguration config = plugin.getPortalManager().getConfig(uuid);

        if (!config.contains("links")) {
            player.sendMessage("§7You have no saved portal links.");
            return;
        }

        ConfigurationSection links = config.getConfigurationSection("links");
        player.sendMessage("§6Linked portals:");
        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section == null) continue;
            player.sendMessage("§e- Code: §f" + code + " §7(" + section.getKeys(false).size() + " worlds)");
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6GoatNetherPortals Commands:");
        player.sendMessage("§e/gnp setowner <linkCode> <player> §7- Sets the owner of a portal.");
        player.sendMessage("§e/gnp find <player|linkCode> §7- Finds portals by player or link code.");
        player.sendMessage("§e/gnp delete <linkCode> §7- Deletes a portal and its linked pair.");
        player.sendMessage("§e/gnp cleanup §7- Clean global portal map (admin)");
        player.sendMessage("§e/gnp cleanupself §7- Clean your own portal data");
        player.sendMessage("§e/gnp cleanupmarkers §7- Remove orphaned portal markers (admin)");
        player.sendMessage("§e/gnp info §7- View your portal link summary");
        player.sendMessage("§e/gnp inspect §7- Toggle portal inspection mode");
        player.sendMessage("§e/gnp resyncmap §7- Force save all portal links to disk");
        player.sendMessage("§e/gnp validate §7- Validate and repair portal links");
        player.sendMessage("§e/gnp move <linkCode> §7- Moves a portal to your current location.");
        player.sendMessage("§e/gnp restore <linkCode> §7- Restores a deleted portal from a backup.");
        player.sendMessage("§e/gnp unlink §7- Unlink nearest portal");
        player.sendMessage("§e/gnp reload §7- Reload config");
        player.sendMessage("§e/gnp debug §7- Dump debug info");
    }
}
