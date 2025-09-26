package com.nullble.goatnetherportals;
//0.0.1
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
//import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.lib.flowpowered.math.vector.Vector3i;
import com.jeff_media.customblockdata.CustomBlockData;
//import com.nullble.nulzone.NulZone;
import com.nullble.goatnetherportals.DetectionRegion;
import com.nullble.goatnetherportals.PortalFrame;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockTypes;

//import net.minecraft.core.BlockPos;
//import net.minecraft.core.Direction;
//import net.minecraft.world.level.Level;
//import net.minecraft.world.level.block.Blocks;
//import net.minecraft.world.level.portal.PortalShape;
import org.jetbrains.annotations.Nullable;

public class PortalManager {

    public enum Axis {
        X, Z
    }

    private final GoatNetherPortals plugin;
    private final File dataFolder;
    private NamespacedKey linkCodeKey;
    private NamespacedKey ownerKey;
    private final File globalPortalMapFile;
    private YamlConfiguration globalPortalMap;
    public final Map<UUID, Long> recentIgniteTimestamps = new HashMap<>();
    private final Set<UUID> igniteCooldown = ConcurrentHashMap.newKeySet();
    private final Map<String, Portal> portalCache = new ConcurrentHashMap<>();
    private final Map<String, Long> recentScanLock = new HashMap<>();
    private final java.util.Set<String> spawnedMarkers =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final Map<String, Long> recentPortalCooldowns = new HashMap<>();
    private final Map<Location, String> portalEntryZones = new HashMap<>();
    private final Set<String> recentScanLocations = new HashSet<>();
    private final Map<UUID, String> playerPendingLinks = new HashMap<>();
    private final List<DetectionRegion> detectionRegions = new ArrayList<>();
    private final Queue<MarkerTask> markerQueue = new LinkedList<>();
    private boolean isMarkerSpawning = false;
    public static final UUID SERVER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Map<UUID, String> recentPortalEntries = new HashMap<>();

    public void savePortal(Portal portal) {
        if (portal == null) {
            return;
        }
        File playerFile = new File(dataFolder, portal.getOwner().toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

        String linkCodePath = "portals." + portal.getLinkCode();
        config.set(linkCodePath + ".owner", portal.getOwner().toString());

        String worldPath = linkCodePath + ".worlds." + portal.getWorld().getName();
        config.set(worldPath + ".location.x", portal.getLocation().getX());
        config.set(worldPath + ".location.y", portal.getLocation().getY());
        config.set(worldPath + ".location.z", portal.getLocation().getZ());
        config.set(worldPath + ".diamondOverride", portal.hasDiamondOverride());

        if (portal.getFrame() != null) {
            config.set(worldPath + ".frame.orientation", portal.getFrame().orientation);
            config.set(worldPath + ".frame.width", portal.getFrame().width);
            config.set(worldPath + ".frame.height", portal.getFrame().height);
            config.set(worldPath + ".frame.bottomLeft", formatCoord(portal.getFrame().bottomLeft));
        }

        try {
            config.save(playerFile);
            portalCache.put(portal.getLinkCode() + ":" + portal.getWorld().getName(), portal);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save portal " + portal.getLinkCode() + " for player " + portal.getOwner());
            e.printStackTrace();
        }
    }

    public void restorePortal(String linkCode, String worldName, CommandSender sender) {
        File backupFile = new File(new File(plugin.getDataFolder(), "backups"), linkCode + "-" + worldName + ".yml");
        if (!backupFile.exists()) {
            sender.sendMessage("§cBackup not found for portal: " + linkCode + " in world " + worldName);
            return;
        }

        YamlConfiguration backupConfig = YamlConfiguration.loadConfiguration(backupFile);
        String basePath = "portal";

        UUID ownerUUID = UUID.fromString(backupConfig.getString(basePath + ".owner"));
        World world = Bukkit.getWorld(backupConfig.getString(basePath + ".world"));
        if (world == null) {
            sender.sendMessage("§cCannot restore portal. World '" + backupConfig.getString(basePath + ".world") + "' is not loaded.");
            return;
        }

        Location location = new Location(
            world,
            backupConfig.getDouble(basePath + ".location.x"),
            backupConfig.getDouble(basePath + ".location.y"),
            backupConfig.getDouble(basePath + ".location.z")
        );

        int width = backupConfig.getInt(basePath + ".frame.width");
        int height = backupConfig.getInt(basePath + ".frame.height");
        String orientation = backupConfig.getString(basePath + ".frame.orientation");
        Location corner = parseCoord(backupConfig.getString(basePath + ".frame.bottomLeft"));
        corner.setWorld(world);

        buildPortalFrame(corner, width, height, orientation);

        PortalFrame frame = scanFullPortalFrame(location);
        if (frame == null) {
            sender.sendMessage("§eWarning: Could not validate the restored portal frame. The data has been restored, but the frame may need to be lit manually.");
        }

        Portal portal = new Portal(linkCode, ownerUUID, location, frame);
        portal.setDiamondOverride(backupConfig.getBoolean(basePath + ".diamondOverride"));

        savePortal(portal);

        if (frame != null) {
            spawnPortalMarker(frame, linkCode, ownerUUID);
        }

        sender.sendMessage("§aPortal " + linkCode + " has been restored.");

        backupFile.delete();
    }

    private void buildPortalFrame(Location corner, int width, int height, String orientation) {
        World world = corner.getWorld();
        int dx = orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = orientation.equalsIgnoreCase("Z") ? 0 : 1;

        // Build frame
        for (int i = 0; i < width; i++) {
            corner.clone().add(i * dx, 0, i * dz).getBlock().setType(Material.OBSIDIAN);
            corner.clone().add(i * dx, height - 1, i * dz).getBlock().setType(Material.OBSIDIAN);
        }
        for (int i = 1; i < height - 1; i++) {
            corner.clone().add(0, i, 0).getBlock().setType(Material.OBSIDIAN);
            corner.clone().add((width - 1) * dx, i, (width - 1) * dz).getBlock().setType(Material.OBSIDIAN);
        }

        // Ignite
        Location igniteLoc = corner.clone().add(dx, 1, dz);
        tryTriggerNaturalPortal(igniteLoc);
    }

    public void movePortal(String linkCode, Location newLocation, CommandSender sender) {
        World world = newLocation.getWorld();
        if (world == null) {
            sender.sendMessage("§cCannot move portal, world could not be determined.");
            return;
        }

        Portal portal = getPortal(linkCode, world.getName());
        if (portal == null) {
            sender.sendMessage("§cPortal with link code '" + linkCode + "' not found in this world.");
            return;
        }

        if (portal.getFrame() == null) {
            sender.sendMessage("§cCannot move portal as its frame data is missing.");
            return;
        }

        // Clear the old portal frame and marker
        clearObsidianFrame(portal.getFrame());
        removeMarker(linkCode, world);

        // Assume player location is the new bottom-left corner
        Location newCorner = newLocation.getBlock().getLocation();
        portal.setLocation(newCorner);

        // Re-build the frame at the new location
        int frameWidth = portal.getFrame().width;
        int frameHeight = portal.getFrame().height;
        String orientation = portal.getFrame().orientation;

        buildPortalFrame(newCorner, frameWidth, frameHeight, orientation);

        // Try to scan the new frame
        Location portalBlockLoc = newCorner.clone().add(
            orientation.equalsIgnoreCase("X") ? 1 : 0, 1,
            orientation.equalsIgnoreCase("Z") ? 1 : 0
        );

        PortalFrame newFrame = scanFullPortalFrame(portalBlockLoc);
        if (newFrame == null) {
            sender.sendMessage("§eWarning: Could not validate the new portal frame. It may need to be lit manually.");
        }
        portal.setFrame(newFrame);

        savePortal(portal);

        if (newFrame != null) {
            spawnPortalMarker(newFrame, linkCode, portal.getOwner());
        }

        sender.sendMessage("§aPortal " + linkCode + " has been moved to your location in " + world.getName() + ".");
    }

    private void clearObsidianFrame(PortalFrame frame) {
        if (frame == null || frame.bottomLeft == null || frame.bottomLeft.getWorld() == null) return;
        World world = frame.bottomLeft.getWorld();
        int dx = frame.orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = frame.orientation.equalsIgnoreCase("Z") ? 1 : 0;

        // Clear the frame
        for (int i = 0; i < frame.width; i++) {
            frame.bottomLeft.clone().add(i * dx, 0, i * dz).getBlock().setType(Material.AIR);
            frame.bottomLeft.clone().add(i * dx, frame.height - 1, i * dz).getBlock().setType(Material.AIR);
        }
        for (int i = 1; i < frame.height - 1; i++) {
            frame.bottomLeft.clone().add(0, i, 0).getBlock().setType(Material.AIR);
            frame.bottomLeft.clone().add((frame.width - 1) * dx, i, (frame.width - 1) * dz).getBlock().setType(Material.AIR);
        }

        // Also clear the portal blocks inside
        clearPortalBlocks(frame);
    }

    public boolean isBlockInFrame(Location blockLoc, PortalFrame frame) {
        Location bottomLeft = frame.bottomLeft;
        int width = frame.width;
        int height = frame.height;
        String orientation = frame.orientation;
        int dx = orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = orientation.equalsIgnoreCase("Z") ? 1 : 0;

        // Check bottom and top rows
        for (int i = 0; i < width; i++) {
            Location bottomBlock = bottomLeft.clone().add(i * dx, 0, i * dz);
            if (blockLoc.getBlockX() == bottomBlock.getBlockX() && blockLoc.getBlockY() == bottomBlock.getBlockY() && blockLoc.getBlockZ() == bottomBlock.getBlockZ()) return true;

            Location topBlock = bottomLeft.clone().add(i * dx, height - 1, i * dz);
            if (blockLoc.getBlockX() == topBlock.getBlockX() && blockLoc.getBlockY() == topBlock.getBlockY() && blockLoc.getBlockZ() == topBlock.getBlockZ()) return true;
        }

        // Check left and right columns (excluding corners which are covered above)
        for (int i = 1; i < height - 1; i++) {
            Location leftBlock = bottomLeft.clone().add(0, i, 0);
            if (blockLoc.getBlockX() == leftBlock.getBlockX() && blockLoc.getBlockY() == leftBlock.getBlockY() && blockLoc.getBlockZ() == leftBlock.getBlockZ()) return true;

            Location rightBlock = bottomLeft.clone().add((width - 1) * dx, i, (width - 1) * dz);
            if (blockLoc.getBlockX() == rightBlock.getBlockX() && blockLoc.getBlockY() == rightBlock.getBlockY() && blockLoc.getBlockZ() == rightBlock.getBlockZ()) return true;
        }

        return false;
    }

    public Portal loadPortal(String linkCode, String worldName) {
        String cacheKey = linkCode + ":" + worldName;
        if (portalCache.containsKey(cacheKey)) {
            return portalCache.get(cacheKey);
        }

        UUID ownerUUID = getPortalOwner(linkCode);
        if (ownerUUID == null) {
            return null;
        }

        File playerFile = new File(dataFolder, ownerUUID.toString() + ".yml");
        if (!playerFile.exists()) {
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        String worldPath = "portals." + linkCode + ".worlds." + worldName;

        if (!config.contains(worldPath)) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        Location location = new Location(
                world,
                config.getDouble(worldPath + ".location.x"),
                config.getDouble(worldPath + ".location.y"),
                config.getDouble(worldPath + ".location.z")
        );

        PortalFrame frame = null;
        if (config.contains(worldPath + ".frame")) {
            frame = scanFullPortalFrame(location);
        }

        Portal portal = new Portal(linkCode, ownerUUID, location, frame);
        portal.setDiamondOverride(config.getBoolean(worldPath + ".diamondOverride"));

        portalCache.put(cacheKey, portal);
        return portal;
    }

    public Portal getPortal(String linkCode, String worldName) {
        return loadPortal(linkCode, worldName);
    }

    public Location getLinkedPortalLocation(String linkCode, World currentWorld) {
        UUID ownerUUID = getPortalOwner(linkCode);
        if (ownerUUID == null) {
            return null;
        }

        File playerFile = new File(dataFolder, ownerUUID.toString() + ".yml");
        if (!playerFile.exists()) {
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        String linkCodeWorldsPath = "portals." + linkCode + ".worlds";
        if (!config.contains(linkCodeWorldsPath)) {
            return null;
        }

        ConfigurationSection worldsSection = config.getConfigurationSection(linkCodeWorldsPath);
        for (String worldName : worldsSection.getKeys(false)) {
            if (!worldName.equals(currentWorld.getName())) {
                String worldPath = linkCodeWorldsPath + "." + worldName;
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Portal linkedPortal = loadPortal(linkCode, worldName);
                    if (linkedPortal != null && linkedPortal.getFrame() != null) {
                        return getSafeTeleportLocation(linkedPortal.getFrame().bottomLeft, linkedPortal.getFrame().width, linkedPortal.getFrame().height, linkedPortal.getFrame().orientation);
                    }
                    // Fallback
                    return new Location(
                        world,
                        config.getDouble(worldPath + ".location.x") + 0.5,
                        config.getDouble(worldPath + ".location.y") + 1,
                        config.getDouble(worldPath + ".location.z") + 0.5
                    );
                }
            }
        }
        return null;
    }

    public UUID getUUIDFromName(String playerName) {
        OfflinePlayer offline = null;
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(playerName)) {
                offline = p;
                break;
            }
        }
        if (offline != null) {
            return offline.getUniqueId();
        } else {
            // fallback for offline player who never joined
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes());
        }
    }

    private void debugScan(String label, Location loc) {
        plugin.debugLog("🌀 [SCAN] " + label + " @ (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
    }
    public YamlConfiguration getOrCreatePlayerConfig(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void saveConfig(UUID uuid, YamlConfiguration config) {
        try {
            config.save(new File(dataFolder, uuid.toString() + ".yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
 // ⛓️ Helper struct

    public PortalManager(GoatNetherPortals plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        this.linkCodeKey = new NamespacedKey(plugin, "linkCode");
        this.ownerKey = new NamespacedKey(plugin, "ownerUUID");

        this.globalPortalMapFile = new File(plugin.getDataFolder(), "portalMap.yml");
        if (!globalPortalMapFile.exists()) {
            try { globalPortalMapFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        this.globalPortalMap = YamlConfiguration.loadConfiguration(globalPortalMapFile);
        loadAllInteriorRegionsFromFiles();

    }









    private boolean isCooldownActive(String key, long cooldownMillis) {
        long now = System.currentTimeMillis();
        long last = recentPortalCooldowns.getOrDefault(key, 0L);
        if (now - last < cooldownMillis) return true;
        recentPortalCooldowns.put(key, now);
        return false;
    }

    private String formatCoord(Location loc) {
        if (loc == null) {
            plugin.debugLog("⚠️ formatCoord() called with null location");
            return "0, 0, 0"; // Return default value
        }
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
 // Overload for when x/y/z or corner string are directly inside the section
    public Location loadUniversalLocation(ConfigurationSection section, World world) {
        if (section == null || world == null) return null;

        if (section.contains("x") && section.contains("y") && section.contains("z")) {
            return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
        }

        if (section.contains("corner")) {
            String coord = section.getString("corner");
            if (coord != null && coord.contains(",")) {
                String[] parts = coord.split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        return new Location(world, x, y, z);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        plugin.debugLog("⚠️ loadUniversalLocation fallback at section: " + section.getCurrentPath());
        return new Location(world, 0, 0, 0);
    }


 // Convert "x, y, z" string → Location (world must be set separately)
    public Location parseCoord(String coordString) {
        if (coordString == null || coordString.trim().isEmpty()) return null;

        String[] parts = coordString.split(",");
        if (parts.length != 3) return null;

        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new Location(null, x, y, z); // Caller must set world
        } catch (NumberFormatException e) {
            plugin.debugLog("⚠️ Failed to parse coord string: " + coordString);
            return null;
        }
    }



    private boolean isInsideNulZone(Location loc) {
        return false;
    }

    public Location findSafePortalSpotInClaim(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        Vector3i pos = new Vector3i(player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());

        Claim claim = GriefDefender.getCore()
            .getClaimManager(world.getUID())
            .getClaimAt(pos);

        if (claim == null || !claim.getOwnerUniqueId().equals(player.getUniqueId())) {
            return null;
        }

        int minX = claim.getLesserBoundaryCorner().getX();
        int maxX = claim.getGreaterBoundaryCorner().getX();
        int minZ = claim.getLesserBoundaryCorner().getZ();
        int maxZ = claim.getGreaterBoundaryCorner().getZ();

        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;

        int maxRadius = Math.max(maxX - minX, maxZ - minZ) / 2;

        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;

                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

                    int y = world.getHighestBlockYAt(x, z);
                    if (y < 5 || y > 250) continue;

                    Location loc = new Location(world, x, y, z);
                    if (!loc.getBlock().getType().isAir()) continue;

                    Material below = loc.clone().add(0, -1, 0).getBlock().getType();
                    if (!below.isSolid() || below.name().contains("LEAVES")) continue;

                    if (isInsideNulZone(loc)) continue;

                    return loc;
                }
            }
        }

        return null;
    }


    public void tryTriggerNaturalPortal(Location frameBase) {
        World world = frameBase.getWorld();
        Block blockAbove = frameBase.clone().add(0, 1, 0).getBlock();

        // Place fire block on top of obsidian — just like the player would do
        if (blockAbove.getType() == Material.AIR) {
            blockAbove.setType(Material.FIRE);
            //plugin.getLogger().info("🔥 Triggered default portal ignition at " + frameBase);
        } else {
            //plugin.getLogger().warning("⚠ Cannot place fire above block: " + blockAbove.getType());
        }
    }





    public int getPortalPairCount(UUID owner) {
        File playerFile = new File(dataFolder, owner.toString() + ".yml");
        if (!playerFile.exists()) {
            return 0;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        ConfigurationSection portalsSection = config.getConfigurationSection("portals");
        if (portalsSection == null) {
            return 0;
        }
        return portalsSection.getKeys(false).size();
    }

    public List<Portal> findPortalsByOwner(UUID owner) {
        List<Portal> portals = new ArrayList<>();
        File playerFile = new File(dataFolder, owner.toString() + ".yml");
        if (!playerFile.exists()) {
            return portals;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        String portalsPath = "portals";
        if (!config.contains(portalsPath)) {
            return portals;
        }

        ConfigurationSection portalsSection = config.getConfigurationSection(portalsPath);
        for (String linkCode : portalsSection.getKeys(false)) {
            ConfigurationSection linkCodeSection = portalsSection.getConfigurationSection(linkCode);
            if (linkCodeSection != null && linkCodeSection.contains("worlds")) {
                ConfigurationSection worldsSection = linkCodeSection.getConfigurationSection("worlds");
                for (String worldName : worldsSection.getKeys(false)) {
                    Portal portal = loadPortal(linkCode, worldName);
                    if (portal != null) {
                        portals.add(portal);
                    }
                }
            }
        }
        return portals;
    }

    public List<Portal> findPortalsByLinkCode(String linkCode) {
        List<Portal> portals = new ArrayList<>();
        UUID ownerUUID = getPortalOwner(linkCode);
        if (ownerUUID == null) {
            return portals;
        }

        File playerFile = new File(dataFolder, ownerUUID.toString() + ".yml");
        if (!playerFile.exists()) {
            return portals;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        String linkCodeWorldsPath = "portals." + linkCode + ".worlds";
        if (!config.contains(linkCodeWorldsPath)) {
            return portals;
        }

        ConfigurationSection worldsSection = config.getConfigurationSection(linkCodeWorldsPath);
        for (String worldName : worldsSection.getKeys(false)) {
            Portal portal = loadPortal(linkCode, worldName);
            if (portal != null) {
                portals.add(portal);
            }
        }
        return portals;
    }

    public void setOwner(String linkCode, UUID newOwnerUUID, CommandSender sender) {
        UUID oldOwnerUUID = getPortalOwner(linkCode);
        if (oldOwnerUUID == null) {
            sender.sendMessage("§cPortal with link code '" + linkCode + "' not found.");
            return;
        }

        if (oldOwnerUUID.equals(newOwnerUUID)) {
            sender.sendMessage("§eThat player is already the owner of this portal.");
            return;
        }

        File oldPlayerFile = new File(dataFolder, oldOwnerUUID.toString() + ".yml");
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(oldPlayerFile);
        File newPlayerFile = new File(dataFolder, newOwnerUUID.toString() + ".yml");
        YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(newPlayerFile);

        String portalPath = "portals." + linkCode;
        ConfigurationSection portalData = oldConfig.getConfigurationSection(portalPath);

        if (portalData == null) {
            sender.sendMessage("§cCould not find portal data in the old owner's file. The data might be corrupted.");
            plugin.getGlobalPortalMap().set(linkCode, null);
            plugin.saveGlobalMap();
            return;
        }

        newConfig.set(portalPath, portalData);
        oldConfig.set(portalPath, null);

        try {
            oldConfig.save(oldPlayerFile);
            newConfig.save(newPlayerFile);

            plugin.getGlobalPortalMap().set(linkCode + ".owner", newOwnerUUID.toString());
            plugin.saveGlobalMap();

            portalCache.keySet().removeIf(key -> key.startsWith(linkCode + ":"));

            OfflinePlayer newOwnerPlayer = Bukkit.getOfflinePlayer(newOwnerUUID);
            String newOwnerName = newOwnerPlayer.getName() != null ? newOwnerPlayer.getName() : newOwnerUUID.toString();
            sender.sendMessage("§aPortal link " + linkCode + " has been transferred to " + newOwnerName);

        } catch (IOException e) {
            sender.sendMessage("§cAn error occurred while saving player data.");
            e.printStackTrace();
        }
    }

    private boolean isOverlappingWithExistingPortal(PortalFrame frame, String linkCode) {
        // Get all corners of the new portal
        Location min = frame.bottomLeft.clone();
        Location max = frame.topRight.clone();

        // Check all existing markers in the world
        for (Entity entity : min.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            String existingCode = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "linkCode"),
                PersistentDataType.STRING
            );

            // Skip our own portal
            if (linkCode.equals(existingCode)) continue;

            // Check if this marker is within our portal's bounds
            Location markerLoc = stand.getLocation();
            if (markerLoc.getX() >= min.getX() && markerLoc.getX() <= max.getX() &&
                markerLoc.getY() >= min.getY() && markerLoc.getY() <= max.getY() &&
                markerLoc.getZ() >= min.getZ() && markerLoc.getZ() <= max.getZ()) {
                return true;
            }
        }
        return false;
    }
    public boolean isHorizontalPortal(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        Material typeX1 = world.getBlockAt(x + 1, y, z).getType();
        Material typeX2 = world.getBlockAt(x - 1, y, z).getType();
        Material typeZ1 = world.getBlockAt(x, y, z + 1).getType();
        Material typeZ2 = world.getBlockAt(x, y, z - 1).getType();

        return (typeX1 == Material.NETHER_PORTAL || typeX2 == Material.NETHER_PORTAL) &&
               !(typeZ1 == Material.NETHER_PORTAL || typeZ2 == Material.NETHER_PORTAL);
    }

    public boolean isMarkerPresent(String linkCode, Location near) {
        for (Entity e : near.getWorld().getNearbyEntities(near, 3, 3, 3)) {
            if (!(e instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;
            String tag = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
            if (linkCode.equalsIgnoreCase(tag)) return true;
        }
        return false;
    }


    /*public String getLinkCode(String playerName, String worldName, boolean isReturn) {
    	UUID uuid = getUUIDFromName(playerName);
    	YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        String path = "links." + worldName + "." + (isReturn ? "return" : "destination") + ".linkCode";
        return config.getString(path);
    }*/
    public String getLinkCode(String playerName, String worldName, boolean isReturn) {
        UUID uuid = getUUIDFromName(playerName);
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection codeSection = links.getConfigurationSection(code);
            if (codeSection != null && codeSection.contains(worldName)) {
                return code;
            }
        }

        return null;
    }
 
    public void tagPortalFrame(Location loc, String linkCode, UUID owner) {
        World world = loc.getWorld();
        if (world == null) return;

        ArmorStand stand = world.spawn(loc.clone().add(0.5, 0.1, 0.5), ArmorStand.class);
        stand.setMarker(true);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setCustomNameVisible(false);
        stand.setSilent(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.addScoreboardTag("gnp_marker");

        PersistentDataContainer data = stand.getPersistentDataContainer();
        NamespacedKey linkKey = new NamespacedKey(plugin, "linkCode");
        NamespacedKey ownerKey = new NamespacedKey(plugin, "ownerUUID");

        data.set(linkKey, PersistentDataType.STRING, linkCode);
        data.set(ownerKey, PersistentDataType.STRING, owner.toString());

        //plugin.getLogger().info("🛰️ Spawned portal marker for linkCode: " + linkCode);
    }


    public boolean isValidPortal(Location center) {
        Material mat = center.getBlock().getType();
        return mat == Material.NETHER_PORTAL && 
               center.getBlock().getRelative(BlockFace.DOWN).getType() == Material.OBSIDIAN;
    }

    public Location findBottomLeftCorner(Location portalBlock) {
        World world = portalBlock.getWorld();
        Set<Location> visited = new HashSet<>();
        Queue<Location> queue = new LinkedList<>();
        queue.add(portalBlock);

        // Find all connected NETHER_PORTAL blocks
        while (!queue.isEmpty()) {
            Location loc = queue.poll();
            if (!visited.add(loc)) continue;

            for (BlockFace face : new BlockFace[] {
                    BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
                Location adj = loc.clone().add(face.getModX(), face.getModY(), face.getModZ());
                if (!visited.contains(adj) &&
                    adj.getBlock().getType() == Material.NETHER_PORTAL) {
                    queue.add(adj);
                }
            }
        }

        // Now find the lowest + most west/north portal block
        Location bottomLeft = portalBlock;
        for (Location loc : visited) {
            if (isLowerCorner(loc, bottomLeft)) {
                bottomLeft = loc;
            }
        }

        // Attempt to walk back 1 block to reach actual obsidian frame
        Axis axis = detectPortalAxis(bottomLeft);
        if (axis == Axis.X) {
            return bottomLeft.clone().add(-1, -1, 0); // frame block WEST-DOWN
        } else {
            return bottomLeft.clone().add(0, -1, -1); // frame block NORTH-DOWN
        }
    }
    private boolean isLowerCorner(Location a, Location b) {
        return a.getBlockY() < b.getBlockY() ||
               (a.getBlockY() == b.getBlockY() && (
                 a.getBlockX() < b.getBlockX() ||
                 a.getBlockZ() < b.getBlockZ()
               ));
    }

    private Axis detectPortalAxis(Location loc) {
        World world = loc.getWorld();
        Block center = world.getBlockAt(loc);
        if (center.getRelative(BlockFace.EAST).getType() == Material.NETHER_PORTAL ||
            center.getRelative(BlockFace.WEST).getType() == Material.NETHER_PORTAL) {
            return Axis.X;
        }
        return Axis.Z;
    }

    public void cleanupGlobalPortalMap(CommandSender sender) {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        if (!file.exists()) {
            sender.sendMessage("§cportalMap.yml not found.");
            return;
        }

        YamlConfiguration mapConfig = YamlConfiguration.loadConfiguration(file);
        int removed = 0;

        for (String code : new ArrayList<>(mapConfig.getKeys(false))) {
            ConfigurationSection section = mapConfig.getConfigurationSection(code);
            if (section == null) continue;

            String worldName = section.getString("world");
            double x = section.getDouble("x");
            double y = section.getDouble("y");
            double z = section.getDouble("z");

            if (worldName == null || Bukkit.getWorld(worldName) == null) {
                //plugin.getLogger().warning("⚠ Skipping invalid or missing world in portalMap: " + worldName);
                continue;
            }

            World world = Bukkit.getWorld(worldName);

            if (world == null) continue;

            Location loc = new Location(world, x, y, z);
            if (loc.getBlock().getType() != Material.OBSIDIAN) {
                mapConfig.set(code, null);
                removed++;
                sender.sendMessage("§7✘ Removed portal " + code + " at " + worldName + " (" + x + ", " + y + ", " + z + ")");
            }
        }

        try {
            mapConfig.save(file);
            sender.sendMessage("§a✔ Cleaned " + removed + " invalid portals from portalMap.yml.");
        } catch (IOException e) {
            sender.sendMessage("§c✘ Failed to save portalMap.yml!");
            //plugin.getLogger().warning("Could not save portalMap.yml");
            e.printStackTrace();
        }
    }


    public void cleanupInvalidPortals(CommandSender sender) {
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            sender.sendMessage("§7No portal files found to clean.");
            return;
        }

        int cleaned = 0;

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection map = config.getConfigurationSection("portalMap");
            if (map == null) continue;

            boolean changed = false;
            for (String code : new ArrayList<>(map.getKeys(false))) {
                ConfigurationSection section = map.getConfigurationSection(code);
                if (section == null) continue;

                String worldName = section.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                double x = section.getDouble("x");
                double y = section.getDouble("y");
                double z = section.getDouble("z");

                Location loc = new Location(world, x, y, z);
                if (!isValidPortal(loc)) {
                    sender.sendMessage("§7✘ Removed invalid portal '" + code + "' from file: " + file.getName());
                    map.set(code, null);
                    changed = true;
                    cleaned++;
                }
            }

            if (changed) {
                try {
                    config.save(file);
                } catch (IOException e) {
                    //plugin.getLogger().warning("❌ Could not save cleaned portal file: " + file.getName());
                }
            }
        }

        sender.sendMessage("§a✔ Cleanup complete. " + cleaned + " invalid portal(s) removed.");
    }


    public void cleanupAllPortals(CommandSender sender) {
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        int totalCleaned = 0;

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection map = config.getConfigurationSection("portalMap");
            if (map == null) continue;

            boolean changed = false;

            for (String code : new ArrayList<>(map.getKeys(false))) {
                String path = "portalMap." + code;
                String worldName = config.getString(path + ".world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                Location loc = new Location(world,
                    config.getDouble(path + ".x"),
                    config.getDouble(path + ".y"),
                    config.getDouble(path + ".z")
                );

                if (!isValidPortal(loc)) {
                    map.set(code, null);
                    changed = true;
                    totalCleaned++;
                    sender.sendMessage("§7✘ Removed invalid portal " + code + " in " + worldName);
                }
            }

            if (changed) {
                try {
                    config.save(file);
                } catch (IOException e) {
                    //plugin.getLogger().warning("❌ Could not save " + file.getName());
                }
            }
        }

        sender.sendMessage("§a✔ Cleanup complete. Removed " + totalCleaned + " invalid portal(s).");
    }

    public void cleanupInvalidPortals(UUID uuid) {
    	File file = new File(plugin.getDataFolder(), "portalMap.yml");
    	YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
    	ConfigurationSection map = config.getConfigurationSection("portalMap");

        if (map == null) return;

        boolean changed = false;
        for (String code : new ArrayList<>(map.getKeys(false))) {
            ConfigurationSection section = map.getConfigurationSection(code);
            if (section == null) continue;

            String worldName = section.getString("world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            Location loc = new Location(world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z")
            );

            if (!isValidPortal(loc)) {
                map.set(code, null);
                changed = true;
            }
        }

        if (changed) {
            saveConfig(uuid, config);
        }
    }

    public Location findSafeNearbyLocation(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return null;

        // Define Y-limits based on world type
        int minY = world.getMinHeight() + 5;
        int maxY = world.getMaxHeight() - 10; // Extra buffer for building
        if (world.getEnvironment() == World.Environment.NETHER) {
            maxY = 123; // 128 - 5, standard nether ceiling
        }

        List<Location> existingPortalLocations = new ArrayList<>();
        for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
            if (entity.getScoreboardTags().contains("gnp_marker")) {
                existingPortalLocations.add(entity.getLocation());
            }
        }

        // Spiral search pattern
        int x = 0, z = 0;
        int dx = 0, dz = -1;
        int maxDistSq = radius * radius;

        for (int i = 0; i < maxDistSq; i++) {
            if ((-radius <= x && x <= radius) && (-radius <= z && z <= radius)) {

                int currentX = center.getBlockX() + x;
                int currentZ = center.getBlockZ() + z;

                for (int currentY = minY; currentY < maxY; currentY++) {
                    Location potentialLoc = new Location(world, currentX, currentY, currentZ);

                    if (!world.getWorldBorder().isInside(potentialLoc)) {
                        continue;
                    }

                    boolean tooClose = false;
                    for (Location existing : existingPortalLocations) {
                        if (existing.distanceSquared(potentialLoc) < 25) { // 5*5
                            tooClose = true;
                            break;
                        }
                    }
                    if (tooClose) continue;

                    if (isSafeSpot(potentialLoc)) {
                        return potentialLoc;
                    }
                }
            }

            // Spiral logic
            if ((x == z) || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int temp = dx;
                dx = -dz;
                dz = temp;
            }
            x += dx;
            z += dz;
        }

        return null; // No safe location found
    }

    private boolean isSafeSpot(Location loc) {
        // Check for solid ground
        if (!loc.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
            return false;
        }

        // Check for a 5x5x5 cube of air above the location, which is enough for any portal frame
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!loc.clone().add(dx, dy, dz).getBlock().isPassable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public void buildBasicPortalFrame(Location corner) {
        World world = corner.getWorld();
        if (world == null) return;

        // Frame
        for (int i = 0; i < 4; i++) {
            corner.clone().add(i, 0, 0).getBlock().setType(Material.OBSIDIAN);
            corner.clone().add(i, 3, 0).getBlock().setType(Material.OBSIDIAN);
        }
        for (int i = 1; i < 3; i++) {
            corner.clone().add(0, i, 0).getBlock().setType(Material.OBSIDIAN);
            corner.clone().add(3, i, 0).getBlock().setType(Material.OBSIDIAN);
        }
        // Ignite
        corner.clone().add(1, 1, 0).getBlock().setType(Material.FIRE);
    }

    public void tryRegisterArrivalPortal(Location base, String linkCode, UUID owner) {
        if (base == null || linkCode == null || owner == null) return;

        World world = base.getWorld();
        if (world == null) return;

        // 🧱 Find the bottom-left corner of any portal nearby
        Location frameCorner = findBottomLeftCorner(base);

        // 🛡 Safety: if no valid frame is found, build one
        if (frameCorner == null) {
            //plugin.getLogger().warning("⚠ No portal block found near arrival. Attempting to create frame...");

            frameCorner = findSafeNearbyLocation(base, 10); // Offset search
            if (frameCorner != null) {
                buildBasicPortalFrame(frameCorner); // Create obsidian frame + portal
                //plugin.getLogger().info("✅ Forced basic portal structure created at " + frameCorner);
            } else {
                //plugin.getLogger().severe("❌ Failed to find or create a safe portal frame.");
                return;
            }
        }

        // 🏷 Tag portal frame with linkCode
        tagPortalFrame(frameCorner, linkCode, owner);

        // 📍 Determine marker location
        PortalFrame frame = scanFullPortalFrame(frameCorner);
        if (frame != null) {
            spawnPortalMarker(frame, linkCode, owner);
        } else {
            //plugin.getLogger().warning("⚠ Failed to determine marker position for portal.");
        }
    }


    public Location findNearbyPortalBlock(Location origin) {
        int scanRadius = 5;
        int yScan = 4;

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -yScan; dy <= yScan; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    Location check = origin.clone().add(dx, dy, dz);
                    if (check.getBlock().getType() == Material.NETHER_PORTAL) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    public boolean hasPortalCreationPermission(Player player) {
        if (player.hasPermission("goatportals.admin")) {
            return true;
        }
        if (plugin.getLuckPerms() == null) {
            return true; // No permission plugin, so allow by default.
        }

        int limit = -1; // -1 for unlimited
        // Find the highest gnp.limit.<number> permission the player has.
        for (var node : plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId()).getNodes()) {
            if (node.getKey().startsWith("gnp.limit.")) {
                try {
                    int nodeLimit = Integer.parseInt(node.getKey().substring("gnp.limit.".length()));
                    if (nodeLimit > limit) {
                        limit = nodeLimit;
                    }
                } catch (NumberFormatException e) {
                    // Ignore invalid permission nodes like gnp.limit.abc
                }
            }
        }

        if (limit == -1) {
            return true; // Unlimited if no limit permission is set.
        }

        int currentPortalPairs = getPortalPairCount(player.getUniqueId());
        if (currentPortalPairs >= limit) {
            player.sendMessage("§cYou have reached your portal limit of " + limit + ".");
            return false;
        }

        return true;
    }


    public void createPortalPair(UUID owner, Location location, PortalFrame frame) {
        Player player = owner.equals(SERVER_UUID) ? null : Bukkit.getPlayer(owner);
        if (player != null && !hasPortalCreationPermission(player)) {
            return;
        }

        String linkCode = generateUniqueLinkCode();
        Portal portalA = new Portal(linkCode, owner, location, frame);
        Location destinationB = convertToOppositeWorldLocation(location);
        if (destinationB == null) {
            if (player != null) player.sendMessage("§cCould not determine a destination for this portal.");
            else plugin.debugLog("Could not determine a destination for this portal. LinkCode: " + linkCode);
            return;
        }

        Location safeLocationB = findSafeNearbyLocation(destinationB, 16);
        if (safeLocationB == null) {
            if (player != null) player.sendMessage("§cCould not find a safe location for the linked portal.");
            else plugin.debugLog("Could not find a safe location for the linked portal. LinkCode: " + linkCode);
            return;
        }

        copyPortalFrameFromTo(location, safeLocationB, frame.width, frame.height, frame.orientation);

        PortalFrame frameB = scanFullPortalFrame(safeLocationB);
        if (frameB == null) {
            if (player != null) player.sendMessage("§cFailed to create the linked portal. Please try again.");
            else plugin.debugLog("Failed to create the linked portal. LinkCode: " + linkCode);
            return;
        }

        Portal portalB = new Portal(linkCode, owner, safeLocationB, frameB);
        savePortal(portalA);
        savePortal(portalB);
        addPortalToGlobalMap(linkCode, owner);
        spawnPortalMarker(frame, linkCode, owner);
        spawnPortalMarker(frameB, linkCode, owner);

        if (player != null) {
            player.sendMessage("§aPortals successfully created with link code: " + linkCode);
        } else {
            plugin.debugLog("Portals successfully created with link code: " + linkCode);
        }
    }
 
    public void addPortalToGlobalMap(String linkCode, UUID owner) {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        if (!yaml.contains(linkCode)) {
            yaml.set(linkCode + ".owner", owner.toString());

            try {
                yaml.save(file);
                plugin.debugLog("✅ Added portal link " + linkCode + " to portalMap.yml");
            } catch (IOException e) {
                plugin.debugLog("❌ Failed to save portalMap.yml");
                e.printStackTrace();
            }
        }
    }
    
    public void setPortalMetadata(String linkCode, String keyPath, Object value) {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        yaml.set(linkCode + "." + keyPath, value);

        try {
            yaml.save(file);
            plugin.debugLog("✅ Updated portalMap: " + linkCode + "." + keyPath + " = " + value);
        } catch (IOException e) {
            plugin.debugLog("❌ Failed to save portalMap.yml during setPortalMetadata");
            e.printStackTrace();
        }
    }

    public UUID getPortalOwner(String linkCode) {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        if (!file.exists()) return null;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String ownerStr = yaml.getString(linkCode + ".owner");
        if (ownerStr == null) return null;

        try {
            return UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            plugin.debugLog("⚠ Invalid UUID in portalMap.yml for linkCode: " + linkCode);
            return null;
        }
    }
    

    public void spawnPortalMarker(PortalFrame frame, String linkCode, UUID ownerUUID) {
        if (frame == null || linkCode == null || ownerUUID == null) return;

        World world = frame.bottomLeft.getWorld();
        if (world == null) return;

        // Frame bounds (used for both existence check and center calc)
        Location bottomLeft = frame.bottomLeft;
        Location topRight   = frame.topRight;

        int minX = Math.min(bottomLeft.getBlockX(), topRight.getBlockX());
        int maxX = Math.max(bottomLeft.getBlockX(), topRight.getBlockX());
        int minY = Math.min(bottomLeft.getBlockY(), topRight.getBlockY());
        int maxY = Math.max(bottomLeft.getBlockY(), topRight.getBlockY());
        int minZ = Math.min(bottomLeft.getBlockZ(), topRight.getBlockZ());
        int maxZ = Math.max(bottomLeft.getBlockZ(), topRight.getBlockZ());

        // De-dupe: one marker per (link, world). If already spawned, set cornerMarker from the existing one and exit.
        String __markerKey = linkCode + "|" + world.getName()
        + "|" + frame.bottomLeft.getBlockX() + "," + frame.bottomLeft.getBlockY() + "," + frame.bottomLeft.getBlockZ()
        + "->" + frame.topRight.getBlockX() + "," + frame.topRight.getBlockY() + "," + frame.topRight.getBlockZ();

        if (!spawnedMarkers.add(__markerKey)) {
            BoundingBox dedupeBox = BoundingBox.of(bottomLeft, topRight).expand(0.5);
            for (Entity entity : world.getNearbyEntities(dedupeBox)) {
                if (!(entity instanceof ArmorStand stand)) continue;
                if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

                String existingCode = stand.getPersistentDataContainer().get(
                        new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);

                if (existingCode != null && existingCode.equalsIgnoreCase(linkCode)) {
                    Location loc = stand.getLocation();
                    int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
                    if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                        frame.cornerMarker = loc.clone(); // keep frame state in sync
                        plugin.debugLog("⏭️ [MARKER] Already spawned for " + __markerKey + " at " + formatLoc(loc));
                        return;
                    }
                }
            }
            plugin.debugLog("⏭️ [MARKER] Already spawned for " + __markerKey);
            return;
        }

        // Check if a marker already exists inside this frame (e.g., after reload)
        BoundingBox box = BoundingBox.of(bottomLeft, topRight).expand(0.5);
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            String existingCode = stand.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);

            if (existingCode != null && existingCode.equalsIgnoreCase(linkCode)) {
                Location loc = stand.getLocation();
                int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

                if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                    frame.cornerMarker = loc.clone(); // keep frame state in sync
                    plugin.debugLog("✅ [MARKER] Already exists for " + linkCode + " at " + formatLoc(loc));
                    return; // Already exists inside this frame
                }
            }
        }

        // 📍 Calculate marker spawn center (interior center)
        int baseX = frame.bottomLeft.getBlockX();
        int baseY = frame.bottomLeft.getBlockY();
        int baseZ = frame.bottomLeft.getBlockZ();

        double centerX;
        double centerY = baseY + 1 + ((frame.height - 2) / 2.0);
        double centerZ;

        if (frame.orientation.equalsIgnoreCase("X")) {
            centerX = baseX + 1 + ((frame.width - 2) / 2.0);
            centerZ = baseZ + 0.5;
        } else {
            centerX = baseX + 0.5;
            centerZ = baseZ + 1 + ((frame.width - 2) / 2.0);
        }

        Location markerLoc = new Location(world, centerX, centerY, centerZ);
        frame.cornerMarker = markerLoc.clone();

        // ✨ Spawn marker
        ArmorStand marker = world.spawn(markerLoc, ArmorStand.class, stand -> {
            stand.setCustomName("§bPortalMarker");
            stand.setCustomNameVisible(true);
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setPersistent(true);
            stand.setSilent(true);
            stand.setCollidable(false);
            stand.setAI(false);
            stand.addScoreboardTag("gnp_marker");

            PersistentDataContainer data = stand.getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING, linkCode);
            data.set(new NamespacedKey(plugin, "ownerUUID"), PersistentDataType.STRING, ownerUUID.toString());
            data.set(new NamespacedKey(plugin, "status"), PersistentDataType.STRING, "FRESH");
            data.set(new NamespacedKey(plugin, "gnp_corner"), PersistentDataType.STRING, baseX + "," + baseY + "," + baseZ);
            data.set(new NamespacedKey(plugin, "orientation"), PersistentDataType.STRING, frame.orientation);
        });

        plugin.debugLog("🪧 Marker spawned for " + linkCode + " at: " + markerLoc.toVector());

        // ✅ Update status if portal is valid
        if (!portalBlocksMissing(bottomLeft, frame.width, frame.height, frame.orientation)) {
            marker.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "status"), PersistentDataType.STRING, "NORMAL");
        }

        // 💾 Save marker to player YAML
        YamlConfiguration config = getOrCreatePlayerConfig(ownerUUID);
        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) linkSection = config.createSection("links." + linkCode);

        ConfigurationSection markerSection = linkSection.createSection(world.getName() + ".marker");
        markerSection.set("x", markerLoc.getX());
        markerSection.set("y", markerLoc.getY());
        markerSection.set("z", markerLoc.getZ());
        markerSection.set("corner", baseX + "," + baseY + "," + baseZ);
        markerSection.set("orientation", frame.orientation);
    }

    
    public Collection<Entity> getEntitiesInsidePortalFrame(PortalFrame frame) {
        BoundingBox box = BoundingBox.of(frame.bottomLeft, frame.topRight).expand(0.5);
        return frame.bottomLeft.getWorld().getNearbyEntities(box);
    }

    public Location calculateMarkerPosition(Location bottomLeft, String orientation, int width, int height) {
        if (bottomLeft == null || orientation == null) return null;
        
        World world = bottomLeft.getWorld();
        if (world == null) return null;

        // Calculate center position based on orientation
        double markerX, markerZ;
        if (orientation.equalsIgnoreCase("X")) {
            // Horizontal portal (X axis)
            markerX = bottomLeft.getX() + (width / 2.0);
            markerZ = bottomLeft.getZ() + 0.5;
        } else {
            // Vertical portal (Z axis)
            markerX = bottomLeft.getX() + 0.5;
            markerZ = bottomLeft.getZ() + (width / 2.0);
        }

        // Position 1 block above the portal's top edge
        double markerY = bottomLeft.getY() + height + 1.0;

        return new Location(world, markerX, markerY, markerZ);
    }

    public Location findMarkerLocation(Location portalBlock) {
        World world = portalBlock.getWorld();

        // Check basic directions to find frame edge
        Location base = portalBlock.clone();

        // Try to find a solid block behind or beneath the portal
        for (int y = 0; y >= -3; y--) {
            Location below = base.clone().add(0, y, 0);
            if (below.getBlock().getType().isSolid()) {
                return below.add(0.5, 1.0, 0.5); // Place above the solid block, centered
            }
        }

        // If nothing solid below, fall back to placing in portal block center
        return base.add(0.5, 0.0, 0.5); // Center of the portal block
    }


    public void cleanupBrokenMarkers(World world) {
        for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
            ArmorStand stand = (ArmorStand) entity;
            PersistentDataContainer container = stand.getPersistentDataContainer();
            NamespacedKey linkKey = new NamespacedKey(plugin, "linkCode");

            if (!container.has(linkKey, PersistentDataType.STRING)) continue;

            String linkCode = container.get(linkKey, PersistentDataType.STRING);
            Location markerLoc = stand.getLocation();

            // Check if the portal still exists at this location
            boolean hasPortal = false;
            for (int y = 0; y <= 3; y++) {
                Location check = markerLoc.clone().add(0, y, 0);
                if (check.getBlock().getType() == Material.NETHER_PORTAL) {
                    hasPortal = true;
                    break;
                }
            }

            if (!hasPortal) {
                //plugin.getLogger().info("§cCleaning up orphaned portal marker at " + markerLoc + " (code: " + linkCode + ")");
                stand.remove();
            }
        }
    }


    public void registerDispenserPortal(UUID uuid, String linkCode, PortalFrame frame, String worldName) {
        // 🧾 Load or create the player config
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        config.set("name", "DispenserSystem"); // Required so interior regions are loaded at startup

        // 🔗 Get or create the link section
        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) linkSection = config.createSection("links." + linkCode);

        // 🌍 Create the world-specific section
        ConfigurationSection worldSection = linkSection.createSection(worldName);

        // 🧭 Portal location (just the base block)
        ConfigurationSection locSection = worldSection.createSection("location");
        locSection.set("world", frame.bottomLeft.getWorld().getName()); // ✅ Critical for deserializeLocation
        locSection.set("x", frame.bottomLeft.getX());
        locSection.set("y", frame.bottomLeft.getY());
        locSection.set("z", frame.bottomLeft.getZ());
        locSection.set("yaw", frame.bottomLeft.getYaw());
        locSection.set("pitch", frame.bottomLeft.getPitch());


        // 🧱 Frame structure
        ConfigurationSection frameSection = worldSection.createSection("frame");
        frameSection.set("orientation", frame.orientation);
        ConfigurationSection cornerSection = frameSection.createSection("corner");
        cornerSection.set("Bottom-Left", formatCoord(frame.bottomLeft));
        cornerSection.set("Bottom-Right", formatCoord(frame.bottomRight));
        cornerSection.set("Top-Left", formatCoord(frame.topLeft));
        cornerSection.set("Top-Right", formatCoord(frame.topRight));

        // 🌀 Compute interior bounds using computeMin/Max
        Location min = computeMin(frame.portalBlocks);
        Location max = computeMax(frame.portalBlocks);

        // ➕ Expand by 1 block like player flow does
        int expandX = 0, expandZ = 0;
        if ("X".equalsIgnoreCase(frame.orientation)) expandZ = 1;
        else expandX = 1;

        Location expandedMin = min.clone().subtract(expandX, 0, expandZ);
        Location expandedMax = max.clone().add(expandX, 0, expandZ);

        // 💾 Write interior section
        ConfigurationSection interior = worldSection.createSection("interior");
        interior.set("min", formatCoord(expandedMin));
        interior.set("max", formatCoord(expandedMax));

        // 🪧 Marker (centered in interior box)
        Location markerLoc = expandedMin.clone().add(
            (expandedMax.getX() - expandedMin.getX()) / 2.0,
            (expandedMax.getY() - expandedMin.getY()) / 2.0,
            (expandedMax.getZ() - expandedMin.getZ()) / 2.0
        );

        ConfigurationSection marker = worldSection.createSection("marker");
        marker.set("x", markerLoc.getX());
        marker.set("y", markerLoc.getY());
        marker.set("z", markerLoc.getZ());
        marker.set("corner", formatCoord(frame.bottomLeft));
        marker.set("orientation", frame.orientation);

        // 🔁 Add detection region if DetectionRegion(Location, Location, String) exists
        //detectionRegions.add(new DetectionRegion(expandedMin, expandedMax, linkCode));
        DetectionRegion region = new DetectionRegion(expandedMin, expandedMax, linkCode);
        //region.setWorldName(worldName); // make sure your DetectionRegion supports this
        detectionRegions.add(region);

        // 🏷️ Flag this link as awaiting pairing
        //linkSection.set("pendingPair", true);

        // 🌐 Add stub entry for opposite world
        String oppositeWorld = plugin.getOppositeWorld(worldName);
        if (oppositeWorld != null) {
            ConfigurationSection oppSection = linkSection.getConfigurationSection(oppositeWorld);
            if (oppSection == null) oppSection = linkSection.createSection(oppositeWorld);

        }
        linkSection.set("pendingPair", true);

        try {
            File file = new File(new File(plugin.getDataFolder(), "playerdata"), uuid.toString() + ".yml");
            config.save(file);
            plugin.debugLog("✅ [DISPENSER-REGISTER] Portal origin registered and saved for: " + linkCode);

            // 🪧 Spawn the marker after save
            spawnPortalMarker(frame, linkCode, uuid);
         // 🔄 Force refresh detection zone in memory
            plugin.loadPlayerData(uuid);
            plugin.debugLog("🔁 [DISPENSER-REGISTER] Player config reloaded into memory for link: " + linkCode);

            tagPortalFrame(frame.bottomLeft, linkCode, uuid);

        } catch (IOException e) {
            plugin.debugLog("❌ [DISPENSER-REGISTER] Failed to save portal origin.");
            e.printStackTrace();
        }


    }
    public Location computeMin(List<Block> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        World world = null;

        for (Block block : blocks) {
            Location loc = block.getLocation();
            if (world == null) world = loc.getWorld();

            minX = Math.min(minX, loc.getBlockX());
            minY = Math.min(minY, loc.getBlockY());
            minZ = Math.min(minZ, loc.getBlockZ());
        }

        return new Location(world, minX, minY, minZ);
    }

    public Location computeMax(List<Block> blocks) {
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        World world = null;

        for (Block block : blocks) {
            Location loc = block.getLocation();
            if (world == null) world = loc.getWorld();

            maxX = Math.max(maxX, loc.getBlockX());
            maxY = Math.max(maxY, loc.getBlockY());
            maxZ = Math.max(maxZ, loc.getBlockZ());
        }

        return new Location(world, maxX, maxY, maxZ);
    }

    public long getLastIgniteTime(UUID uuid) {
        cleanOldIgnites(); // Clean old entries before checking
        return recentIgniteTimestamps.getOrDefault(uuid, 0L);
    }

    public void updateIgniteTime(UUID uuid) {
        recentIgniteTimestamps.put(uuid, System.currentTimeMillis());
    }

    private void cleanOldIgnites() {
        long cutoff = System.currentTimeMillis() - 10_000; // 10 seconds
        recentIgniteTimestamps.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }


    public boolean isPortalNearby(Location loc, int radius) {
        World world = loc.getWorld();
        if (world == null) return false;

        for (Entity entity : world.getEntities()) {
            if (entity instanceof ArmorStand stand) {
                PersistentDataContainer data = stand.getPersistentDataContainer();
                if (data.has(linkCodeKey, PersistentDataType.STRING)) {
                    if (stand.getLocation().distanceSquared(loc) <= radius * radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private NamespacedKey key(String path) {
        return new NamespacedKey(plugin, path);
    }

    public PortalFrame scanFullPortalFrame(Location origin) {
    	//return scanFullPortalFrame(origin, new HashSet<>(), true);
    	return scanFullPortalFrame(origin, new HashSet<>(), true, false);

    }
    public PortalFrame scanFullPortalFrame(Location origin, Set<Location> visited, boolean suppressDuplicate) {
        return scanFullPortalFrame(origin, visited, suppressDuplicate, false);
    }

    public String getLinkCodeFromBlock(Location loc) {
        return findLinkCodeAt(loc);
    }

    public PortalFrame scanFullPortalFrame(Location origin, Set<Location> visited, boolean suppressDuplicate, boolean isFromDispenser) {
        // 🚫 SCAN LOCK to prevent rapid repeat scans of the same spot
    	Location center = origin.getBlock().getLocation(); // ← Keep this line as is

    	String key = center.getWorld().getName() + "::" + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ();
    	long now = System.currentTimeMillis();
    	
        String existingCode = getLinkCodeFromBlock(origin);
        if (existingCode != null) {
            plugin.debugLog("🔗 Found existing portal with code: " + existingCode);
            return null; // Or return existing frame if needed
        }
        
    	if (suppressDuplicate) {
    	    long last = recentScanLock.getOrDefault(key, 0L);
    	    if (now - last < 1000) {
	        plugin.debugLog("⏳ [SCAN] Skipping duplicate scan at: " + key);
    	        return null;
    	    }
    	    recentScanLock.put(key, now);
    	}
        if (origin == null || origin.getWorld() == null) {
            plugin.debugLog("❌ [SCAN] Null origin or world");
            return null;
        }


        //FROM HERE DOWN IS DEBUGGER
        if (origin.getBlockX() == 0 && origin.getBlockY() == 0 && origin.getBlockZ() == 0) {
            plugin.debugLog("🛑 SCAN STARTED FROM (0,0,0) — TRACKING DOWN SOURCE");
            Thread.dumpStack(); // still useful for full trace
            return null;
        }

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stack.length; i++) {
            if (!stack[i].getClassName().contains("PortalManager")) {
                plugin.debugLog("📌 Actual scanFullPortalFrame caller: " +
                    stack[i].getClassName() + "::" + stack[i].getMethodName() + " @ line " + stack[i].getLineNumber());
                break;
            }
        }
        //END OF DEBUGGERS

        World world = origin.getWorld();
        if (world == null) return null;

        //Location center = origin.getBlock().getLocation();
        if (visited.contains(center)) {
            plugin.debugLog("🔁 [SCAN] Skipping already visited location: " + formatLoc(center));
            return null;
        }
        visited.add(center);
        plugin.debugLog("🌀 [SCAN] Scanning from portal block: " + formatLoc(center));

        Axis axis = detectPortalAxis(center);
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;

        /*while (world.getBlockAt(center.clone().add(0, -1, 0)).getType() == Material.NETHER_PORTAL) {
            center.add(0, -1, 0);
        }*/
        if (!isFromDispenser) {
            while (world.getBlockAt(center.clone().add(0, -1, 0)).getType() == Material.NETHER_PORTAL) {
                center.add(0, -1, 0);
            }
        } else {
            plugin.debugLog("🎯 Dispenser scan: skipping downward adjustment");
        }



        Location left = walkUntilNotPortal(center.clone(), -dx, 0, -dz);
        Location right = walkUntilNotPortal(center.clone(), dx, 0, dz);
        int width = Math.abs(right.getBlockX() - left.getBlockX() + right.getBlockZ() - left.getBlockZ()) + 1;

        int height = 1;
        while (world.getBlockAt(left.getBlockX(), center.getBlockY() + height, left.getBlockZ()).getType() == Material.NETHER_PORTAL) {
            height++;
        }

        if (width < 2 || height < 3) {
            plugin.debugLog("❌ [SCAN] Frame too small. Width: " + width + ", Height: " + height);
            return null;
        }

        Location bottomLeft = left.clone().add(-dx, -1, -dz);
        Location bottomRight = right.clone().add(dx, -1, dz);
        Location topLeft = bottomLeft.clone().add(0, height + 1, 0);
        Location topRight = bottomRight.clone().add(0, height + 1, 0);

        boolean valid = true;

        for (int i = 0; i <= width + 1; i++) {
            Location b = bottomLeft.clone().add(dx * i, 0, dz * i);
            Location t = topLeft.clone().add(dx * i, 0, dz * i);
            if (!isFrame(b.getBlock().getType())) {
                plugin.debugLog("❌ Bottom edge block invalid at: " + formatLoc(b));
                valid = false;
            }
            if (!isFrame(t.getBlock().getType())) {
                plugin.debugLog("❌ Top edge block invalid at: " + formatLoc(t));
                valid = false;
            }
        }

        for (int j = 1; j <= height; j++) {
            Location l = bottomLeft.clone().add(0, j, 0);
            Location r = bottomRight.clone().add(0, j, 0);
            if (!isFrame(l.getBlock().getType())) {
                plugin.debugLog("❌ Left edge block invalid at: " + formatLoc(l));
                valid = false;
            }
            if (!isFrame(r.getBlock().getType())) {
                plugin.debugLog("❌ Right edge block invalid at: " + formatLoc(r));
                valid = false;
            }
        }
        List<Block> portalBlocks = new ArrayList<>();

        int portalCount = 0;
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                Location loc = left.clone().add(dx * w, h, dz * w);
                Material m = loc.getBlock().getType();
                if (m != Material.NETHER_PORTAL && m != Material.AIR) {
                    plugin.debugLog("❌ Interior block invalid at: " + formatLoc(loc) + " was " + m);
                    return null;
                }
                if (m == Material.NETHER_PORTAL) {
                    portalBlocks.add(loc.getBlock());
                    portalCount++;
                }

            }
        }

        int diamonds = 0;
        for (Location loc : new Location[]{bottomLeft, bottomRight, topLeft, topRight}) {
            if (loc.getBlock().getType() == Material.DIAMOND_BLOCK) {
                diamonds++;
                plugin.debugLog("💎 Diamond corner at: " + formatLoc(loc));
            }
        }

        plugin.debugLog("✅ [SCAN] Portal interior size: " + width + "w × " + height + "h (usable space)");
        plugin.debugLog("🧱 Frame outer size: " + (width + 2) + "w × " + (height + 2) + "h (including obsidian)");
        plugin.debugLog("🧭 Orientation axis: " + axis.name());
        plugin.debugLog("🔲 Frame corner coordinates:");
        plugin.debugLog("  ◼ Bottom-Left : " + formatLoc(bottomLeft));
        plugin.debugLog("  ◻ Bottom-Right: " + formatLoc(bottomRight));
        plugin.debugLog("  ◽ Top-Left    : " + formatLoc(topLeft));
        plugin.debugLog("  ◼ Top-Right   : " + formatLoc(topRight));

        plugin.debugLog("🟪 Portal blocks found: " + portalCount);
        plugin.debugLog("💎 Diamond corners: " + diamonds);

        for (Player p : world.getPlayers()) {
            showPortalScanVisualization(p, bottomLeft, width, height, axis);
        }

        if (bottomLeft == null || bottomRight == null || topLeft == null || topRight == null) {
            plugin.debugLog("❌ [SCAN] Failed to determine all frame corners");
            return null;
        }
        return new PortalFrame(width + 2, height + 2, axis.name(),
		    bottomLeft, bottomRight, topLeft, topRight, portalBlocks, diamonds);

    }

    private Location walkUntilNotPortal(Location start, int dx, int dy, int dz) {
        Location current = start.clone();
        while (current.getBlock().getType() == Material.NETHER_PORTAL) {
            current.add(dx, dy, dz);
        }
        return current.subtract(dx, dy, dz);
    }

    public void showPortalScanVisualization(Player player, Location bottomLeft, int width, int height, Axis axis) {
        World world = bottomLeft.getWorld();
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;

        // Show perimeter
        for (int i = 0; i <= width + 1; i++) {
            for (int j = 0; j <= height + 1; j += height + 1) {
                Location edge = bottomLeft.clone().add(dx * i, j, dz * i).add(0.5, 0.5, 0.5);
                world.spawnParticle(Particle.FLAME, edge, 1, 0, 0, 0, 0);
            }
        }

        for (int j = 0; j <= height + 1; j++) {
            for (int i = 0; i <= width + 1; i += width + 1) {
                Location edge = bottomLeft.clone().add(dx * i, j, dz * i).add(0.5, 0.5, 0.5);
                world.spawnParticle(Particle.FLAME, edge, 1, 0, 0, 0, 0);
            }
        }

        // Show portal interior
        for (int w = 1; w <= width; w++) {
            for (int h = 1; h <= height; h++) {
                Location inside = bottomLeft.clone().add(dx * w, h, dz * w).add(0.5, 0.5, 0.5);
                world.spawnParticle(Particle.FLAME, inside, 1, 0, 0, 0, 0);
            }
        }

        // Corners
        for (Location loc : new Location[]{
                bottomLeft.clone(),
                bottomLeft.clone().add(dx * (width + 1), 0, dz * (width + 1)),
                bottomLeft.clone().add(0, height + 1, 0),
                bottomLeft.clone().add(dx * (width + 1), height + 1, dz * (width + 1))
        }) {
            world.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 0.5, 0.5), 3, 0, 0, 0, 0);
        }

        plugin.debugLog("🎆 [SCAN] Portal preview rendered.");
    }

    private boolean isFrame(Material type) {
        return type == Material.OBSIDIAN || type == Material.DIAMOND_BLOCK;
    }

    private boolean isPortalOrAir(Block block) {
        if (!block.getChunk().isLoaded()) return false;
        Material type = block.getType();
        return type == Material.AIR || type == Material.NETHER_PORTAL;
    }

    private boolean isFrameMaterial(Material material) {
        return material == Material.OBSIDIAN || material == Material.DIAMOND_BLOCK;
    }

    private boolean hasAdjacentFrameBlock(Block block) {
        for (BlockFace face : BlockFace.values()) {
            if (isFrameMaterial(block.getRelative(face).getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean detectPortalAxis(World world, Location anchor) {
        int countX = 0, countZ = 0;
        for (int i = -2; i <= 2; i++) {
            if (world.getBlockAt(anchor.getBlockX() + i, anchor.getBlockY(), anchor.getBlockZ()).getType() == Material.NETHER_PORTAL) countX++;
            if (world.getBlockAt(anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ() + i).getType() == Material.NETHER_PORTAL) countZ++;
        }
        return countX >= countZ;
    }

    private int scanFrameWidth(World world, Location anchor, int dx, int dz) {
        int left = 0, right = 0;
        while (isPortalOrAir(world.getBlockAt(anchor.getBlockX() - dx * (left + 1), anchor.getBlockY(), anchor.getBlockZ() - dz * (left + 1)))) left++;
        while (isPortalOrAir(world.getBlockAt(anchor.getBlockX() + dx * (right + 1), anchor.getBlockY(), anchor.getBlockZ() + dz * (right + 1)))) right++;
        return left + right + 3;
    }

    private int scanFrameHeight(World world, Location anchor) {
        int down = 0, up = 0;
        while (isPortalOrAir(world.getBlockAt(anchor.getBlockX(), anchor.getBlockY() - (down + 1), anchor.getBlockZ()))) down++;
        while (isPortalOrAir(world.getBlockAt(anchor.getBlockX(), anchor.getBlockY() + (up + 1), anchor.getBlockZ()))) up++;
        return down + up + 3;
    }

    private int countPortalBlocks(World world, Location bottomLeft, int width, int height, int dx, int dz) {
        int count = 0;
        for (int w = 1; w <= width - 2; w++) {
            for (int h = 1; h <= height - 2; h++) {
                if (world.getBlockAt(bottomLeft.getBlockX() + dx * w, bottomLeft.getBlockY() + h, bottomLeft.getBlockZ() + dz * w).getType() == Material.NETHER_PORTAL) {
                    count++;
                }
            }
        }
        return count;
    }

    private String formatLoc(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }

    public void copyPortalFrameFromTo(Location sourceCorner, Location destCorner, int width, int height, String orientation) {
        try {
            World fromWorld = sourceCorner.getWorld();
            World toWorld = destCorner.getWorld();
            if (fromWorld == null || toWorld == null) {
                return;
            }

            int dx = orientation.equalsIgnoreCase("X") ? 1 : 0;
            int dz = orientation.equalsIgnoreCase("Z") ? 1 : 0;

            int x0 = sourceCorner.getBlockX();
            int y0 = sourceCorner.getBlockY();
            int z0 = sourceCorner.getBlockZ();

            BlockVector3 min = BlockVector3.at(
                x0 - dx,
                y0 - 1,
                z0 - dz
            );

            BlockVector3 max = BlockVector3.at(
                x0 + dx * (width - 1),
                y0 + height,
                z0 + dz * (width - 1)
            );

            CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(fromWorld), min, max);
            final BlockArrayClipboard[] clipboardRef = new BlockArrayClipboard[] {
                new BlockArrayClipboard(region)
            };

            // Step 1: Copy from source
            try (EditSession sourceSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(fromWorld))) {
                ForwardExtentCopy copy = new ForwardExtentCopy(sourceSession, region, clipboardRef[0], region.getMinimumPoint());
                Operations.complete(copy);
            }

            // Step 1.5: Remove disallowed materials from clipboard
            List<String> allowed = plugin.getConfig().getStringList("copy-allowed-frame-blocks");
            Set<Material> allowedMaterials = allowed.stream().map(Material::valueOf).collect(Collectors.toSet());

            clipboardRef[0].getRegion().forEach(bv -> {
                BaseBlock block = clipboardRef[0].getFullBlock(bv);
                Material material = BukkitAdapter.adapt(block.getBlockType());
                if (!allowedMaterials.contains(material)) {
                    clipboardRef[0].setBlock(bv, BlockTypes.AIR.getDefaultState().toBaseBlock());
                }
            });

            // Step 2: Paste into destination
            try (EditSession pasteSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(toWorld))) {
                ClipboardHolder holder = new ClipboardHolder(clipboardRef[0]);

                Operation operation = holder
                    .createPaste(pasteSession)
                    .to(BlockVector3.at(destCorner.getBlockX(), destCorner.getBlockY(), destCorner.getBlockZ()))
                    .ignoreAirBlocks(false)
                    .copyEntities(true)
                    .build();

                Operations.complete(operation);

                // ✅ Unmap clipboard and dereference to avoid file lock on Windows
                clipboardRef[0] = null;
                holder = null;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int verified = 0;
                for (int x = 1; x <= width - 2; x++) {
                    for (int y = 1; y <= height - 2; y++) {
                        Location check = destCorner.clone().add(
                            orientation.equalsIgnoreCase("X") ? x : 0,
                            y,
                            orientation.equalsIgnoreCase("Z") ? x : 0
                        );
                        if (check.getBlock().getType() == Material.NETHER_PORTAL) {
                            verified++;
                        }
                    }
                }

                if (verified == 0) {
                    // 🔥 Force fill portal blocks manually
                    for (int x = 1; x <= width - 2; x++) {
                        for (int y = 1; y <= height - 2; y++) {
                            Location portal = destCorner.clone().add(
                                orientation.equalsIgnoreCase("X") ? x : 0,
                                y,
                                orientation.equalsIgnoreCase("Z") ? x : 0
                            );
                            portal.getBlock().setType(Material.NETHER_PORTAL);
                        }
                    }
                    plugin.debugLog("⚠️ Missing portal blocks — filled manually.");
                } else {
                    plugin.debugLog("✅ Portal verified: " + verified + " NETHER_PORTAL blocks found.");
                }
            }, 5L); // ⏱ Slightly increased delay to let FAWE complete


            // Step 4: Force ignition if needed
            Location ignite = destCorner.clone().add(dx + 1, 1, dz + 1);
            if (ignite.getBlock().getType() == Material.AIR) {
                ignite.getBlock().setType(Material.FIRE);
            }

            if (portalBlocksMissing(destCorner, width, height, orientation)) {
                tryTriggerNaturalPortal(destCorner.clone().add(1, 1, 1));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static class PortalInfo {
        public final Location frameCorner;
        public final boolean finalized;
        public final UUID owner;

        public PortalInfo(Location frameCorner, boolean finalized, UUID owner) {
            this.frameCorner = frameCorner;
            this.finalized = finalized;
            this.owner = owner;
        }
    }

 // 🧠 Helper to get the frame.corner location from YAML
    private Location getCornerFromConfig(ConfigurationSection worldSection) {
        if (worldSection == null) return null;
        ConfigurationSection frame = worldSection.getConfigurationSection("frame");
        if (frame == null) return null;
        ConfigurationSection corner = frame.getConfigurationSection("corner");
        if (corner == null) return null;

        double x = corner.getDouble("x");
        double y = corner.getDouble("y");
        double z = corner.getDouble("z");
        World world = Bukkit.getWorld(worldSection.getName());
        return world == null ? null : new Location(world, x, y, z);
    }

    public Location convertToOppositeWorldLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        
        String worldName = loc.getWorld().getName();
        String oppositeWorldName = plugin.getOppositeWorld(worldName);
        if (oppositeWorldName == null) return null;
        
        World oppositeWorld = Bukkit.getWorld(oppositeWorldName);
        if (oppositeWorld == null) return null;
        
        // Nether to Overworld (1:8)
        if (worldName.endsWith("_nether")) {
            return new Location(oppositeWorld, loc.getBlockX() * 8, loc.getBlockY(), loc.getBlockZ() * 8);
        } 
        // Overworld to Nether (8:1)
        else {
            return new Location(oppositeWorld, loc.getBlockX() / 8, loc.getBlockY(), loc.getBlockZ() / 8);
        }
    }
    
 // ✅ Checks if a NETHER_PORTAL block exists near a given location
    public boolean portalBlocksMissing(Location base, int width, int height, String orientation) {
        int found = 0;

        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                Location check = base.clone().add(
                    orientation.equalsIgnoreCase("X") ? dx : 0,
                    dy,
                    orientation.equalsIgnoreCase("Z") ? dx : 0
                );

                if (check.getBlock().getType() == Material.NETHER_PORTAL) {
                    found++;
                }
            }
        }

        return found == 0; // ❌ No portal blocks detected
    }
    
    public String getMarkerStatus(String linkCode, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return "MISSING";

        for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            String code = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING
            );
            if (!linkCode.equalsIgnoreCase(code)) continue;

            return stand.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "status"),
                PersistentDataType.STRING,
                "FRESH"
            );
        }

        return "MISSING"; // No marker found
    }

    public boolean areBothMarkersPresent(String linkCode, String worldA, String worldB) {
        //return isMarkerPresentInWorld(linkCode, worldA) && isMarkerPresentInWorld(linkCode, worldB);
    	return isMarkerPresent(linkCode, worldA, null) && isMarkerPresent(linkCode, worldB, null);

    }

    public boolean isMarkerPresent(String linkCode, String worldName, @Nullable Location near) {
    	World world = Bukkit.getWorld(worldName);
    	if (world == null) return false;

    	for (Entity e : world.getEntitiesByClass(ArmorStand.class)) {
    	    if (!(e instanceof ArmorStand stand)) continue;
    	    if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

    	    String code = stand.getPersistentDataContainer().get(
    	        new NamespacedKey(plugin, "linkCode"),
    	        PersistentDataType.STRING
    	    );

    	    if (!linkCode.equalsIgnoreCase(code)) continue;

    	    // 🧠 Optional: if location is provided, check proximity
    	    if (near != null && !stand.getLocation().getWorld().equals(near.getWorld())) continue;
    	    if (near != null && stand.getLocation().distanceSquared(near) > 2.25) continue;

    	    return true;
    	}

    	return false;

    }

    
    public void markPortalUsedByLinkCode(String linkCode, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            PersistentDataContainer data = stand.getPersistentDataContainer();
            String code = data.get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
            if (linkCode.equalsIgnoreCase(code)) {
                data.set(new NamespacedKey(plugin, "status"), PersistentDataType.STRING, "NORMAL");
                //plugin.getLogger().info("🔁 Failsafe: Set marker " + linkCode + " to NORMAL in " + worldName);
            }
        }
    }

    public Location getSavedMarkerLocation(String linkCode, String worldName, UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        String path = "links." + linkCode + "." + worldName + ".marker";
        if (!config.contains(path)) return null;

        double x = config.getDouble(path + ".x");
        double y = config.getDouble(path + ".y");
        double z = config.getDouble(path + ".z");

        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        return new Location(world, x, y, z);
    }

    public Location getSafeTeleportLocation(Location portalCorner, int width, int height, String orientation) {
        Location base = portalCorner.clone();

        // Offset into the portal interior
        int dx = orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = orientation.equalsIgnoreCase("Z") ? 1 : 0;

        // Step into the portal and up one block (standing in portal interior)
        Location inside = base.clone().add(dx, 1, dz).add(0.5, 0, 0.5);

        // Optional: if the portal has an open interior block above, adjust Y
        for (int y = 0; y < height; y++) {
            Location check = base.clone().add(dx, y + 1, dz);
            if (check.getBlock().getType() == Material.NETHER_PORTAL) {
                return check.add(0.5, 0, 0.5); // Centered on portal interior
            }
        }

        return inside;
    }

    public boolean hasDiamondInCorners(Location frameCorner, int width, int height, String orientation) {
        if (frameCorner == null || frameCorner.getWorld() == null) return false;
        
        //plugin.getLogger().info("🔍 Running hasDiamondInCorners() for corner: " + frameCorner + " orientation: " + orientation + " width: " + width + " height: " + height);


        World world = frameCorner.getWorld();
        int baseX = frameCorner.getBlockX();
        int baseY = frameCorner.getBlockY();
        int baseZ = frameCorner.getBlockZ();

        boolean facingX = orientation.equalsIgnoreCase("X");

        int dx = facingX ? 1 : 0;
        int dz = facingX ? 0 : 1;

        Location bottomLeft = frameCorner.clone();
        Location bottomRight = frameCorner.clone().add(dx * (width + 1), 0, dz * (width + 1));
        Location topLeft = frameCorner.clone().add(0, height + 1, 0);
        Location topRight = frameCorner.clone().add(dx * (width + 1), height + 1, dz * (width + 1));



        List<Location> corners = List.of(bottomLeft, bottomRight, topLeft, topRight);

        /*for (Location corner : corners) {
            //plugin.getLogger().info("🔍 Checking corner: " + corner + " → " + corner.getBlock().getType());
            if (corner.getBlock().getType() == Material.DIAMOND_BLOCK) {
                //plugin.getLogger().warning("💎 Detected DIAMOND_BLOCK at corner: " + corner);
                return true;
            }
        }


        return false;
    }*/
        boolean foundDiamond = false;

        for (Location corner : corners) {
            Material mat = corner.getBlock().getType();
            //plugin.getLogger().info("🔍 Checking corner: " + corner + " → " + mat);

            if (mat == Material.DIAMOND_BLOCK) {
                //plugin.getLogger().warning("💎 Detected DIAMOND_BLOCK at corner: " + corner);
                foundDiamond = true; // don't return yet
            }
        }

        return foundDiamond;
    }
        
    public int measurePortalWidthFromPortalBlock(Location portalBlock) {
        World world = portalBlock.getWorld();
        if (world == null) return 0;

        int baseX = portalBlock.getBlockX();
        int baseY = portalBlock.getBlockY();
        int baseZ = portalBlock.getBlockZ();

        boolean isX = isHorizontalPortal(portalBlock);
        int dx = isX ? 1 : 0;
        int dz = isX ? 0 : 1;

        int width = 1;

        // Scan left
        int left = 0;
        while (world.getBlockAt(baseX - dx * (left + 1), baseY, baseZ - dz * (left + 1)).getType() == Material.NETHER_PORTAL) {
            left++;
        }

        // Scan right
        int right = 0;
        while (world.getBlockAt(baseX + dx * (right + 1), baseY, baseZ + dz * (right + 1)).getType() == Material.NETHER_PORTAL) {
            right++;
        }

        return left + 1 + right;
    }

    public int measurePortalHeightFromPortalBlock(Location portalBlock) {
        World world = portalBlock.getWorld();
        if (world == null) return 0;

        int baseX = portalBlock.getBlockX();
        int baseY = portalBlock.getBlockY();
        int baseZ = portalBlock.getBlockZ();

        int up = 0;
        while (world.getBlockAt(baseX, baseY + (up + 1), baseZ).getType() == Material.NETHER_PORTAL) {
            up++;
        }

        int down = 0;
        while (world.getBlockAt(baseX, baseY - (down + 1), baseZ).getType() == Material.NETHER_PORTAL) {
            down++;
        }

        return down + 1 + up;
    }



    /**
     * Dynamically detects the width of a portal's obsidian frame from its bottom-left corner.
     */
    public int getFrameWidthFromCorner(Location corner, String orientation) {
        World world = corner.getWorld();
        if (world == null) return 0;

        int width = 1;
        if (orientation.equalsIgnoreCase("X")) {
            while (world.getBlockAt(corner.clone().add(width, 0, 0)).getType() == Material.OBSIDIAN) {
                width++;
            }
        } else {
            while (world.getBlockAt(corner.clone().add(0, 0, width)).getType() == Material.OBSIDIAN) {
                width++;
            }
        }
        return width;
    }

    /**
     * Dynamically detects the height of a portal's obsidian frame from its bottom-left corner.
     */
    public int getFrameHeightFromCorner(Location corner) {
        World world = corner.getWorld();
        if (world == null) return 0;

        int height = 1;
        while (world.getBlockAt(corner.clone().add(0, height, 0)).getType() == Material.OBSIDIAN) {
            height++;
        }
        return height;
	    }
    
    public String getMostRecentLinkCode(UUID uuid) {
        File file = new File(plugin.getDataFolder(), "playerdata/" + uuid.toString() + ".yml");
        if (!file.exists()) return null;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        // Just grab the last key alphabetically for now
        for (String key : links.getKeys(false)) {
            return key; // or use a smarter "latest modified" check if needed
        }

        return null;
    }
    
    public void savePlayerConfig(UUID uuid, YamlConfiguration config) {
        File file = new File(plugin.getDataFolder(), "playerdata/" + uuid.toString() + ".yml");
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("❌ Failed to save config for player: " + uuid);
            e.printStackTrace();
        }
    }

    private boolean isBottomLeftCorner(Location loc) {
        Block base = loc.getBlock();
        int score = 0;

        // Allow up to 2 vertical obsidian
        if (base.getRelative(BlockFace.UP).getType() == Material.OBSIDIAN) score++;
        if (base.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getType() == Material.OBSIDIAN) score++;

        // Allow either side frame (Z or X)
        if (base.getRelative(BlockFace.EAST).getType() == Material.OBSIDIAN) score++;
        if (base.getRelative(BlockFace.SOUTH).getType() == Material.OBSIDIAN) score++;
        if (base.getRelative(BlockFace.WEST).getType() == Material.OBSIDIAN) score++;
        if (base.getRelative(BlockFace.NORTH).getType() == Material.OBSIDIAN) score++;

        // This allows corners even if one frame side is air
        return score >= 2;
    }

 
    public Location findTrueBottomLeftCorner(Location center) {
        if (center == null) return null;
        World world = center.getWorld();
        if (world == null) return null;

        // First try to find a nearby portal block
        Location portalBlock = findNearbyPortalBlock(center, 5);
        if (portalBlock == null) return null;

        // Now scan for the actual frame corner
        PortalFrame frame = scanFullPortalFrame(portalBlock);
        if (frame != null) {
            return frame.bottomLeft;
        }

        // Fallback to searching for obsidian corners
        int radius = 5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Location check = center.clone().add(dx, dy, dz);
                    if (check.getBlock().getType() == Material.OBSIDIAN && isBottomLeftCorner(check)) {
                        return check;
                    }
                }
            }
        }

        return null;
    }
    public boolean hasNearbyConflictingPortal(Location loc, String linkCode, int radius) {
        World world = loc.getWorld();
        if (world == null) return false;

        for (Entity entity : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            String existingCode = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "linkCode"),
                PersistentDataType.STRING
            );

            if (existingCode != null && !existingCode.equals(linkCode)) {
                return true;
            }
        }
        return false;
    }
    
    /*public YamlConfiguration getPortalMap() {
        return globalPortalMap; // or whatever variable holds portalMap.yml content
    }*/
    //private YamlConfiguration globalPortalMap;

    public YamlConfiguration getPortalMap() {
        if (globalPortalMap == null) {
            File file = new File(plugin.getDataFolder(), "portalMap.yml");
            if (!file.exists()) {
                plugin.getLogger().warning("⚠ portalMap.yml not found. Creating new configuration.");
                globalPortalMap = new YamlConfiguration();
                try {
                    file.createNewFile();
                    globalPortalMap.save(file);
                } catch (IOException e) {
                    plugin.getLogger().severe("❌ Failed to create portalMap.yml: " + e.getMessage());
                }
            } else {
                globalPortalMap = YamlConfiguration.loadConfiguration(file);
            }
        }
        return globalPortalMap;
    }

    
    public boolean hasDiamondOverride(Player player) {
        //UUID uuid = player.getUniqueId();
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;

        File file = new File(dataFolder, uuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return false;

        for (String key : links.getKeys(false)) {
            ConfigurationSection link = links.getConfigurationSection(key);
            if (link.getBoolean("diamondoverride", false)) {
                return true;
            }
        }
        return false;
    }

    public Location getDiamondOverrideLocation(Player player) {
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;

        //UUID uuid = player.getUniqueId();
        File file = new File(dataFolder, uuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String key : links.getKeys(false)) {
            ConfigurationSection link = links.getConfigurationSection(key);
            if (!link.getBoolean("diamondoverride", false)) continue;

            String currentWorld = player.getWorld().getName();
            ConfigurationSection dest = link.getConfigurationSection(currentWorld);
            if (dest == null) continue;

            int x = dest.getInt("location.x");
            int y = dest.getInt("location.y");
            int z = dest.getInt("location.z");
            World world = Bukkit.getWorld(currentWorld);
            if (world == null) continue;

            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    public boolean hasPendingPair(Player player) {
        //UUID uuid = player.getUniqueId();
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;

        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) return false;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return false;

        for (String code : links.getKeys(false)) {
            if (config.getBoolean("links." + code + ".pendingPair", false)) {
                return true;
            }
        }
        return false;
    }

    public Location getPendingPairDestination(Player player) {
        //UUID uuid = player.getUniqueId();
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;

        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) return null;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection link = links.getConfigurationSection(code);
            if (link == null || !link.getBoolean("pendingPair", false)) continue;

            for (String worldKey : link.getKeys(false)) {
                if (worldKey.equals("pendingPair") || worldKey.equals("diamondoverride")) continue;

                ConfigurationSection locSec = link.getConfigurationSection(worldKey + ".location");
                if (locSec == null) continue;

                World world = Bukkit.getWorld(worldKey);
                if (world == null) continue;

                double x = locSec.getDouble("x");
                double y = locSec.getDouble("y");
                double z = locSec.getDouble("z");

                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    public boolean isPlayerInsideExpectedPortalFrame(Player player, String linkCode, String worldName) {
        UUID uuid = player.getUniqueId();
        File playerFile = new File(new File(plugin.getDataFolder(), "playerdata"), uuid + ".yml");
        if (!playerFile.exists()) return false;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        ConfigurationSection frameSection = config.getConfigurationSection("links." + linkCode + "." + worldName + ".frame.corner");
        if (frameSection == null) return false;

        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;

        Location frameCorner = loadUniversalLocation(frameSection, world);

        Location playerLoc = player.getLocation();
        if (!playerLoc.getWorld().equals(world)) return false;

        return Math.abs(playerLoc.getBlockX() - frameCorner.getBlockX()) <= 2 &&
               Math.abs(playerLoc.getBlockY() - frameCorner.getBlockY()) <= 3 &&
               Math.abs(playerLoc.getBlockZ() - frameCorner.getBlockZ()) <= 1;
    }


    public Location getPortalLocationByLinkCode(String linkCode, String worldName) {
        Portal portal = loadPortal(linkCode, worldName);
        if (portal != null) {
            return portal.getLocation();
        }
        return null;
    }

    public void forcePlayerToCorrectPortal(Player player, String linkCode, String worldName) {
        Location expected = getPortalLocationByLinkCode(linkCode, worldName);
        if (expected == null) {
            plugin.getLogger().warning("⚠ No saved location for linkCode: " + linkCode + ", world: " + worldName);
            return;
        }

        // ✅ Store the linkCode so we don't re-scan after teleport
        plugin.getLogger().info("✅ [Correction] Storing pending linkCode: " + linkCode);
        storePendingLink(player.getUniqueId(), linkCode);

        // 🧭 Safe teleport slightly above the portal frame
        Location safe = expected.clone().add(0.5, 1, 0.5);
        player.teleport(safe);
        player.sendMessage("§e[Portal Correction] You were repositioned to your correct portal.");
    }

    public @Nullable String resolveLinkCodeByMarkerScan(PortalFrame frame) {
        if (frame == null || frame.bottomLeft == null) return null;

        World world = frame.bottomLeft.getWorld();
        if (world == null) return null;

        int minX = frame.bottomLeft.getBlockX();
        int minY = frame.bottomLeft.getBlockY();
        int minZ = frame.bottomLeft.getBlockZ();

        int maxX = minX + (frame.orientation.equals("X") ? frame.width - 1 : 0);
        int maxY = minY + frame.height - 1;
        int maxZ = minZ + (frame.orientation.equals("Z") ? frame.width - 1 : 0);

        // Loop through all entities in this region
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location loc = new Location(world, x + 0.5, y + 0.5, z + 0.5);
                    for (Entity entity : world.getNearbyEntities(loc, 0.25, 0.25, 0.25)) {
                        if (entity instanceof ArmorStand armor && armor.getScoreboardTags().contains("gnp_marker")) {
                            PersistentDataContainer data = armor.getPersistentDataContainer();
                            String code = data.get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
                            if (code != null) return code;
                        }
                    }
                }
            }
        }

        return null;
    }


    public String getLinkFromRegion(Location loc) {
        return portalEntryZones.get(loc);
    }

    public void storePendingLink(UUID uuid, String linkCode) {
        playerPendingLinks.put(uuid, linkCode);
    }

    public void clearPendingLink(UUID uuid) {
        playerPendingLinks.remove(uuid);
    }

    public String getPendingLink(UUID uuid) {
        return playerPendingLinks.get(uuid);
    }

    public void previewEntryZones(Player player) {
        for (Map.Entry<Location, String> entry : portalEntryZones.entrySet()) {
            Location loc = entry.getKey();

            Location centered = loc.clone().add(0.5, 0.5, 0.5);
            drawOutlineCube(player, centered, 1.0, Particle.FLAME);


            // Optional debug message
            player.sendMessage("§b[Zone] " + formatLoc(loc) + " → LinkCode: §e" + entry.getValue());
        }
    }
    private void drawOutlineCube(Player player, Location center, double size, Particle type) {
        double half = size / 2.0;
        World world = center.getWorld();

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks++ > 60) cancel(); // lasts 3 seconds (20 ticks/sec)

                for (double x = -half; x <= half; x += size) {
                    for (double y = -half; y <= half; y += size / 4) {
                        for (double z = -half; z <= half; z += size) {
                            Location edge = center.clone().add(x, y, z);
                            spawnColoredParticle(player, edge);
                        }
                    }
                }

                for (double y = -half; y <= half; y += size / 4) {
                    for (double x = -half; x <= half; x += size / 4) {
                        for (double z : new double[]{-half, half}) {
                            Location edge = center.clone().add(x, y, z);
                            spawnColoredParticle(player, edge);
                        }
                    }
                }

            }
        }.runTaskTimer(plugin, 0L, 5L); // runs every 5 ticks (~0.25s)
    }
    private void spawnColoredParticle(Player player, Location loc) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.FUCHSIA, 1.5F);
        player.spawnParticle(Particle.FLAME, loc, 1, 0, 0, 0, 0, dust);
    }
    
    public List<DetectionRegion> getDetectionRegions() {
        return detectionRegions;
    }

    public Location parseCoord(String coordString, String worldName) {
        Location loc = parseCoord(coordString);
        if (loc != null) {
            loc.setWorld(Bukkit.getWorld(worldName));
        }
        return loc;
    }

    public void loadAllInteriorRegionsFromFiles() {
        detectionRegions.clear(); // reset before reload

        File folder = new File(plugin.getDataFolder(), "playerdata");
        if (!folder.exists() || !folder.isDirectory()) return;

        for (File file : folder.listFiles((dir, name) -> name.endsWith(".yml"))) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String playerName = config.getString("name");
            if (playerName == null) continue;

            ConfigurationSection links = config.getConfigurationSection("links");
            if (links == null) continue;

            for (String linkCode : links.getKeys(false)) {
                ConfigurationSection code = links.getConfigurationSection(linkCode);
                for (String worldName : code.getKeys(false)) {
                    ConfigurationSection worldSec = code.getConfigurationSection(worldName);
                    if (worldSec == null) continue;

                    ConfigurationSection interior = worldSec.getConfigurationSection("interior");
                    if (interior == null) continue;

                    Location min = parseCoord(interior.getString("min"), worldName);
                    Location max = parseCoord(interior.getString("max"), worldName);
                    if (min != null && max != null) {
                        DetectionRegion region = new DetectionRegion(min, max, linkCode);
                        detectionRegions.add(region);
                        plugin.debugLog("🟩 Loaded region from file: " + formatLoc(min) + " → " + formatLoc(max));
                    }
                }
            }
        }
    }
    
    public void reloadPortalMap() {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        if (!file.exists()) {
            plugin.debugLog("⚠ portalMap.yml not found — creating a new one.");
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.debugLog("❌ Failed to create portalMap.yml");
                e.printStackTrace();
                return;
            }
        }

        this.globalPortalMap = YamlConfiguration.loadConfiguration(file);
        plugin.debugLog("🔄 Reloaded portalMap.yml into memory.");
    }

    private final Map<UUID, Boolean> exitCooldown = new HashMap<>();

    public void markExitCooldown(UUID uuid) {
        exitCooldown.put(uuid, true);
    }

    public boolean isExitCooldownActive(UUID uuid) {
        return exitCooldown.getOrDefault(uuid, false);
    }

    public void clearExitCooldown(UUID uuid) {
        exitCooldown.remove(uuid);
    }

    
    public PortalFrame getFrameByLinkCode(String linkCode, World world) {
        if (linkCode == null || world == null) {
            plugin.debugLog("⚠ getFrameByLinkCode was called with null arguments.");
            return null;
        }

        String worldName = world.getName();
        plugin.debugLog("[FRAME-DEBUG] Looking up frame for linkCode: " + linkCode + " in world: " + worldName);

        YamlConfiguration portalMap = getPortalMap();
        if (!portalMap.contains(linkCode + ".owner")) {
            plugin.debugLog("⚠ LinkCode " + linkCode + " does not exist in portalMap.");
            return null;
        }

        UUID ownerUUID = UUID.fromString(portalMap.getString(linkCode + ".owner"));
        YamlConfiguration config = getOrCreatePlayerConfig(ownerUUID);

        String base = "links." + linkCode + "." + worldName + ".frame";
        if (!config.contains(base + ".orientation")) {
            plugin.debugLog("⚠ Frame orientation missing at: " + base + ".orientation");
            return null;
        }

        String orientation = config.getString(base + ".orientation");
        ConfigurationSection cornerSec = config.getConfigurationSection(base + ".corner");
        if (cornerSec == null) {
            plugin.debugLog("⚠ Frame corner section missing at: " + base + ".corner");
            return null;
        }

        // Parse corners
        String rawBL = cornerSec.getString("Bottom-Left");
        String rawBR = cornerSec.getString("Bottom-Right");
        String rawTL = cornerSec.getString("Top-Left");
        String rawTR = cornerSec.getString("Top-Right");

        plugin.debugLog("[FRAME-DEBUG] Parsed corner strings:");
        plugin.debugLog("  Bottom-Left: " + rawBL);
        plugin.debugLog("  Bottom-Right: " + rawBR);
        plugin.debugLog("  Top-Left: " + rawTL);
        plugin.debugLog("  Top-Right: " + rawTR);

        Location bottomLeft = parseLocationString(world, rawBL);
        Location bottomRight = parseLocationString(world, rawBR);
        Location topLeft = parseLocationString(world, rawTL);
        Location topRight = parseLocationString(world, rawTR);

        if (bottomLeft == null || bottomRight == null || topLeft == null || topRight == null) {
            plugin.debugLog("⚠ One or more portal corners could not be parsed for linkCode: " + linkCode);
            return null;
        }

        // Calculate dimensions
        int width, height;
        if (orientation.equalsIgnoreCase("X")) {
            width = Math.abs(bottomRight.getBlockX() - bottomLeft.getBlockX()) + 1;
            height = Math.abs(topLeft.getBlockY() - bottomLeft.getBlockY()) + 1;
        } else {
            width = Math.abs(bottomRight.getBlockZ() - bottomLeft.getBlockZ()) + 1;
            height = Math.abs(topLeft.getBlockY() - bottomLeft.getBlockY()) + 1;
        }

        plugin.debugLog("[FRAME-DEBUG] Final frame: " + width + "x" + height + " (" + orientation + ")");
        plugin.debugLog("[FRAME-DEBUG] Frame BL location: " + bottomLeft);

        return new PortalFrame(width, height, orientation, bottomLeft, bottomRight, topLeft, topRight, new ArrayList<Block>(), 0);
    }

    private Location parseLocationString(World world, String raw) {
        if (raw == null || world == null) return null;
        try {
            String[] parts = raw.split(",");
            if (parts.length != 3) return null;
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            return new Location(world, x, y, z);
        } catch (Exception e) {
            plugin.debugLog("⚠ Failed to parse location string: " + raw);
            return null;
        }
    }

    public Location getDestinationFromLink(String linkCode, World currentWorld) {
        if (linkCode == null || currentWorld == null) return null;

        String currentWorldName = currentWorld.getName();
        YamlConfiguration portalMap = getPortalMap();
        if (!portalMap.contains(linkCode + ".owner")) return null;

        UUID ownerUUID = UUID.fromString(portalMap.getString(linkCode + ".owner"));
        YamlConfiguration config = getOrCreatePlayerConfig(ownerUUID);

        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) return null;

        String targetWorldName = null;
        for (String worldKey : linkSection.getKeys(false)) {
            if (!worldKey.equalsIgnoreCase(currentWorldName) && !worldKey.equalsIgnoreCase("pendingPair")) {
                targetWorldName = worldKey;
                break;
            }
        }

        if (targetWorldName == null) return null;

        // Look for interior first
        String interiorPath = "links." + linkCode + "." + targetWorldName + ".interior.min";
        String framePath = "links." + linkCode + "." + targetWorldName + ".frame.corner.Bottom-Left";

        World targetWorld = Bukkit.getWorld(targetWorldName);
        if (targetWorld == null) return null;

        String rawLoc = config.getString(interiorPath);
        if (rawLoc != null) {
            Location interior = parseLocationString(targetWorld, rawLoc);
            if (interior != null) return interior.add(0.5, 0, 0.5); // center it
        }

        rawLoc = config.getString(framePath);
        if (rawLoc != null) {
            Location fallback = parseLocationString(targetWorld, rawLoc);
            if (fallback != null) return fallback.add(0.5, 1, 0.5); // centered, slightly up
        }

        return null;
    }

    public Location getLinkBlockLocation(UUID owner, String worldName) {
        YamlConfiguration config = getOrCreatePlayerConfig(owner);
        String portalsPath = "portals";
        if (!config.contains(portalsPath)) return null;

        ConfigurationSection portalsSection = config.getConfigurationSection(portalsPath);
        List<String> linkBlockPortalCodes = new ArrayList<>();

        for (String linkCode : portalsSection.getKeys(false)) {
            String worldPath = portalsPath + "." + linkCode + ".worlds." + worldName;
            if (config.getBoolean(worldPath + ".diamondOverride", false)) {
                linkBlockPortalCodes.add(linkCode);
            }
        }

        if (linkBlockPortalCodes.isEmpty()) {
            return null;
        }

        String chosenLinkCode;
        if (linkBlockPortalCodes.size() == 1) {
            chosenLinkCode = linkBlockPortalCodes.get(0);
        } else {
            chosenLinkCode = linkBlockPortalCodes.get(plugin.getRandom().nextInt(linkBlockPortalCodes.size()));
        }

        Portal portal = loadPortal(chosenLinkCode, worldName);
        if (portal != null && portal.getFrame() != null) {
             return getSafeTeleportLocation(portal.getFrame().bottomLeft, portal.getFrame().width, portal.getFrame().height, portal.getFrame().orientation);
        } else if (portal != null) {
            return portal.getLocation().clone().add(0.5, 1, 0.5); // Fallback
        }

        return null;
    }

    public void linkPortals(String linkCode1, String linkCode2, CommandSender sender) {
        UUID owner1UUID = getPortalOwner(linkCode1);
        UUID owner2UUID = getPortalOwner(linkCode2);

        if (owner1UUID == null) {
            sender.sendMessage("§cLink code '" + linkCode1 + "' not found.");
            return;
        }
        if (owner2UUID == null) {
            sender.sendMessage("§cLink code '" + linkCode2 + "' not found.");
            return;
        }

        if (!owner1UUID.equals(owner2UUID)) {
            sender.sendMessage("§cPortals must have the same owner to be linked.");
            return;
        }

        File playerFile = new File(dataFolder, owner1UUID.toString() + ".yml");
        if (!playerFile.exists()) {
            sender.sendMessage("§cPlayer data file not found for owner.");
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

        String path1 = "portals." + linkCode1;
        String path2 = "portals." + linkCode2;

        if (!config.contains(path1)) {
            sender.sendMessage("§cPortal data for " + linkCode1 + " not found in owner's file.");
            return;
        }
        if (!config.contains(path2)) {
            sender.sendMessage("§cPortal data for " + linkCode2 + " not found in owner's file.");
            return;
        }

        ConfigurationSection worlds2 = config.getConfigurationSection(path2 + ".worlds");
        if (worlds2 == null) {
            sender.sendMessage("§cNo worlds found for " + linkCode2 + " to merge.");
            return;
        }

        for (String worldName : worlds2.getKeys(false)) {
            ConfigurationSection worldData = worlds2.getConfigurationSection(worldName);
            config.set(path1 + ".worlds." + worldName, worldData);
        }

        config.set(path2, null);

        try {
            config.save(playerFile);

            YamlConfiguration globalConfig = plugin.getGlobalPortalMap();
            globalConfig.set(linkCode2, null);
            plugin.saveGlobalMap();

            portalCache.keySet().removeIf(key -> key.startsWith(linkCode1 + ":") || key.startsWith(linkCode2 + ":"));

            sender.sendMessage("§aSuccessfully linked " + linkCode2 + " into " + linkCode1 + ".");
        } catch (IOException e) {
            sender.sendMessage("§cAn error occurred while saving portal data.");
            e.printStackTrace();
        }
    }

    public void deletePortal(String linkCode, CommandSender sender) {
        UUID ownerUUID = getPortalOwner(linkCode);
        if (ownerUUID == null) {
            sender.sendMessage("§cPortal with link code '" + linkCode + "' not found in global map.");
            return;
        }

        File playerFile = new File(dataFolder, ownerUUID.toString() + ".yml");
        if (!playerFile.exists()) {
            sender.sendMessage("§cOwner data file not found for portal " + linkCode);
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        String linkCodeWorldsPath = "portals." + linkCode + ".worlds";

        if (!config.contains(linkCodeWorldsPath)) {
            sender.sendMessage("§cCould not find any worlds for link code " + linkCode + " in player data.");
            return;
        }

        ConfigurationSection worldsSection = config.getConfigurationSection(linkCodeWorldsPath);
        Set<String> worldNames = worldsSection.getKeys(false);
        int deleteCount = 0;

        for (String worldName : worldNames) {
            Portal portal = loadPortal(linkCode, worldName);
            if (portal != null) {
                backupPortal(portal);
                removeMarker(linkCode, portal.getWorld());
                if (portal.getFrame() != null) {
                    clearObsidianFrame(portal.getFrame());
                }
                portalCache.remove(linkCode + ":" + worldName);
                sender.sendMessage("§aDeleted portal " + linkCode + " in world " + portal.getWorld().getName());
                deleteCount++;
            }
        }

        if (deleteCount > 0) {
            config.set("portals." + linkCode, null);
            try {
                config.save(playerFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

            YamlConfiguration globalConfig = plugin.getGlobalPortalMap();
            globalConfig.set(linkCode, null);
            plugin.saveGlobalMap();

            sender.sendMessage("§aSuccessfully deleted link code " + linkCode + " and its associated portals.");
        } else {
            sender.sendMessage("§eNo portals were deleted for link code " + linkCode + ". It might be corrupted.");
        }
    }

    public List<String> getBackupFiles() {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = backupDir.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(files)
                     .map(File::getName)
                     .filter(name -> name.endsWith(".yml"))
                     .collect(Collectors.toList());
    }

    private void backupPortal(Portal portal) {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        File backupFile = new File(backupDir, portal.getLinkCode() + "-" + portal.getWorld().getName() + ".yml");
        YamlConfiguration backupConfig = new YamlConfiguration();

        String basePath = "portal";
        backupConfig.set(basePath + ".linkCode", portal.getLinkCode());
        backupConfig.set(basePath + ".owner", portal.getOwner().toString());
        backupConfig.set(basePath + ".world", portal.getWorld().getName());
        backupConfig.set(basePath + ".location.x", portal.getLocation().getX());
        backupConfig.set(basePath + ".location.y", portal.getLocation().getY());
        backupConfig.set(basePath + ".location.z", portal.getLocation().getZ());
        backupConfig.set(basePath + ".diamondOverride", portal.hasDiamondOverride());

        if (portal.getFrame() != null) {
            backupConfig.set(basePath + ".frame.orientation", portal.getFrame().orientation);
            backupConfig.set(basePath + ".frame.width", portal.getFrame().width);
            backupConfig.set(basePath + ".frame.height", portal.getFrame().height);
            backupConfig.set(basePath + ".frame.bottomLeft", formatCoord(portal.getFrame().bottomLeft));
        }

        try {
            backupConfig.save(backupFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not backup portal " + portal.getLinkCode());
            e.printStackTrace();
        }
    }

    private void removeMarker(String linkCode, World world) {
        for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
            if (entity.getScoreboardTags().contains("gnp_marker")) {
                String code = entity.getPersistentDataContainer().get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
                if (linkCode.equals(code)) {
                    entity.remove();
                    return;
                }
            }
        }
    }

    private void clearPortalBlocks(PortalFrame frame) {
        if (frame == null || frame.bottomLeft == null || frame.bottomLeft.getWorld() == null) return;
        World world = frame.bottomLeft.getWorld();
        int dx = frame.orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = frame.orientation.equalsIgnoreCase("Z") ? 1 : 0;

        int innerWidth = frame.width - 2;
        int innerHeight = frame.height - 2;
        Location start = frame.bottomLeft.clone().add(dx, 1, dz);

        for (int w = 0; w < innerWidth; w++) {
            for (int h = 0; h < innerHeight; h++) {
                Location loc = start.clone().add(dx * w, h, dz * w);
                if (loc.getBlock().getType() == Material.NETHER_PORTAL) {
                    loc.getBlock().setType(Material.AIR);
                }
            }
        }
    }


    private Location getLocationFromSection(World world, ConfigurationSection sec) {
        if (sec == null || world == null) return null;
        return new Location(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"));
    }

    public String generateUniqueLinkCode() {
        String code;
        do {
            code = UUID.randomUUID().toString().substring(0, 6);
        } while (getPortalMap().contains(code));
        return code;
    }
    public String getFirstWorldForLink(String linkCode, UUID uuid) {
    	File file = new File(plugin.getDataFolder(), "playerdata/" + uuid.toString() + ".yml");
        if (!file.exists()) return null;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) return null;

        for (String key : linkSection.getKeys(false)) {
            if (!key.equalsIgnoreCase("pendingPair") && !key.equalsIgnoreCase("diamondoverride")) {
                return key; // This is the first world that saved a portal
            }
        }

        return null;
    }

    
    private static class MarkerTask {
        final PortalFrame frame;
        final String linkCode;
        final UUID uuid;

        MarkerTask(PortalFrame frame, String linkCode, UUID uuid) {
            this.frame = frame;
            this.linkCode = linkCode;
            this.uuid = uuid;
        }
    }
    public void queueMarkerSpawn(PortalFrame frame, String linkCode, UUID uuid) {
        markerQueue.add(new MarkerTask(frame, linkCode, uuid));
        trySpawnNextMarker();
    }
    private void trySpawnNextMarker() {
        if (isMarkerSpawning || markerQueue.isEmpty()) return;

        isMarkerSpawning = true;
        MarkerTask task = markerQueue.poll();

        // Delay to allow full block update and chunk settle
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            spawnPortalMarker(task.frame, task.linkCode, task.uuid);
            isMarkerSpawning = false;

            // Call next one
            trySpawnNextMarker();
        }, 2L); // Delay between marker spawns
    }

    public void removeScanCooldownKey(Location loc) {
        String key = loc.getWorld().getName() + "::" +
                     loc.getBlockX() + "," +
                     loc.getBlockY() + "," +
                     loc.getBlockZ();
        recentScanLocations.remove(key);
    }

    public void loadFromConfig(UUID uuid, YamlConfiguration config) {
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return;

        for (String linkCode : links.getKeys(false)) {
            ConfigurationSection linkSection = links.getConfigurationSection(linkCode);
            if (linkSection == null) continue;

            for (String worldName : linkSection.getKeys(false)) {
                ConfigurationSection worldSection = linkSection.getConfigurationSection(worldName);
                if (worldSection == null) continue;

                String minString = worldSection.getString("interior.min");
                String maxString = worldSection.getString("interior.max");

                if (minString == null || maxString == null) continue;

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.debugLog("⚠ World not found for portal region: " + worldName);
                    continue;
                }

                Location min = parseCoord(minString);
                Location max = parseCoord(maxString);

                if (min == null || max == null) {
                    plugin.debugLog("⚠ Failed to parse interior bounds for link: " + linkCode);
                    continue;
                }

                min.setWorld(world);
                max.setWorld(world);

                DetectionRegion region = new DetectionRegion(min, max, linkCode);
                detectionRegions.add(region);

                plugin.debugLog("✅ Reloaded portal zone for " + linkCode + " in world " + worldName);
            }
        }
    }

    public void setRecentPortalEntry(UUID playerId, String linkCode) {
        recentPortalEntries.put(playerId, linkCode);
    }

    public @Nullable String consumeRecentPortalEntry(UUID playerId) {
        return recentPortalEntries.remove(playerId);
    }

    public void teleportPlayerToLinkedPortal(Player player, String linkCode) {
        if (linkCode == null || player == null) return;

        UUID uuid = player.getUniqueId();
        YamlConfiguration playerData = getOrCreatePlayerConfig(uuid);

        World currentWorld = player.getWorld();
        String currentWorldName = currentWorld.getName();

        // 🎯 NEW: Read from portalA/portalB direction tags
        String portalA = playerData.getString("links." + linkCode + ".portalA");
        String portalB = playerData.getString("links." + linkCode + ".portalB");

        if (portalA == null || portalB == null) {
            plugin.debugLog("⚠️ Missing portalA or portalB for linkCode: " + linkCode);
            return;
        }

        String destinationWorld;
        if (currentWorldName.equals(portalA)) {
            destinationWorld = portalB;
        } else if (currentWorldName.equals(portalB)) {
            destinationWorld = portalA;
        } else {
            plugin.debugLog("⚠️ Current world not listed in portalA or portalB for linkCode: " + linkCode);
            return;
        }

        String baseKey = "links." + linkCode + "." + destinationWorld + ".location";

        if (!playerData.contains(baseKey)) {
            plugin.debugLog("⚠️ No saved return location for player in destination world: " + destinationWorld + " under linkCode: " + linkCode);
            plugin.debugLog("🔍 baseKey: " + baseKey);

            ConfigurationSection linkSection = playerData.getConfigurationSection("links." + linkCode);
            if (linkSection != null) {
                plugin.debugLog("📖 Available worlds under link: " + linkSection.getKeys(false));
                ConfigurationSection destSection = linkSection.getConfigurationSection(destinationWorld);
                if (destSection != null) {
                    plugin.debugLog("📦 Keys under destination world: " + destSection.getKeys(false));
                } else {
                    plugin.debugLog("❌ Could not find section: " + destinationWorld);
                }
            }
            return;
        }

        Location destination = deserializeLocation(playerData, baseKey);

        if (destination != null) {
            plugin.debugLog("🌍 Current world: " + currentWorldName);
            plugin.debugLog("🧭 Destination world: " + destinationWorld);
            plugin.debugLog("🔗 linkCode: " + linkCode);
            plugin.debugLog("🚀 Teleporting player " + player.getName() + " using linkCode: " + linkCode);

            try {
                player.getClass().getMethod("teleportAsync", Location.class); // reflection test
                player.getClass().getMethod("teleportAsync", Location.class)
                      .invoke(player, destination);
            } catch (Exception e) {
                player.teleport(destination); // fallback to sync
            }

        } else {
            plugin.debugLog("❌ Failed to deserialize destination for linkCode: " + linkCode);
        }
    }

    public Location deserializeLocation(YamlConfiguration config, String path) {
        if (!config.contains(path)) return null;

        String worldName = config.getString(path + ".world");
        if (worldName == null) {
            plugin.debugLog("❌ deserializeLocation() failed — world name is null at: " + path);
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.debugLog("❌ deserializeLocation() failed — world '" + worldName + "' is not loaded.");
            return null;
        }

        double x = config.getDouble(path + ".x");
        double y = config.getDouble(path + ".y");
        double z = config.getDouble(path + ".z");
        float yaw = (float) config.getDouble(path + ".yaw");
        float pitch = (float) config.getDouble(path + ".pitch");

        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

	 // ─────────────────────────────────────────────────────────────
	 // Paired build orchestration: run now if world is loaded, else defer
	 // ─────────────────────────────────────────────────────────────
	private String chooseDestinationWorld(String originWorldName) {
	    String mapped = plugin.getConfig().getString("world-pairs." + originWorldName);
	    if (mapped != null && !mapped.isBlank()) return mapped;
	    try {
	        String alt = plugin.getOppositeWorld(originWorldName);
	        if (alt != null && !alt.isBlank()) return alt;
	    } catch (Throwable ignored) {}
	    return null;
	}

	public void requestPairedPortalBuild(String linkCode,
	                                     String originWorldName,
	                                     java.util.UUID uuid,
	                                     PortalFrame frame) {
	
	    // decide the destination world from the origin, with a safe fallback
	    final String destWorldName = (plugin.getOppositeWorld(originWorldName) != null)
	            ? plugin.getOppositeWorld(originWorldName)
	            : originWorldName;
	
	    enqueue(() -> {
	        final String buildKey = linkCode + "|" + destWorldName;
	        if (!buildInFlight.add(buildKey)) {
	            // another queued job is already building this B-side
	            return;
	        }
	        try {
	            org.bukkit.World destW = org.bukkit.Bukkit.getWorld(destWorldName);
	            if (destW == null) {
	                plugin.debugLog("[GNP] Destination world not loaded: " + destWorldName);
	                return;
	            }
	
	            // Compute destination corner using your existing logic
	            org.bukkit.Location destCorner = computeOrFindDestinationCorner(
	                    linkCode, originWorldName, destW, frame
	            );
	
	            // Idempotence check: skip if a region already covering destCorner exists for this linkCode
	            boolean alreadyRegistered = false;
	            for (DetectionRegion r : detectionRegions) {
	                if (!r.contains(destCorner)) continue;
	
	                String rc = null;
	                try {
	                    rc = (String) r.getClass().getMethod("getLinkCode").invoke(r);
	                } catch (Throwable ignore) {
	                    try {
	                        java.lang.reflect.Field f = r.getClass().getDeclaredField("linkCode");
	                        f.setAccessible(true);
	                        rc = (String) f.get(r);
	                    } catch (Throwable ignore2) {
	                        // no-op
	                    }
	                }
	                if (rc != null && rc.equals(linkCode)) {
	                    alreadyRegistered = true;
	                    break;
	                }
	            }
	            if (alreadyRegistered) return;
	
	            // Ensure a portal exists at the destination
	            org.bukkit.Location formed = findNearbyPortalBlock(destCorner, 4);
	            if (formed == null) {
	                // Force-create the B-side frame+portal (use your existing builder)
	                forceCreatePortal(destCorner, linkCode, uuid);
	                formed = findNearbyPortalBlock(destCorner, 4);
	            }
	            if (formed == null) {
	                plugin.debugLog("[GNP] Could not verify B portal for " + linkCode
	                        + " in " + destW.getName());
	                return;
	            }
	
	            // Final re-scan + marker + detection region persist
	            java.util.Set<org.bukkit.Location> visited = new java.util.HashSet<>();
	            PortalFrame newFrame = scanFullPortalFrame(formed, visited, false, false);
	            if (newFrame == null) {
	                plugin.debugLog("[GNP] B-side scan failed after creation for " + linkCode);
	                return;
	            }
	
	            spawnPortalMarker(newFrame, linkCode, uuid);
	            registerDetectionFor(linkCode, uuid, newFrame);
	
	            // (Removed addPortalLinkToMap — undefined / redundant)
	            plugin.debugLog("✅ [PAIR] Portal-B registered for " + linkCode
	                    + " at " + formatLoc(newFrame.bottomLeft));
	
	        } catch (Throwable t) {
	            plugin.debugLog("[GNP] requestPairedPortalBuild failed: " + t.getMessage());
	            t.printStackTrace();
	        } finally {
	            buildInFlight.remove(buildKey);
	        }
	    });
	}

	private final java.util.Map<String, java.util.List<DeferredBuild>> deferred = new java.util.concurrent.ConcurrentHashMap<>();
	
	private static final class DeferredBuild {
		    final String linkCode;
		    final String originWorld;
		    final java.util.UUID uuid;
		    final PortalFrame frame;
		    DeferredBuild(String c, String ow, java.util.UUID u, PortalFrame f) {
		        this.linkCode = c; this.originWorld = ow; this.uuid = u; this.frame = f;
		    }
	}
	
	private void deferUntilWorldLoads(String destWorldName, String linkCode, String originWorldName, java.util.UUID uuid, PortalFrame frame) {
		 deferred.computeIfAbsent(destWorldName, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new DeferredBuild(linkCode, originWorldName, uuid, frame));
	}

	
	 /** Called by main plugin on WorldLoadEvent */
    public void forceCreatePortal(Location loc, String linkCode, UUID owner) {
        buildBasicPortalFrame(loc);
    }

    public void forceGeneratePairedPortal(String linkCode, String originWorld, UUID uuid, PortalFrame frame) {
        requestPairedPortalBuild(linkCode, originWorld, uuid, frame);
    }

	 public void onWorldLoadedKickDeferredBuilds(String worldNameJustLoaded) {
	     var list = deferred.remove(worldNameJustLoaded);
	     if (list == null || list.isEmpty()) return;
	     plugin.debugLog("[GNP] Running " + list.size() + " deferred paired builds for world " + worldNameJustLoaded);
	     for (DeferredBuild db : list) {
	         org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
	             try {
	            	 forceGeneratePairedPortal(db.linkCode, db.originWorld, db.uuid, db.frame);
	             } catch (Exception ex) {
	                 plugin.debugLog("[GNP] Deferred build failed (" + db.linkCode + "): " + ex.getMessage());
	                 ex.printStackTrace();
	             }
	         });
	     }
	 }

	 private org.bukkit.World.Environment resolveEnvironmentForWorld(String originWorldName, String destWorldName) {
		    // Optional explicit mapping in config: world-env.<worldName>: NORMAL|NETHER|THE_END
		    String cfg = plugin.getConfig().getString("world-env." + destWorldName);
		    if (cfg != null) {
		        try { return org.bukkit.World.Environment.valueOf(cfg.toUpperCase(java.util.Locale.ROOT)); }
		        catch (IllegalArgumentException ignored) {}
		    }
		    // Infer from common names
		    String n = destWorldName.toLowerCase(java.util.Locale.ROOT);
		    if (n.contains("nether")) return org.bukkit.World.Environment.NETHER;
		    if (n.contains("the_end") || n.endsWith("_end") || n.equals("end")) return org.bukkit.World.Environment.THE_END;
		    return org.bukkit.World.Environment.NORMAL;
	}
	
	 public void registerDetectionFor(String linkCode, java.util.UUID uuid, PortalFrame frame) {
		    int minX = Math.min(frame.bottomLeft.getBlockX(), frame.bottomRight.getBlockX());
		    int maxX = Math.max(frame.bottomLeft.getBlockX(), frame.bottomRight.getBlockX());
		    int minZ = Math.min(frame.bottomLeft.getBlockZ(), frame.bottomRight.getBlockZ());
		    int maxZ = Math.max(frame.bottomLeft.getBlockZ(), frame.bottomRight.getBlockZ());
		    int minY = frame.bottomLeft.getBlockY();
		    int maxY = frame.topLeft.getBlockY();

		    int expandX = 0, expandZ = 0;
		    if ("X".equalsIgnoreCase(frame.orientation)) expandZ = 1; else expandX = 1;

		    org.bukkit.Location min = new org.bukkit.Location(
		        frame.bottomLeft.getWorld(), minX, minY, minZ).clone().subtract(expandX, 0, expandZ);
		    org.bukkit.Location max = new org.bukkit.Location(
		        frame.topRight.getWorld(),  maxX, maxY, maxZ).clone().add(expandX, 0, expandZ);

		    // in-memory registration for entry checks
		    detectionRegions.add(new DetectionRegion(min, max, linkCode));

		    // persist to YAML so it survives restarts
		    YamlConfiguration cfg = getOrCreatePlayerConfig(uuid);
		    String world = frame.bottomLeft.getWorld().getName();
		    ConfigurationSection dest = cfg.getConfigurationSection("links." + linkCode + "." + world);
		    if (dest == null) dest = cfg.createSection("links." + linkCode + "." + world);

		    ConfigurationSection interior = dest.getConfigurationSection("interior");
		    if (interior == null) interior = dest.createSection("interior");
		    interior.set("min", formatCoord(min));
		    interior.set("max", formatCoord(max));

		    try {
		        java.io.File file = new java.io.File(new java.io.File(plugin.getDataFolder(), "playerdata"), uuid.toString() + ".yml");
		        cfg.save(file);
		    } catch (java.io.IOException e) {
		        plugin.debugLog("[GNP] Failed to save A-side interior for " + linkCode + ": " + e.getMessage());
		    }

		    addPortalToGlobalMap(linkCode, uuid);
		}

	// === INSERT (bottom) — task queue + helpers ===

	 /** Single-threaded worker (main-thread) to serialize portal ops. */
	 private final java.util.ArrayDeque<Runnable> gnpWorkQueue = new java.util.ArrayDeque<>();
	 private boolean gnpWorkerRunning = false;

	 /** Pair-build guard so we don't create duplicate B-sides. */
	 private final java.util.Set<String> buildInFlight = java.util.Collections.newSetFromMap(
	         new java.util.concurrent.ConcurrentHashMap<>()
	 );

	 /** Last detected link per player during entry/use. */
	 private final java.util.Map<java.util.UUID, String> lastDetectedLink =
	         new java.util.concurrent.ConcurrentHashMap<>();

	 /** Enqueue work to run on the primary thread in FIFO order. */
	 private void enqueue(Runnable r) {
	     synchronized (gnpWorkQueue) {
	         gnpWorkQueue.addLast(r);
	         if (!gnpWorkerRunning) {
	             gnpWorkerRunning = true;
	             org.bukkit.Bukkit.getScheduler().runTask(plugin, this::drainQueue);
	         }
	     }
	 }

	 private void drainQueue() {
	     Runnable job;
	     while (true) {
	         synchronized (gnpWorkQueue) {
	             job = gnpWorkQueue.pollFirst();
	             if (job == null) { gnpWorkerRunning = false; break; }
	         }
	         try { job.run(); } catch (Throwable t) {
	             plugin.debugLog("[GNP] Queue job failed: " + t.getMessage());
	             t.printStackTrace();
	         }
	     }
	 }

	// Fast link lookup at a location: region index → marker ArmorStand fallback.
	 public String findLinkCodeAt(org.bukkit.Location loc) {
	     if (loc == null || loc.getWorld() == null) return null;

	     // 1) detectionRegions first (use contains(Location))
	     for (DetectionRegion r : detectionRegions) {
	         if (r.contains(loc)) {
	             // If your DetectionRegion uses fields instead of getters, change to: r.linkCode
	             String code;
	             try { code = r.getLinkCode(); }
	             catch (NoSuchMethodError | RuntimeException e) { 
	                 // fallback if you expose a public field
	                 try { code = (String) DetectionRegion.class.getField("linkCode").get(r); }
	                 catch (Throwable ignore) { code = null; }
	             }
	             if (code != null && !code.isBlank()) return code;
	         }
	     }

	     // 2) marker fallback (tight box)
	     org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(
	             loc.clone().add(-1.5, -2.0, -1.5),
	             loc.clone().add( 1.5,  2.0,  1.5)
	     );
	     for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(box)) {
	         if (!(e instanceof org.bukkit.entity.ArmorStand stand)) continue;
	         if (!stand.getScoreboardTags().contains("gnp_marker")) continue;
	         String code = stand.getPersistentDataContainer().get(
	                 new org.bukkit.NamespacedKey(plugin, "linkCode"),
	                 org.bukkit.persistence.PersistentDataType.STRING
	         );
	         if (code != null && !code.isBlank()) return code;
	     }
	     return null;
	 }


	 public void setLastDetectedLink(java.util.UUID uuid, String linkCode) {
	     if (uuid == null || linkCode == null || linkCode.isBlank()) return;
	     lastDetectedLink.put(uuid, linkCode);
	 }
	 // === INSERT (END)
	// === INSERT — computeOrFindDestinationCorner (bottom of class)
	public org.bukkit.Location computeOrFindDestinationCorner(String linkCode,
	                                                          String originWorldName,
	                                                          org.bukkit.World destWorld,
	                                                          PortalFrame frame) {
	    // 1) derive a source interior center from the A frame
	    int baseX = frame.bottomLeft.getBlockX();
	    int baseY = frame.bottomLeft.getBlockY();
	    int baseZ = frame.bottomLeft.getBlockZ();
	
	    double centerX;
	    double centerZ;
	    if ("X".equalsIgnoreCase(frame.orientation)) {
	        centerX = baseX + 1 + ((frame.width - 2) / 2.0);  // interior center along X
	        centerZ = baseZ + 0.5;
	    } else { // "Z" orientation
	        centerX = baseX + 0.5;
	        centerZ = baseZ + 1 + ((frame.width - 2) / 2.0);  // interior center along Z
	    }
	
	    // 2) apply OW <-> NETHER coordinate scaling
	    String destName = destWorld.getName();
	    boolean originIsNether = originWorldName.toLowerCase(java.util.Locale.ROOT).contains("nether");
	    boolean destIsNether   = destName.toLowerCase(java.util.Locale.ROOT).contains("nether");
	    double scale = 1.0;
	    if (!originIsNether && destIsNether)         scale = 1.0 / 8.0;  // OW -> NETHER
	    else if (originIsNether && !destIsNether)    scale = 8.0;        // NETHER -> OW
	
	    int destX = (int) Math.round(centerX * scale);
	    int destZ = (int) Math.round(centerZ * scale);
	
	    // 3) choose a reasonable Y in the destination
	    int highest = destWorld.getHighestBlockYAt(destX, destZ);
	    int maxCeiling = (destWorld.getEnvironment() == org.bukkit.World.Environment.NETHER) ? 118 : 254;
	    int destY = Math.min(Math.max(highest + 1, 10), maxCeiling);
	
	    // 4) convert interior center → approximate bottom-left corner for the frame
	    int bx, bz;
	    if ("X".equalsIgnoreCase(frame.orientation)) {
	        // interior width along X; bottom-left is 1 block "south" (−Z) and left by half interior
	        bx = destX - (int) Math.floor((frame.width - 2) / 2.0) - 1;
	        bz = destZ - 1;
	    } else {
	        // interior width along Z; bottom-left is 1 block "west" (−X) and back by half interior
	        bx = destX - 1;
	        bz = destZ - (int) Math.floor((frame.width - 2) / 2.0) - 1;
	    }
	
	    return new org.bukkit.Location(destWorld, bx, destY, bz);
	}
	// === INSERT (END)
	// # NEW: Public tight-range portal finder used by PortalManager & PortalListener
	public @org.jetbrains.annotations.Nullable org.bukkit.Location findNearbyPortalBlockTight(
	        org.bukkit.Location center, int maxDistanceBlocks) {
	    if (center == null || center.getWorld() == null) return null;
	    final org.bukkit.World w = center.getWorld();
	    final int r = Math.max(1, maxDistanceBlocks);

	    // Tight cube around the expected frame; shallow Y range is sufficient
	    for (int dy = -1; dy <= 3; dy++) {
	        for (int dx = -r; dx <= r; dx++) {
	            for (int dz = -r; dz <= r; dz++) {
	                org.bukkit.block.Block b = w.getBlockAt(
	                        center.getBlockX() + dx,
	                        center.getBlockY() + dy,
	                        center.getBlockZ() + dz);
	                if (b.getType() == org.bukkit.Material.NETHER_PORTAL) {
	                    return b.getLocation();
	                }
	            }
	        }
	    }
	    return null;
	}
	
    public Location findNearbyPortalBlock(
	            org.bukkit.Location center, int maxDistanceBlocks) {
	        return findNearbyPortalBlockTight(center, maxDistanceBlocks);
	    }
    }