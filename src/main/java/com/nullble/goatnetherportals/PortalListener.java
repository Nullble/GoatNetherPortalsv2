package com.nullble.goatnetherportals;

import com.nullble.goatnetherportals.PortalFrame;
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

            portalManager.createPortalPair(player.getUniqueId(), portalBlock, frame);

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
        Block under = targetBlock.getLocation().clone().subtract(0, 1, 0).getBlock();
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

            portalManager.createPortalPair(PortalManager.SERVER_UUID, portalBlock, frame);

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
                        portalManager.deletePortal(linkCode, event.getPlayer());
                        break;
                    }
                }
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalUse(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Find the link code by scanning the area around the player
        String linkCode = portalManager.findLinkCodeAt(player.getLocation());
        if (linkCode == null) {
            return; // Not a custom portal
        }

        // It's a custom portal, so we always cancel the vanilla event
        event.setCancelled(true);

        // Cooldown to prevent instant re-teleport
        if (recentlyTeleported.contains(playerUUID)) {
            return;
        }
        recentlyTeleported.add(playerUUID);
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentlyTeleported.remove(playerUUID), 40L); // 2 second cooldown


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

        // Handle link-block override for non-owners
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

        player.teleport(destination);
    }
}
