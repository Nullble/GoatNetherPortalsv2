package com.nullble.goatnetherportals;

import com.nullble.goatnetherportals.PortalManager.PortalFrame;
import com.nullble.goatnetherportals.PortalManager;
import org.bukkit.configuration.file.YamlConfiguration;
import com.nullble.goatnetherportals.GoatNetherPortals;
//import com.nullble.nulzone.NulZone;
//import com.nullble.nulzone.TeleportZoneManager;
import org.bukkit.block.data.Directional;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustTypes;

import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
//import com.griefdefender.api.GriefDefenderPlugin;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import io.papermc.paper.entity.TeleportFlag;


import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;


import java.util.UUID;


public class PortalListener implements Listener {

    private final GoatNetherPortals plugin;
    private final PortalManager portalManager;
    //private final Set<UUID> igniteCooldown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> igniteCooldown = new HashSet<>();
    private final Set<UUID> flintCooldown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recentlyTeleported = Collections.newSetFromMap(new WeakHashMap<>());
    // Debounce: single-scan guard per frame
    private final java.util.concurrent.ConcurrentMap<String, BukkitTask> frameScanTasks
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static String keyForFrame(Location bottomLeft, Location topRight) {
        return bottomLeft.getWorld().getName() + ":" +
                bottomLeft.getBlockX() + "," + bottomLeft.getBlockY() + "," + bottomLeft.getBlockZ() + "->" +
                topRight.getBlockX() + "," + topRight.getBlockY() + "," + topRight.getBlockZ();
    }

    public PortalListener(GoatNetherPortals plugin, PortalManager portalManager) {
        this.plugin = plugin;
        this.portalManager = portalManager;
    }

    private boolean isSensitiveMaterial(Material type) {
        return plugin.getConfig().getStringList("sensitive-blocks").contains(type.name());
    }
    
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        plugin.debugLog("onPortalCreate triggered. Reason: " + event.getReason());
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;

        Entity trigger = event.getEntity();
        if (!(trigger instanceof Player player)) return;
        plugin.debugLog("Portal created by player: " + player.getName());

        UUID uuid = player.getUniqueId();
        Location loc = event.getBlocks().get(0).getLocation();
        String worldName = loc.getWorld().getName();
        plugin.debugLog("Portal location: " + loc);

        event.setCancelled(true);
        plugin.debugLog("❌ Cancelled vanilla-generated portal at: " + event.getBlocks());

        // ⏳ Check spam
        long now = System.currentTimeMillis();
        long last = portalManager.getLastIgniteTime(uuid);
        if (now - last < 2000) {
            plugin.debugLog("⏳ Suppressed duplicate portal trigger for " + player.getName());
            return;
        }

        YamlConfiguration config = portalManager.getConfig(uuid);
        String linkCode = portalManager.findAnyLinkCodeWithOneWorld(uuid);
        if (linkCode == null) {
            plugin.debugLog("⚠ No pending portal found for " + player.getName() + ". Skipping portal link.");
            return;
        }
        plugin.debugLog("Found pending link code: " + linkCode);

        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) {
            plugin.debugLog("⚠ Link section not found for code: " + linkCode);
            return;
        }
        
        String opposite = plugin.getOppositeWorld(worldName);
        plugin.debugLog("Opposite world: " + opposite);
     // 🔄 Re-check for missing portal blocks and retry FAWE paste
        if (linkSection.contains(worldName) && linkSection.contains(opposite)) {
            plugin.debugLog("Link section contains both worlds.");
            ConfigurationSection frame = linkSection.getConfigurationSection(worldName + ".frame");
            if (frame != null) {
                String orientation = frame.getString("orientation", "Z");
                int width = frame.getInt("width", 3);
                int height = frame.getInt("height", 4);

                Location corner = new Location(loc.getWorld(),
                    frame.getDouble("corner.x"),
                    frame.getDouble("corner.y"),
                    frame.getDouble("corner.z")
                );
                plugin.debugLog("Frame data found: " + orientation + ", " + width + "x" + height + " at " + corner);
            }
        }

        ConfigurationSection frame = linkSection.getConfigurationSection(worldName + ".frame");
        if (frame == null || !frame.contains("corner.x")) {
            plugin.debugLog("⚠ Skipping paired portal generation — missing corner data.");
            return;
        }

        // 🧠 Only continue if this portal is being created in the arrival world (world-B)
        if (!linkSection.contains(worldName) || linkSection.contains(opposite)) {
            plugin.debugLog("🛑 Skipping paired portal creation — either not in world-B or already handled.");
            return;
        }

        plugin.debugLog("🛠 Detected World-B portal creation for linkCode: " + linkCode);
        plugin.debugLog("📦 Scheduling paired portal copy from " + opposite + " → " + worldName);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispenserPortalCreate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE) return;
        if (event.getEntity() != null) return; // Skip players; handled in onPortalCreate
        if (event.getBlocks().isEmpty()) return;

        Location base = event.getBlocks().get(0).getLocation();
        boolean dispenserNearby = false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block relative = base.clone().add(dx, dy, dz).getBlock();
                    if (relative.getType() == Material.DISPENSER) {
                        dispenserNearby = true;
                        break;
                    }
                }
            }
        }

        if (!dispenserNearby) {
            plugin.debugLog("❌ [DISPENSER-PORTAL] No dispenser found near: " + base + " — skipping.");
            return;
        }

        World world = base.getWorld();
        if (world == null) return;

        plugin.debugLog("🧯 [DISPENSER-PORTAL] Dispenser-triggered portal detected at: " + base);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            //PortalFrame frame = portalManager.scanFullPortalFrame(base);
        	PortalFrame frame = portalManager.scanFullPortalFrame(base, new HashSet<>(), true, true);

            if (frame == null) {
                plugin.debugLog("⚠️ [DISPENSER-PORTAL] Frame scan failed at: " + base);
                return;
            }

            UUID systemUUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

            // Check if this portal is completing a return link
            YamlConfiguration config = portalManager.getConfig(systemUUID);
            String linkCode = portalManager.findAnyLinkCodeWithOneWorld(systemUUID);

            if (linkCode != null) {
                ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
                if (linkSection != null) {
                    String worldName = base.getWorld().getName();
                    String opposite = plugin.getOppositeWorld(worldName);

                    if (linkSection.contains(opposite) && !linkSection.contains(worldName)) {
                        // 🌍 Save arrival portal location
                        ConfigurationSection worldSection = linkSection.createSection(worldName);
                        ConfigurationSection locSection = worldSection.createSection("location");
                        locSection.set("x", base.getBlockX());
                        locSection.set("y", base.getBlockY());
                        locSection.set("z", base.getBlockZ());

                        portalManager.savePlayerConfig(systemUUID, config);
                        plugin.debugLog("📌 [DISPENSER-ARRIVAL] Destination location updated for linkCode: " + linkCode);

                        // 💾 Register full portal now
                        portalManager.registerDispenserPortal(systemUUID, linkCode, frame, worldName);
                        return;
                    }
                }
            }

            // 🔁 Fallback: no partial link, generate new one
            linkCode = portalManager.generateUniqueLinkCode(systemUUID);
            portalManager.registerDispenserPortal(systemUUID, linkCode, frame, world.getName());
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.OBSIDIAN) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FLINT_AND_STEEL) {
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);

        Location obsidianLoc = clickedBlock.getLocation();

        // Trigger vanilla portal creation
        plugin.getPortalManager().tryTriggerNaturalPortal(obsidianLoc);

        // Schedule a task to check for the portal frame after ignition
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location portalBlock = portalManager.findNearbyPortalBlock(obsidianLoc, 4);
            if (portalBlock == null) {
                player.sendMessage("§cPortal did not form correctly. Try again.");
                return;
            }

            PortalFrame frame = portalManager.scanFullPortalFrame(portalBlock);
            if (frame == null) {
                player.sendMessage("§cCould not validate the portal structure.");
                return;
            }

            portalManager.createAndLinkPortals(player, portalBlock, frame);

        }, 2L); // 2-tick delay to allow the portal to form
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.FLINT_AND_STEEL) return;

        Block dispenser = event.getBlock();
        BlockFace facing = ((Directional) dispenser.getBlockData()).getFacing();
        Block targetBlock = dispenser.getRelative(facing);

        // Only allow fire placement into air, above obsidian
        if (targetBlock.getType() != Material.AIR) {
            return;
        }
        Block under = targetBlock.clone().subtract(0, 1, 0).getBlock();
        if (under.getType() != Material.OBSIDIAN) {
            return;
        }

        // Cancel default fire placement
        event.setCancelled(true);

        // Manually place fire
        Location igniteLoc = targetBlock.getLocation();
        igniteLoc.getBlock().setType(Material.FIRE);

        // Trigger portal formation (vanilla behavior)
        plugin.getPortalManager().tryTriggerNaturalPortal(igniteLoc);

        // Delay to allow the portal to fully form
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location portalBlock = portalManager.findNearbyPortalBlock(igniteLoc, 4);
            if (portalBlock == null) {
                plugin.debugLog("❌ [DISPENSER] No portal block found after ignition.");
                return;
            }

            PortalFrame frame = portalManager.scanFullPortalFrame(portalBlock);
            if (frame == null) {
                plugin.debugLog("❌ [DISPENSER] Failed to scan portal frame.");
                return;
            }

            portalManager.createAndLinkPortalsForDispenser(portalBlock, frame);

        }, 2L); // short delay for portal to fully form
    }




    private boolean isWithinBounds(Location loc, Location min, Location max) {
        return loc.getX() >= min.getX() && loc.getX() <= max.getX()
            && loc.getY() >= min.getY() && loc.getY() <= max.getY()
            && loc.getZ() >= min.getZ() && loc.getZ() <= max.getZ();
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand stand) {
            PersistentDataContainer container = stand.getPersistentDataContainer();
            if (container.has(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING)) {
                event.setCancelled(true); // prevent right-click interaction
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        // Filter for portal frame materials
        if (type != Material.OBSIDIAN && type != Material.DIAMOND_BLOCK && type != Material.NETHER_PORTAL) return;

        Location blockLoc = block.getLocation();
        java.util.List<DetectionRegion> regions = portalManager.getDetectionRegions();

        for (DetectionRegion region : regions) {
            if (region.contains(blockLoc)) {
                String linkCode = region.getLinkCode();
                Portal portal = portalManager.getPortal(linkCode, block.getWorld().getName());

                if (portal != null && portal.getFrame() != null) {
                    // Check if the broken block is part of the frame or the portal itself
                    if (type == Material.NETHER_PORTAL || portalManager.isBlockInFrame(blockLoc, portal.getFrame())) {
                        Player player = event.getPlayer();
                        player.sendMessage("§eYou have broken a linked portal. The pair has also been removed.");
                        portalManager.deletePortal(linkCode, player);
                        break;
                    }
                }
            }
        }
    }

    private String tryFindSharedCode(YamlConfiguration config, String fromWorld, String toWorld, Location arrivalLoc) {
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        String bestMatch = null;
        double bestDistance = Double.MAX_VALUE;

        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section == null) continue;

            //boolean isPending = section.getBoolean("pendingPair", false);

            ConfigurationSection fromSec = section.getConfigurationSection(fromWorld);
            ConfigurationSection toSec = section.getConfigurationSection(toWorld);

            boolean hasFrom = fromSec != null && fromSec.contains("location");
            boolean hasTo = toSec != null && toSec.contains("location");

            if (hasFrom && hasTo) {
                // Check which completed link is *closest* to the arrival portal
                ConfigurationSection locSec = toSec.getConfigurationSection("location");
                if (locSec != null) {
                    double x = locSec.getDouble("x");
                    double y = locSec.getDouble("y");
                    double z = locSec.getDouble("z");

                    if (arrivalLoc != null && arrivalLoc.getWorld().getName().equals(toWorld)) {
                        double dist = arrivalLoc.distanceSquared(new Location(arrivalLoc.getWorld(), x, y, z));
                        if (dist < bestDistance) {
                            bestDistance = dist;
                            bestMatch = code;
                        }
                    }
                }
            }
        }

        return bestMatch;
    }

    public void registerDetectionFor(String linkCode, java.util.UUID uuid, PortalFrame frame) {
        // compute interior bounds from the frame we just built/ignited
        int minX = Math.min(frame.bottomLeft.getBlockX(), frame.bottomRight.getBlockX());
        int maxX = Math.max(frame.bottomLeft.getBlockX(), frame.bottomRight.getBlockX());
        int minZ = Math.min(frame.bottomLeft.getBlockZ(), frame.bottomRight.getBlockZ());
        int maxZ = Math.max(frame.bottomLeft.getBlockZ(), frame.bottomRight.getBlockZ());
        int minY = frame.bottomLeft.getBlockY();
        int maxY = frame.topLeft.getBlockY();

        // expand 1 block perpendicular to frame so stepping in is caught reliably
        int expandX = 0, expandZ = 0;
        if ("X".equalsIgnoreCase(frame.orientation)) expandZ = 1; else expandX = 1;

        Location min = new Location(frame.bottomLeft.getWorld(), minX, minY, minZ).clone().subtract(expandX, 0, expandZ);
        Location max = new Location(frame.topRight.getWorld(),  maxX, maxY, maxZ).clone().add(expandX, 0, expandZ);


    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalUse(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Consume the link code from the entry listener
        String linkCode = portalManager.consumeRecentPortalEntry(playerUUID);
        if (linkCode == null) {
            // This is not a custom portal, so we allow vanilla behavior
            return;
        }

        // It's a custom portal, so we always cancel the vanilla event
        event.setCancelled(true);

        // We need the portal the player is standing in to know its owner
        Portal currentPortal = portalManager.getPortal(linkCode, player.getWorld().getName());
        if (currentPortal == null) {
            player.sendMessage("§cThis portal seems to be broken (cannot find its data).");
            return;
        }

        UUID ownerUUID = currentPortal.getOwner();
        Location destination = null;

        World currentWorld = player.getWorld();
        String destinationWorldName = plugin.getOppositeWorld(currentWorld.getName());
        if (destinationWorldName == null) {
            player.sendMessage("§cThis world does not have a linked opposite world.");
            return;
        }

        // Handle link-block override for non-owners, checking in the destination world.
        if (!playerUUID.equals(ownerUUID)) {
            destination = portalManager.getLinkBlockLocation(ownerUUID, destinationWorldName);
        }

        // If no link-block, or if the player is the owner, find the paired portal
        if (destination == null) {
            destination = portalManager.getLinkedPortalLocation(linkCode, currentWorld);
        }

        if(destination == null) {
             player.sendMessage("§cCould not find a destination for this portal. The linked portal may not exist.");
             return;
        }

        // Teleport the player
        player.teleport(destination);
    }
}
