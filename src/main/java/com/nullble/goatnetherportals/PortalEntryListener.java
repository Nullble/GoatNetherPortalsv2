package com.nullble.goatnetherportals;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PortalEntryListener implements Listener {

    private final GoatNetherPortals plugin;

    public PortalEntryListener(GoatNetherPortals plugin) {
        this.plugin = plugin;
    }

    /*@EventHandler
    public void onPlayerEnterPortalRegion(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom() == null) return;
        if (event.getFrom().getBlock().equals(event.getTo().getBlock())) return;

        Player player = event.getPlayer();
        Location toBlock = event.getTo().getBlock().getLocation();

        String linkCode = plugin.getPortalManager().getLinkFromRegion(toBlock);
        if (linkCode == null) return;

        UUID uuid = player.getUniqueId();
        plugin.getPortalManager().storePendingLink(uuid, linkCode);

        // ✅ DEBUG MESSAGE
        player.sendMessage("§b[DEBUG] You entered a portal pre-check zone. LinkCode: §e" + linkCode);

        // ⏳ Schedule auto-clear after 5 seconds
        new BukkitRunnable() {
            public void run() {
                plugin.getPortalManager().clearPendingLink(uuid);
                // Optionally, notify when it expires:
                // player.sendMessage("§7[DEBUG] Your stored linkCode has expired.");
            }
        }.runTaskLater(plugin, 20 * 5); // 5 seconds
    }*/

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || event.getFrom().getBlock().equals(to.getBlock())) {
            return;
        }

        Player player = event.getPlayer();
        List<DetectionRegion> regions = plugin.getPortalManager().getDetectionRegions();

        for (DetectionRegion region : regions) {
            if (region.contains(to)) {
                plugin.getPortalManager().setRecentPortalEntry(player.getUniqueId(), region.getLinkCode());
                return;
            }
        }
    }


    private String formatLoc(Location loc) {
        return loc.getWorld().getName() + " " +
               loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

}
