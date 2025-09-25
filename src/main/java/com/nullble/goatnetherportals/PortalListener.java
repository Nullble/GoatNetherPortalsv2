package com.nullble.goatnetherportals;

import com.nullble.goatnetherportals.PortalManager.PortalFrame;
import com.nullble.goatnetherportals.PortalManager;
import org.bukkit.configuration.file.YamlConfiguration;
import com.nullble.goatnetherportals.GoatNetherPortals;
import com.nullble.nulzone.NulZone;
import com.nullble.nulzone.TeleportZoneManager;
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
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;

        Entity trigger = event.getEntity();
        if (!(trigger instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        Location loc = event.getBlocks().get(0).getLocation();
        String worldName = loc.getWorld().getName();

        event.setCancelled(true);
        //plugin.getLogger().info("❌ Cancelled vanilla-generated portal at: " + event.getBlocks());

        // ⏳ Check spam
        long now = System.currentTimeMillis();
        long last = portalManager.getLastIgniteTime(uuid);
        if (now - last < 2000) {
            //plugin.getLogger().info("⏳ Suppressed duplicate portal trigger for " + player.getName());
            return;
        }

        YamlConfiguration config = portalManager.getConfig(uuid);
        String linkCode = portalManager.findAnyLinkCodeWithOneWorld(uuid);
        if (linkCode == null) {
            //plugin.getLogger().warning("⚠ No pending portal found for " + player.getName() + ". Skipping portal link.");
            return;
        }

        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) {
            //plugin.getLogger().warning("⚠ Link section not found for code: " + linkCode);
            return;
        }
        
        String opposite = plugin.getOppositeWorld(worldName);
     // 🔄 Re-check for missing portal blocks and retry FAWE paste
        if (linkSection.contains(worldName) && linkSection.contains(opposite)) {
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

                /*PortalFrame scanned = plugin.getPortalManager().scanFullPortalFrame(corner);
                if (scanned != null && plugin.getPortalManager().portalBlocksMissing(scanned.bottomLeft, scanned.width, scanned.height, scanned.orientation)) {
                    plugin.getLogger().warning("⚠ Portal blocks missing — retrying FRAME paste for: " + linkCode);
                    plugin.getPortalManager().forceGeneratePairedPortal(linkCode, opposite, uuid, scanned);
                }*/

            }
        }

     // 🧱 Prevent double-generation if already handled
        /*if (!linkSection.contains(worldName + ".frame.corner")) {
            //plugin.getLogger().warning("⚠ Skipping paired portal generation — missing corner data.");
            return;
        }*/
        ConfigurationSection frame = linkSection.getConfigurationSection(worldName + ".frame");
        if (frame == null || !frame.contains("corner.x")) {
            //plugin.getLogger().warning("⚠ Skipping paired portal generation — missing corner data.");
            return;
        }

        // 🧠 Only continue if this portal is being created in the arrival world (world-B)
        if (!linkSection.contains(worldName) || linkSection.contains(opposite)) {
            //plugin.getLogger().info("🛑 Skipping paired portal creation — either not in world-B or already handled.");
            return;
        }

        //plugin.getLogger().info("🛠 Detected World-B portal creation for linkCode: " + linkCode);
        //plugin.getLogger().info("📦 Scheduling paired portal copy from " + opposite + " → " + worldName);

        /*plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            portalManager.forceGeneratePairedPortal(
                linkCode,
                opposite, // ✅ forceGenerate uses originWorld, not arrival
                uuid,
                loc,
                3, 4, "Z"
            );
        }, 20L);*/
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
            plugin.getLogger().info("❌ [DISPENSER-PORTAL] No dispenser found near: " + base + " — skipping.");
            return;
        }

        World world = base.getWorld();
        if (world == null) return;

        plugin.getLogger().info("🧯 [DISPENSER-PORTAL] Dispenser-triggered portal detected at: " + base);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            //PortalFrame frame = portalManager.scanFullPortalFrame(base);
        	PortalFrame frame = portalManager.scanFullPortalFrame(base, new HashSet<>(), true, true);

            if (frame == null) {
                plugin.getLogger().warning("⚠️ [DISPENSER-PORTAL] Frame scan failed at: " + base);
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
                        plugin.getLogger().info("📌 [DISPENSER-ARRIVAL] Destination location updated for linkCode: " + linkCode);

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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Block block = event.getClickedBlock();

        if (block == null || block.getType() != Material.OBSIDIAN) return;
        if (event.getItem() == null || event.getItem().getType() != Material.FLINT_AND_STEEL) return;

        // Debounce: prevent dual-hand event triggering
        if (!flintCooldown.add(uuid)) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> flintCooldown.remove(uuid), 2L);

        event.setCancelled(true);
        Location obsidianLoc = block.getLocation();

        // Fire, then wait 1 tick for the game to form the portal
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getPortalManager().tryTriggerNaturalPortal(obsidianLoc);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location portalBlock = plugin.getPortalManager().findNearbyPortalBlock(obsidianLoc, 4);
                if (portalBlock == null) return;

                plugin.getLogger().info("🌀 [SCAN:INTERACT] Scanning portal frame after player lights it at: " + portalBlock);

                Set<Location> visited = new HashSet<>();
                PortalFrame frame = plugin.getPortalManager().scanFullPortalFrame(portalBlock, visited, true, false);
                if (frame == null) {
                    player.sendMessage("§cFailed to scan portal frame.");
                    return;
                }
                plugin.getLogger().info("✅ Valid portal formed at: " + portalBlock);

                // Single-scan guard per frame (prevents double Portal-B)
                Location bottomLeft = frame.bottomLeft;
                Location topRight   = frame.topRight;
                String frameKey     = keyForFrame(bottomLeft, topRight);

                BukkitTask guard = Bukkit.getScheduler().runTask(plugin, () -> {});
                BukkitTask existing = frameScanTasks.putIfAbsent(frameKey, guard);
                if (existing != null) {
                    guard.cancel();
                    plugin.getLogger().info("⏭️ [DEBOUNCE] Frame already being processed: " + frameKey);
                    return;
                }
                try {
                    // Register A-side; PortalManager handles detection + paired build
                    plugin.getPortalManager().tryAutoRegisterPortal(player, portalBlock, frame);
                } finally {
                    frameScanTasks.remove(frameKey, guard);
                    guard.cancel();
                }
            }, 1L);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.FLINT_AND_STEEL) return;

        Block dispenser = event.getBlock();
        BlockFace facing = ((Directional) dispenser.getBlockData()).getFacing();
        Block targetBlock = dispenser.getRelative(facing);
        Location igniteLoc = targetBlock.getLocation();

        plugin.getLogger().info("🚀 BlockDispenseEvent fired!");
        plugin.getLogger().info("🧱 Target block from dispenser: " + targetBlock.getType());

        // Only allow fire placement into air, above obsidian
        if (targetBlock.getType() != Material.AIR) {
            plugin.getLogger().warning("⚠ Target is not AIR — cannot place fire.");
            return;
        }

        Block under = igniteLoc.clone().subtract(0, 1, 0).getBlock();
        if (under.getType() != Material.OBSIDIAN) {
            plugin.getLogger().warning("⚠ Block under ignition is not obsidian — ignition denied.");
            return;
        }

        // Cancel default fire placement
        event.setCancelled(true);

        // Manually place fire
        igniteLoc.getBlock().setType(Material.FIRE);

        // Trigger portal formation (vanilla behavior)
        plugin.getPortalManager().tryTriggerNaturalPortal(igniteLoc);

        // Delay to allow the portal to fully form
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location portalBlock = plugin.getPortalManager().findNearbyPortalBlock(igniteLoc, 4);
            if (portalBlock == null) {
                plugin.getLogger().warning("❌ No portal block found after ignition.");
                return;
            }

            plugin.getLogger().info("🌀 [DISPENSE] Scanning portal frame after dispenser ignition at: " + portalBlock);

            PortalFrame frame = plugin.getPortalManager().scanFullPortalFrame(portalBlock);
            if (frame == null) {
                plugin.getLogger().warning("❌ Failed to scan portal frame.");
                return;
            }

            plugin.getLogger().info("✅ Valid portal formed at: " + portalBlock);

            UUID fakeUUID = PortalManager.SERVER_UUID;
            String worldName = portalBlock.getWorld().getName();

            // 🔍 Attempt to resolve existing linkCode from markers or frame data
            String linkCode = plugin.getPortalManager().resolveLinkCodeByMarkerScan(frame);
            if (linkCode != null) {
                plugin.getLogger().info("🔗 [DISPENSER] Found existing linkCode via marker scan: " + linkCode);
            } else {
                linkCode = plugin.getPortalManager().generateUniqueLinkCode(fakeUUID);
                plugin.getLogger().info("🆕 [DISPENSER] Generated new linkCode: " + linkCode);
            }

            // 💾 Register the portal and save to config
            plugin.getPortalManager().registerDispenserPortal(fakeUUID, linkCode, frame, worldName);

            // 🔁 Attempt to generate or finalize the opposite portal
            plugin.getPortalManager().forceGeneratePairedPortal(linkCode, worldName, fakeUUID, frame);

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

        // ✅ Filter only for Obsidian and Nether Portal blocks
        if (type != Material.OBSIDIAN && type != Material.NETHER_PORTAL) return;

        // 🔍 Look for nearby ArmorStands that act as markers
        Collection<Entity> nearbyEntities = block.getWorld().getNearbyEntities(block.getLocation(), 3, 3, 3);
        for (Entity entity : nearbyEntities) {
            if (entity instanceof ArmorStand stand) {
                PersistentDataContainer data = stand.getPersistentDataContainer();
                NamespacedKey key = new NamespacedKey(plugin, "linkCode");
                if (data.has(key, PersistentDataType.STRING)) {
                    //plugin.getLogger().info("⚠️ Portal marker found near broken portal — removing...");
                    stand.remove();
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
    public void onPortalUse(org.bukkit.event.player.PlayerPortalEvent event) {
        final Player player = event.getPlayer();
        final Location from = event.getFrom();

        // Find the actual portal block near the event origin (fallback to 'from' if none)
        Location fromBlock = plugin.getPortalManager().findNearbyPortalBlock(from, 4);
        if (fromBlock == null) {
            fromBlock = from;
        }

        // Use the existing helper you already have in this file
        resolveLinkOrRetryOnce(player, event, this.portalManager, fromBlock);
    }

    private void resolveLinkOrRetryOnce(Player player,
                                        org.bukkit.event.player.PlayerPortalEvent event,
                                        com.nullble.goatnetherportals.PortalManager portalManager,
                                        org.bukkit.Location fromBlock) {
        // 1) Region index first
        String link = portalManager.findLinkCodeAt(fromBlock);
        if (link == null) {
            // 2) Marker scan fallback
            java.util.Set<org.bukkit.Location> v = new java.util.HashSet<>();
            com.nullble.goatnetherportals.PortalManager.PortalFrame pf =
                    portalManager.scanFullPortalFrame(fromBlock, v, true, false);
            if (pf != null) link = portalManager.resolveLinkCodeByMarkerScan(pf);
        }

        if (link == null) {
            // 3) Give the registry 2 ticks to catch up, then try exactly once more
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                    com.nullble.goatnetherportals.GoatNetherPortals.getInstance(),
                    () -> {
                        String again = portalManager.findLinkCodeAt(fromBlock);
                        if (again == null) {
                            java.util.Set<org.bukkit.Location> v2 = new java.util.HashSet<>();
                            var pf2 = portalManager.scanFullPortalFrame(fromBlock, v2, true, false);
                            if (pf2 != null) again = portalManager.resolveLinkCodeByMarkerScan(pf2);
                        }
                        if (again == null) {
                            event.setCancelled(true);
                            player.sendMessage("§cPortal isn’t ready yet. Step out and re-enter.");
                        } else {
                            portalManager.setLastDetectedLink(player.getUniqueId(), again);
                            player.performCommand("gnp validate"); // optional: poke any validation you use
                        }
                    },
                    2L
            );
            return;
        }

        portalManager.setLastDetectedLink(player.getUniqueId(), link);
    }

    
}
