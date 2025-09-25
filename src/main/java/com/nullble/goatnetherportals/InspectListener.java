package com.nullble.goatnetherportals;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Collection;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

public class InspectListener implements Listener {

    private final GoatNetherPortals plugin;
    private final Set<UUID> inspectMode = new HashSet<>();


    public InspectListener(GoatNetherPortals plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!plugin.isInspecting(uuid)) return;

        Location clickedLoc = event.getClickedBlock().getLocation();
        Collection<Entity> nearby = clickedLoc.getWorld().getNearbyEntities(clickedLoc, 1.5, 1.5, 1.5);
        Collection<ArmorStand> markers = nearby.stream()
            .filter(e -> e instanceof ArmorStand)
            .map(e -> (ArmorStand) e)
            .toList();


        for (ArmorStand stand : markers) {
            PersistentDataContainer data = stand.getPersistentDataContainer();
            String code = data.get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
            String owner = data.get(new NamespacedKey(plugin, "ownerUUID"), PersistentDataType.STRING);

            if (code != null || owner != null) {
                player.sendMessage("§bPortal Marker Found:");
                if (code != null)
                    player.sendMessage("§f• Link Code: §e" + code);
                if (owner != null)
                    player.sendMessage("§f• Owner UUID: §a" + owner);
                return;
            }
        }

        player.sendMessage("§7No portal marker metadata found near this block.");
    }
    
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.toggleInspect(event.getPlayer().getUniqueId()); // force off
    }
    
    public void toggleInspectMode(Player player) {
        UUID uuid = player.getUniqueId();
        if (inspectMode.contains(uuid)) {
            inspectMode.remove(uuid);
            player.sendMessage("§cInspect mode disabled.");
        } else {
            inspectMode.add(uuid);
            player.sendMessage("§aInspect mode enabled.");
        }
    }

}
