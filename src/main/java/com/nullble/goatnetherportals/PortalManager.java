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
import org.bukkit.craftbukkit.CraftWorld;
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
import com.nullble.nulzone.NulZone;
import com.nullble.goatnetherportals.DetectionRegion;
import com.nullble.goatnetherportals.PortalManager.PortalFrame;
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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;
import org.jetbrains.annotations.Nullable;

public class PortalManager {

    private final GoatNetherPortals plugin;
    private final File dataFolder;
    private NamespacedKey linkCodeKey;
    private NamespacedKey ownerKey;
    private final File globalPortalMapFile;
    private YamlConfiguration globalPortalMap;
    public final Map<UUID, Long> recentIgniteTimestamps = new HashMap<>();
    private final Set<UUID> igniteCooldown = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recentSaveLock = new HashMap<>();
    private final Map<UUID, YamlConfiguration> queuedPlayerConfigs = new ConcurrentHashMap<>();
    //private final Set<String> spawnedMarkers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    //private final Map<String, PortalInfo> portalMap = new HashMap<>(); This may still be needed for portal in memory storage
    private final Map<String, Long> recentScanLock = new HashMap<>();
    private final Map<Location, String> portalEntryZones = new HashMap<>(); // region → linkCode
    private final java.util.Set<String> spawnedMarkers =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final Map<UUID, String> playerPendingLinks = new HashMap<>();
    public List<Block> portalBlocks;
    private final List<DetectionRegion> detectionRegions = new ArrayList<>();
    private final Map<UUID, Map<String, Long>> recentSaveTimestamps = new HashMap<>();
    private final Map<String, Long> recentPortalCooldowns = new HashMap<>();
    private final Queue<MarkerTask> markerQueue = new LinkedList<>();
    private boolean isMarkerSpawning = false;
    public static final UUID SERVER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Set<String> recentScanLocations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, String> recentPortalEntries = new HashMap<>();

    public YamlConfiguration getOrCreatePlayerConfig(UUID uuid) {
        return queuedPlayerConfigs.computeIfAbsent(uuid, this::getConfig);
    }

    public boolean playerYamlHasLocationFor(UUID uuid, String linkCode, String worldName) {      // #102
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);                                 // #103
        String base = "links." + linkCode + "." + worldName + ".location";                        // #104
        return config.contains(base);                                                             // #105
    }                                                                                             // #106

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
        if (plugin.getConfig().getBoolean("verboseLogging", false)) {
            plugin.getLogger().info("🌀 [SCAN] " + label + " @ (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
        }
    }

 // ⛓️ Helper struct
    public static class PortalFrame {
        public final int width;
        public final int height;
        public final String orientation;
        public Location cornerMarker; // Exact location of the marker (if created)

        public final Location bottomLeft;
        public final Location bottomRight;
        public final Location topLeft;
        public final Location topRight;

        public final List<Block> portalBlocks; // ✅ NEW FIELD

        public PortalFrame(int width, int height, String orientation,
                           Location bottomLeft, Location bottomRight,
                           Location topLeft, Location topRight,
                           List<Block> portalBlocks) {
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.bottomLeft = bottomLeft;
            this.bottomRight = bottomRight;
            this.topLeft = topLeft;
            this.topRight = topRight;
            this.portalBlocks = portalBlocks; // ✅ NEW FIELD
        }
    }

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

    public void setPortal(String playerName, boolean isNether, Location loc) {
        UUID uuid = getUUIDFromName(playerName);
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        String path = isNether ? "nether" : "overworld";
        String inversePath = isNether ? "overworld" : "nether";

        if (loc == null) {
            config.set(path, null);
        } else {
            // Check if a linkCode already exists on the other side
        	//String linkCode = getOrCreateSharedLinkCode(config, path, inversePath);
        	String linkCode = generateUniqueLinkCode(uuid);

            config.set(path + ".world", loc.getWorld().getName());
            config.set(path + ".x", loc.getBlockX());
            config.set(path + ".y", loc.getBlockY());
            config.set(path + ".z", loc.getBlockZ());
            config.set(path + ".linkCode", linkCode);

            /* Save to global portal map
            addPortalToGlobalMap(linkCode, loc, true, uuid);
            savePortalMapToDisk();*/
            addPortalToGlobalMap(linkCode, uuid); //THIS REPALCED THE ABOVE DONT FOR GET YOU CAN ROLL IT BACK

        }

        saveConfig(uuid, config);
    }

    public Location getLinkedPortal(String playerName, String targetWorld) {
        UUID uuid = getUUIDFromName(playerName);
        File file = new File(dataFolder, uuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section == null) continue;

            if (section.contains(targetWorld + ".location")) {
                double x = section.getDouble(targetWorld + ".location.x");
                double y = section.getDouble(targetWorld + ".location.y");
                double z = section.getDouble(targetWorld + ".location.z");
                return new Location(Bukkit.getWorld(targetWorld), x, y, z);
            }
        }

        return null;
    }



    public void clearPortal(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (file.exists()) file.delete();
    }

    public YamlConfiguration getConfig(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        return YamlConfiguration.loadConfiguration(file);
    }

    
    public YamlConfiguration getGlobalPortalMap() {
        return plugin.getGlobalPortalMap();
    }


    private void saveConfig(UUID uuid, YamlConfiguration config) {
        try {
            config.save(new File(dataFolder, uuid.toString() + ".yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    
    public void setAnchor(String playerName, Location loc) {
    	UUID uuid = getUUIDFromName(playerName);
    	YamlConfiguration config = getOrCreatePlayerConfig(uuid);

    	saveLocation(config, "anchor", loc);
    	saveConfig(uuid, config);

    }

    public Location getAnchor(String playerName) {
    	UUID uuid = getUUIDFromName(playerName);
    	YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        return loadLocation(config, "anchor");
    }

    private void saveLocation(YamlConfiguration config, String path, Location loc) {
        if (loc == null) {
            config.set(path, null);
        } else {
            config.set(path + ".world", loc.getWorld().getName());
            config.set(path + ".x", loc.getBlockX());
            config.set(path + ".y", loc.getBlockY());
            config.set(path + ".z", loc.getBlockZ());
        }
    }

    private Location deserializeLocation(World world, ConfigurationSection section) {
        if (section == null || world == null) return null;
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        return new Location(world, x, y, z);
    }

    private Location loadLocation(YamlConfiguration config, String path) {
        String worldName = config.getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x = config.getDouble(path + ".x");
        double y = config.getDouble(path + ".y");
        double z = config.getDouble(path + ".z");
        return new Location(world, x, y, z);
    }
    
    public boolean recoverAnchor(Player player) {
        String name = player.getName();
        Location lastAnchor = getAnchor(name);
        if (lastAnchor == null) return false;

        World world = lastAnchor.getWorld();
        Location best = null;
        double bestDist = Double.MAX_VALUE;

        int radius = plugin.getConfig().getInt("portalSearchRadius", 50);

        for (int x = -radius; x <= radius; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location loc = lastAnchor.clone().add(x, y, z);
                    Material mat = loc.getBlock().getType();
                    if (mat == Material.NETHER_PORTAL || mat == Material.OBSIDIAN) {
                        if (isInsideNulZone(loc)) continue;
                        double dist = loc.distanceSquared(player.getLocation());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = loc;
                        }
                    }
                }
            }
        }

        if (best != null) {
            setAnchor(name, best);
            player.sendMessage("§eYour original anchor was lost. A nearby portal has been relinked.");
            //plugin.getLogger().info("Anchor recovered for " + name + " at " + best);
            return true;
        }

        return false;
    }
    private boolean isInsideNulZone(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        NulZone nulZone = (NulZone) Bukkit.getPluginManager().getPlugin("NulZone");
        if (nulZone == null || !nulZone.isEnabled()) return false;

        return nulZone.getZoneManager().getMatchingZone(
            loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ()
        ) != null;
    }
    
    
    
    public void saveLink(@Nullable String playerName, Location loc, boolean isReturn, @Nullable String linkCode, @Nullable PortalFrame frameOverride) {
    	UUID uuid = (playerName != null) ? getUUIDFromName(playerName) : SERVER_UUID;

        
        String worldName = loc.getWorld().getName();

    	if (frameOverride == null) {
    	    plugin.getLogger().severe("❌ saveLink() received NULL frameOverride!");
    	}

        if (playerName == null || loc == null || loc.getWorld() == null) {
            plugin.getLogger().warning("⚠️ saveLink() short triggered");
            plugin.getLogger().warning("  playerName = " + playerName);
            plugin.getLogger().warning("  loc        = " + loc);
            plugin.getLogger().warning("  world      = " + (loc != null ? loc.getWorld() : "null"));
            return;
        }
        if (linkCode == null) {
            plugin.getLogger().severe("❌ saveLink() called with null linkCode — this is invalid and should be fixed upstream.");
            return;
        } else {
            // Use existing code
            plugin.getLogger().info("🔗 saveLink() using provided linkCode: " + linkCode);
        }
        World world = loc.getWorld();  // ✅ Add this line


        PortalFrame original = (frameOverride != null) ? frameOverride : scanFullPortalFrame(loc);
        if (original == null) return;

        // Defensive copy
        PortalFrame frame = new PortalFrame(
        	    original.width,
        	    original.height,
        	    original.orientation,
        	    original.bottomLeft,
        	    original.bottomRight,
        	    original.topLeft,
        	    original.topRight,
        	    original.portalBlocks
        	);


        if (frame == null) {
            plugin.getLogger().warning("❌ saveLink() failed, frame is null");
            return;
        }

        if (frameOverride != null) {
            if (frameOverride.bottomLeft == null || frameOverride.bottomRight == null ||
                frameOverride.topLeft == null || frameOverride.topRight == null) {
                plugin.getLogger().severe("❌ saveLink() - Frame has null corners. Aborting save.");
                plugin.getLogger().severe("BottomLeft: " + frameOverride.bottomLeft);
                plugin.getLogger().severe("BottomRight: " + frameOverride.bottomRight);
                plugin.getLogger().severe("TopLeft: " + frameOverride.topLeft);
                plugin.getLogger().severe("TopRight: " + frameOverride.topRight);
                return;
            }
        }

        String orientation = frame.orientation;
        Location frameCorner = frame.bottomLeft;

        String locKey = "portal::" + loc.getWorld().getName() + "::" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        String playerKey = "player::" + uuid + "::" + worldName;

        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        //config.set("name", playerName);
        config.set("name", (playerName != null) ? playerName : "Server");

        ConfigurationSection linksSection = config.getConfigurationSection("links");
        if (linksSection == null) linksSection = config.createSection("links");

        ConfigurationSection codeSection = linksSection.getConfigurationSection(linkCode);
        if (codeSection == null) codeSection = linksSection.createSection(linkCode);

        ConfigurationSection worldSection = codeSection.getConfigurationSection(worldName);
        if (worldSection == null) worldSection = codeSection.createSection(worldName);

        if (!worldSection.contains("location")) {
            ConfigurationSection locSection = worldSection.createSection("location");
            locSection.set("world", loc.getWorld().getName());
            locSection.set("x", loc.getX());
            locSection.set("y", loc.getY());
            locSection.set("z", loc.getZ());
            locSection.set("yaw", loc.getYaw());
            locSection.set("pitch", loc.getPitch());
        } else {
            plugin.getLogger().info("🛑 [saveLink] Skipping overwrite of existing location for link: " + linkCode);
        }

        Location cornerCheckLoc = loadUniversalLocation(worldSection.getConfigurationSection("frame.corner"), world);

        if (hasDiamondInCorners(cornerCheckLoc, frame.width, frame.height, orientation)) {
            config.set("links." + linkCode + ".diamondoverride", true);
        }


        if (hasDiamondInCorners(frameCorner, frame.width, frame.height, orientation)) {
            config.set("links." + linkCode + ".diamondoverride", true);
        }

        ConfigurationSection frameSection = worldSection.createSection("frame");
        frameSection.set("orientation", orientation);

        ConfigurationSection cornerSection = frameSection.createSection("corner");
        if (frame.bottomLeft == null) {
            plugin.getLogger().severe("❌ frame.bottomLeft is NULL during saveLink");
        } else {
            cornerSection.set("Bottom-Left", formatCoord(frame.bottomLeft));
        }
        if (frame.bottomRight == null) {
            plugin.getLogger().severe("❌ frame.bottomRight is NULL during saveLink");
        } else {
            cornerSection.set("Bottom-Right", formatCoord(frame.bottomRight));
        }
        if (frame.topLeft == null) {
            plugin.getLogger().severe("❌ frame.topLeft is NULL during saveLink");
        } else {
            cornerSection.set("Top-Left", formatCoord(frame.topLeft));
        }
        if (frame.topRight == null) {
            plugin.getLogger().severe("❌ frame.topRight is NULL during saveLink");
        } else {
            cornerSection.set("Top-Right", formatCoord(frame.topRight));
        }

/*
        cornerSection.set("Bottom-Left", formatCoord(frame.bottomLeft));
        cornerSection.set("Bottom-Right", formatCoord(frame.bottomRight));
        cornerSection.set("Top-Left", formatCoord(frame.topLeft));
        cornerSection.set("Top-Right", formatCoord(frame.topRight));*/

        Location min = frameCorner.clone();
        Location max = frameCorner.clone().add(
            orientation.equalsIgnoreCase("X") ? frame.width - 1 : 0,
            frame.height - 1,
            orientation.equalsIgnoreCase("Z") ? frame.width - 1 : 0
        );

        int expandX = orientation.equalsIgnoreCase("X") ? 0 : 1;
        int expandZ = orientation.equalsIgnoreCase("Z") ? 0 : 1;

        Location expandedMin = min.clone().subtract(expandX, 0, expandZ);
        Location expandedMax = max.clone().add(expandX, 0, expandZ);

        ConfigurationSection interiorSection = worldSection.createSection("interior");
        interiorSection.set("min", formatCoord(expandedMin));
        interiorSection.set("max", formatCoord(expandedMax));
     // ✅ Save marker info
        Location markerLoc = frame.cornerMarker != null ? frame.cornerMarker : loc.clone().add(0.5, 1.5, 0.5);

        if (markerLoc != null) {
            ConfigurationSection markerSection = worldSection.createSection("marker");
            markerSection.set("x", markerLoc.getX());
            markerSection.set("y", markerLoc.getY());
            markerSection.set("z", markerLoc.getZ());
            markerSection.set("corner", formatCoord(frame.bottomLeft));
            markerSection.set("orientation", orientation);
        } else {
            plugin.getLogger().severe("❌ markerLoc is NULL — skipping marker save");
        }

        detectionRegions.add(new DetectionRegion(expandedMin, expandedMax, linkCode));
        plugin.getLogger().info("📦 Registered detection region: " + formatLoc(expandedMin) + " → " + formatLoc(expandedMax));

	     // If both exist, nothing to generate. If neither exists, bail (shouldn't happen after save).

     // 🪧 Spawn marker (for Portal-A)
        plugin.getLogger().info("🪧 [MARKER] Spawning marker for Portal-A at: " + frame.bottomLeft + " for code: " + linkCode);
        //spawnPortalMarker(frame, linkCode, uuid);
        queueMarkerSpawn(frame, linkCode, uuid);


        for (Block portalBlock : frame.portalBlocks) {
            Location portalLoc = portalBlock.getLocation();
            Vector dir1 = orientation.equals("X") ? new Vector(0, 0, 1) : new Vector(1, 0, 0);
            Vector dir2 = dir1.clone().multiply(-1);
            portalEntryZones.put(portalLoc.clone().add(dir1).getBlock().getLocation(), linkCode);
            portalEntryZones.put(portalLoc.clone().add(dir2).getBlock().getLocation(), linkCode);
        }

     String otherWorldName = plugin.getOppositeWorld(worldName);
     Location dest = getPendingPortalLocation(linkCode, otherWorldName, uuid);

     if (dest != null && worldSection.getKeys(false).size() > 0 && worldName.equals(getFirstWorldForLink(linkCode, uuid))) {
         // 🔁 Only allow the *first portal created* to trigger the opposite-world pairing
         forceGeneratePairedPortal(linkCode, worldName, uuid, frame);
     }

        commitQueuedData(uuid);
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
            plugin.getLogger().warning("⚠️ formatCoord() called with null location");
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

        plugin.getLogger().warning("⚠️ loadUniversalLocation fallback at section: " + section.getCurrentPath());
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
            plugin.getLogger().warning("⚠️ Failed to parse coord string: " + coordString);
            return null;
        }
    }

    public void commitQueuedData(UUID uuid) {
        YamlConfiguration config = queuedPlayerConfigs.remove(uuid);
        if (config == null) return;

        File file = new File(dataFolder, uuid.toString() + ".yml");

        try {
            config.save(file);
            //plugin.getLogger().info("🔁 forceGeneratePairedPortal skipped — no pending portal found for " + linkCode);
        } catch (IOException e) {
            //plugin.getLogger().warning("❌ Failed to commit player data for " + uuid);
            e.printStackTrace();
        }
    }

    public Location getLinkedLocation(String playerName, String fromWorld, boolean isReturn) {
        UUID uuid = getUUIDFromName(playerName);
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection link = links.getConfigurationSection(code);
            if (link == null) continue;

            for (String portalKey : List.of("portalA", "portalB")) {
                ConfigurationSection portal = link.getConfigurationSection(portalKey);
                if (portal == null) continue;

                String world = portal.getString("world");
                if (world == null || world.equalsIgnoreCase(fromWorld)) continue;

                ConfigurationSection loc = portal.getConfigurationSection("location");
                if (loc == null) continue;

                World targetWorld = Bukkit.getWorld(world);
                if (targetWorld == null) continue;

                double x = loc.getDouble("x");
                double y = loc.getDouble("y");
                double z = loc.getDouble("z");

                return new Location(targetWorld, x, y, z);
            }
        }

        return null;
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



    public void logPortalBuild(Location loc, String facing) {
        File folder = plugin.getDataFolder();
        File logFile = new File(folder, "portal_rebuilds.log");

        String message = "[" + System.currentTimeMillis() + "] Portal rebuilt at "
                + loc.getWorld().getName() + " (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                + ") facing " + facing;

        try {
            Files.writeString(logFile.toPath(), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            //plugin.getLogger().warning("Failed to write to portal_rebuilds.log");
        }
    }

    public void registerPortal(Player player, Location loc, String linkCode, PortalFrame frame) {
        if (frame == null || linkCode == null) {
            plugin.getLogger().severe("❌ registerPortal() missing frame or linkCode");
            return;
        }

        // 💡 Corner safety checks
        if (frame.bottomLeft == null || frame.bottomRight == null || frame.topLeft == null || frame.topRight == null) {
            plugin.getLogger().severe("❌ registerPortal() - Frame has null corners. Aborting save.");
            plugin.getLogger().severe("BottomLeft: " + frame.bottomLeft);
            plugin.getLogger().severe("BottomRight: " + frame.bottomRight);
            plugin.getLogger().severe("TopLeft: " + frame.topLeft);
            plugin.getLogger().severe("TopRight: " + frame.topRight);
            return;
        }

        //UUID uuid = player.getUniqueId();
        UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        Location corner = frame.bottomLeft;
        int width = frame.width;
        int height = frame.height;
        String orientation = frame.orientation;
        String worldName = loc.getWorld().getName();

        // 2. Verify the portal isn't overlapping with another portal's space
        if (isOverlappingWithExistingPortal(frame, linkCode)) {
            player.sendMessage("§cThis portal overlaps with an existing portal's space.");
            return;
        }

        plugin.getLogger().warning("📦 About to spawn marker for: " + linkCode);
        //spawnPortalMarker(frame, linkCode, uuid); // ✅ Visual marker
        queueMarkerSpawn(frame, linkCode, uuid);

        // YAML structure
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) links = config.createSection("links");

        ConfigurationSection codeSection = links.getConfigurationSection(linkCode);
        if (codeSection == null) codeSection = links.createSection(linkCode);

        ConfigurationSection worldSection = codeSection.createSection(worldName);

        // Save origin location
        ConfigurationSection location = worldSection.createSection("location");
        location.set("x", corner.getBlockX());
        location.set("y", corner.getBlockY());
        location.set("z", corner.getBlockZ());

        // Save frame data
        ConfigurationSection frameSection = worldSection.createSection("frame");
        frameSection.set("orientation", orientation);
        frameSection.set("width", width);
        frameSection.set("height", height);

        ConfigurationSection frameCorner = frameSection.createSection("corner");
        frameCorner.set("Bottom-Left", formatCoord(frame.bottomLeft));
        frameCorner.set("Bottom-Right", formatCoord(frame.bottomRight));
        frameCorner.set("Top-Left", formatCoord(frame.topLeft));
        frameCorner.set("Top-Right", formatCoord(frame.topRight));

        config.set("name", player.getName());
        savePlayerConfig(uuid, config);

        plugin.getLogger().info("📍 registerPortal() loc = " + loc);
        saveLink(player.getName(), corner, false, linkCode, frame); // ✅ Only now do we save
        addPortalToGlobalMap(linkCode, uuid);

        // Auto-generate paired portal if missing
        String destWorld = plugin.getOppositeWorld(worldName);
        if (destWorld != null) {
            ConfigurationSection checkSection = config.getConfigurationSection("links." + linkCode);
            if (checkSection != null && !checkSection.contains(destWorld)) {
                Location frameBase = corner.clone();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Location nearby = findNearbyPortalBlock(frameBase);
                    if (nearby != null) {
                        PortalFrame scanned = scanFullPortalFrame(nearby);
                        if (scanned != null) {
                            forceGeneratePairedPortal(linkCode, worldName, uuid, scanned);
                        }
                    }
                }, 3L);
            }
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
        Direction.Axis axis = detectPortalAxis(bottomLeft);
        if (axis == Direction.Axis.X) {
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

    private Direction.Axis detectPortalAxis(Location loc) {
        World world = loc.getWorld();
        Block center = world.getBlockAt(loc);
        if (center.getRelative(BlockFace.EAST).getType() == Material.NETHER_PORTAL ||
            center.getRelative(BlockFace.WEST).getType() == Material.NETHER_PORTAL) {
            return Direction.Axis.X;
        }
        return Direction.Axis.Z;
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
 
    public void addPortalToGlobalMap(String linkCode, UUID owner) {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        if (!yaml.contains(linkCode)) {
            yaml.set(linkCode + ".owner", owner.toString());

            try {
                yaml.save(file);
                plugin.getLogger().info("✅ Added portal link " + linkCode + " to portalMap.yml");
            } catch (IOException e) {
                plugin.getLogger().warning("❌ Failed to save portalMap.yml");
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
            plugin.getLogger().info("✅ Updated portalMap: " + linkCode + "." + keyPath + " = " + value);
        } catch (IOException e) {
            plugin.getLogger().warning("❌ Failed to save portalMap.yml during setPortalMetadata");
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
            plugin.getLogger().warning("⚠ Invalid UUID in portalMap.yml for linkCode: " + linkCode);
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
                        plugin.getLogger().info("⏭️ [MARKER] Already spawned for " + __markerKey + " at " + formatLoc(loc));
                        return;
                    }
                }
            }
            plugin.getLogger().info("⏭️ [MARKER] Already spawned for " + __markerKey);
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
                    plugin.getLogger().info("✅ [MARKER] Already exists for " + linkCode + " at " + formatLoc(loc));
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

        plugin.getLogger().info("🪧 Marker spawned for " + linkCode + " at: " + markerLoc.toVector());

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

        commitQueuedData(ownerUUID);
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

    public String findSharedLinkCode(YamlConfiguration config, String world1, String world2) {
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection entry = links.getConfigurationSection(code);
            if (entry.contains(world1) && entry.contains(world2)) {
                return code;
            }
        }
        return null;
    }

    
    public void removePortalMarker(String linkCode) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                PersistentDataContainer container = entity.getPersistentDataContainer();
                NamespacedKey linkKey = new NamespacedKey(plugin, "linkCode");
                String existingCode = container.get(linkKey, PersistentDataType.STRING);
                if (linkCode.equals(existingCode)) {
                    entity.remove();
                    //plugin.getLogger().info("🗑 Removed portal marker with linkCode: " + linkCode);
                }
            }
        }
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

    public void tryAutoRegisterPortal(Player player, Location base, @Nullable PortalFrame preScannedFrame) {
    	if (base == null || base.getWorld() == null) return;

        //UUID uuid = (player != null) ? player.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000001");
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;
        String name = (player != null) ? player.getName() : "Server";
    	
        World world = base.getWorld();
        String worldName = world.getName();
        //UUID uuid = player.getUniqueId();
     // 🐞 Debug who is triggering the registration
        if (player != null) {
            plugin.getLogger().info("🔍 tryAutoRegisterPortal called by player: " + player.getName());
        } else {
            plugin.getLogger().info("🔍 tryAutoRegisterPortal called by SERVER/dispenser/command block.");
        }
        /* ⏱ Spam throttle
        long now = System.currentTimeMillis();
        long last = recentIgniteTimestamps.getOrDefault(uuid, 0L);
        if (now - last < 2000) return;
        recentIgniteTimestamps.put(uuid, now);*/

        // 🔍 Find nearby portal block
        Location portalLoc = plugin.getPortalManager().findNearbyPortalBlock(base, 4);
        if (portalLoc == null || !isValidPortal(portalLoc)) {
            plugin.getLogger().warning("❌ No valid portal block found near: " + base);
            return;
        }
        String linkCode = generateUniqueLinkCode(uuid);
        if (linkCode == null) {
            plugin.getLogger().warning("❌ Failed to generate linkCode");
            return;
        }
        
        // Store this linkCode immediately to prevent duplicate generation
        playerPendingLinks.put(uuid, linkCode);
        
        // 🧱 Resolve the full frame
        PortalFrame frame = (preScannedFrame != null)
            ? preScannedFrame
            : scanFullPortalFrame(portalLoc);

        if (frame == null) {
            plugin.getLogger().warning("❌ Portal frame scan failed at: " + portalLoc);
            return;
        }

        // 🔗 Generate a new linkCode
        //String linkCode = generateUniqueLinkCode(uuid);
        /*
         * 
        if (linkCode == null) {
            plugin.getLogger().warning("❌ Failed to get or create linkCode for: " + player.getName());
            return;
        }*/

        // 💾 Save all portal data to player .yml (frame, interior, detection)
        //plugin.getLogger().info("💾 Saving portal frame for " + player.getName() + " at " + portalLoc + " with code " + linkCode);
        plugin.getLogger().info("💾 Saving portal frame for " + name + " at " + portalLoc + " with code " + linkCode);

        //saveLink(player.getName(), frame.bottomLeft, false, linkCode, frame);
        saveLink(name, frame.bottomLeft, false, linkCode, frame);
        if (uuid.equals(SERVER_UUID)) {
            plugin.getPortalManager().registerDispenserPortal(uuid, linkCode, frame, worldName);
        }

        // 🌍 Reserve opposite world (ensures pairing works)
        String oppositeWorld = plugin.getOppositeWorld(worldName);
        if (oppositeWorld != null) {
            YamlConfiguration config = getOrCreatePlayerConfig(uuid);
            ConfigurationSection codeSection = config.getConfigurationSection("links." + linkCode);
            if (codeSection == null) codeSection = config.createSection("links." + linkCode);
            ConfigurationSection oppSection = codeSection.getConfigurationSection(oppositeWorld);
            if (oppSection == null || !oppSection.contains("location")) {
                ConfigurationSection opp = codeSection.createSection(oppositeWorld);
                opp.set("location.x", 0);
                opp.set("location.y", 0);
                opp.set("location.z", 0);
            }


            // Save changes
            try {
                File file = new File(new File(plugin.getDataFolder(), "playerdata"), uuid.toString() + ".yml");
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("❌ Failed to save reserved opposite world section.");
                e.printStackTrace();
            }
        }

        // 🔁 Generate the paired portal
        plugin.getPortalManager().forceGeneratePairedPortal(linkCode, worldName, uuid, frame);

        // 🧷 Final marker registration & completion
        finalizePair(player, linkCode);

        /* 🟢 Feedback
        player.sendMessage("§aYour portal has been registered!");
        player.sendMessage("§7Link Code: §e" + linkCode);*/
        if (player != null) {
            player.sendMessage("§aYour portal has been registered!");
            player.sendMessage("§7Link Code: §e" + linkCode);
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
            plugin.getLogger().info("✅ [DISPENSER-REGISTER] Portal origin registered and saved for: " + linkCode);

            // 🪧 Spawn the marker after save
            spawnPortalMarker(frame, linkCode, uuid);
         // 🔄 Force refresh detection zone in memory
            plugin.loadPlayerData(uuid);
            plugin.getLogger().info("🔁 [DISPENSER-REGISTER] Player config reloaded into memory for link: " + linkCode);

            tagPortalFrame(frame.bottomLeft, linkCode, uuid);

        } catch (IOException e) {
            plugin.getLogger().severe("❌ [DISPENSER-REGISTER] Failed to save portal origin.");
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

    public String findExistingPendingLinkCode(UUID uuid, String worldName, Location loc) {
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);

        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
        	ConfigurationSection dest = config.getConfigurationSection("links." + code + "." + worldName);
        	if (dest == null) continue;


            ConfigurationSection locSec = dest.getConfigurationSection("location");
            if (locSec == null) continue;

            String w = locSec.getString("world");
            double x = locSec.getDouble("x");
            double y = locSec.getDouble("y");
            double z = locSec.getDouble("z");

            if (w.equalsIgnoreCase(worldName)
                && Math.abs(x - loc.getBlockX()) < 1
                && Math.abs(y - loc.getBlockY()) < 1
                && Math.abs(z - loc.getBlockZ()) < 1) {
                return code;
            }
        }

        return null;
    }

    public void finalizePair(Player player, String linkCode) {
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        ConfigurationSection link = config.getConfigurationSection("links." + linkCode);
        if (link == null) return;

        Set<String> worldsWithLocation = new HashSet<>();
        for (String key : link.getKeys(false)) {
            if (key.equals("pendingPair")) continue;
            ConfigurationSection worldSection = link.getConfigurationSection(key);
            if (worldSection != null && worldSection.contains("location")) {
                worldsWithLocation.add(key);
            }
        }

        // ✅ If already finalized, skip
        if (!link.getBoolean("pendingPair", true)) return;

        // ✅ If both worlds are linked, finalize once and stop further pairing logic
        if (worldsWithLocation.size() >= 2) {
            link.set("pendingPair", false);
            commitQueuedData(uuid);
        }
    }


    private Location parseCoord(String raw, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (raw == null || world == null) return null;

        String[] parts = raw.replace(" ", "").split(",");
        if (parts.length != 3) return null;

        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("⚠️ Failed to parse coordinate: " + raw);
            return null;
        }
    }

    public Location getPortalLocationByLinkCode(String linkCode, String worldName) {
        File portalMapFile = new File(plugin.getDataFolder(), "portalMap.yml");
        YamlConfiguration portalMap = YamlConfiguration.loadConfiguration(portalMapFile);

        String uuidString = portalMap.getString(linkCode + ".owner");
        if (uuidString == null) return null;

        UUID uuid = UUID.fromString(uuidString);
        File playerFile = new File(new File(plugin.getDataFolder(), "playerdata"), uuid + ".yml");
        if (!playerFile.exists()) return null;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        ConfigurationSection section = config.getConfigurationSection("links." + linkCode + "." + worldName + ".location");
        if (section == null) return null;

        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");

        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        return new Location(world, x + 0.5, y, z + 0.5);
    }


    public boolean isPortalRegisteringSoon(Player player, Location destination) {
        //UUID uuid = player.getUniqueId();
    	UUID uuid = (player != null) ? player.getUniqueId() : SERVER_UUID;

        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) return false;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return false;

        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section == null || !section.getBoolean("pendingPair", false)) continue;

            ConfigurationSection worldSection = section.getConfigurationSection(destination.getWorld().getName());
            if (worldSection == null || !worldSection.contains("location")) continue;

            ConfigurationSection loc = worldSection.getConfigurationSection("location");
            if (loc == null) continue;

            double x = loc.getDouble("x");
            double y = loc.getDouble("y");
            double z = loc.getDouble("z");

            if (destination.distance(new Location(destination.getWorld(), x, y, z)) < 3.5) {
                return true; // close match
            }
        }

        return false;
    }

    public String findAnyLinkCodeWithOneWorld(UUID uuid) {
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section == null) continue;

            int worldCount = 0;
            for (String k : section.getKeys(false)) {
                if (k.equalsIgnoreCase("frame") || k.equalsIgnoreCase("location")) continue;
                worldCount++;
            }
            if (worldCount == 1) return code;
        }

        return null;
    }


    //private final Map<UUID, Map<String, Long>> recentSaveTimestamps = new HashMap<>();

    public boolean isSaveTooRecent(UUID uuid, String world) {
        long now = System.currentTimeMillis();
        long last = recentSaveTimestamps
            .computeIfAbsent(uuid, k -> new HashMap<>())
            .getOrDefault(world, 0L);

        if (now - last < 1000) return true;

        recentSaveTimestamps.get(uuid).put(world, now);
        return false;
    }

    public void cleanupPortalMarkers() {
        int totalRemoved = 0;
        for (World world : Bukkit.getWorlds()) {
            int removed = 0;
            for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                if (entity.getScoreboardTags().contains("gnp_marker")) {
                    entity.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                //plugin.getLogger().info("🧹 Removed " + removed + " portal markers from world: " + world.getName());
                totalRemoved += removed;
            }
        }

        if (totalRemoved == 0) {
            //plugin.getLogger().info("✅ No portal markers found to clean up.");
        } else {
            //plugin.getLogger().info("✅ Total portal markers removed: " + totalRemoved);
        }
        spawnedMarkers.clear();
    }

    public void removeLink(String playerName, String worldName, boolean isReturn) {
        UUID uuid = getUUIDFromName(playerName);
        File file = new File(dataFolder, uuid + ".yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return;

        for (String code : links.getKeys(false)) {
            ConfigurationSection section = links.getConfigurationSection(code);
            if (section != null && section.contains(worldName)) {
                section.set(worldName, null);
                //plugin.getLogger().info("🧼 Removed portal link for " + playerName + " in world: " + worldName);
                try {
                    config.save(file);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    public String getPendingLinkCodeForWorld(UUID uuid, String worldName) {
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        ConfigurationSection links = config.getConfigurationSection("links");
        if (links == null) return null;

        for (String code : links.getKeys(false)) {
            ConfigurationSection sec = links.getConfigurationSection(code);
            if (sec == null) continue;
            if (sec.getBoolean("pendingPair", false) && !sec.contains(worldName)) {
                return code;
            }
        }
        return null;
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

    public String getLinkCodeFromBlock(Location loc) {
        if (loc == null || loc.getBlock() == null) return null;

        Block block = loc.getBlock();
        if (!block.getType().name().contains("PORTAL")) return null;

        PersistentDataContainer container = new CustomBlockData(block, plugin);
        if (container.has(key("linkCode"), PersistentDataType.STRING)) {
            return container.get(key("linkCode"), PersistentDataType.STRING);
        }

        return null;
    }

    private NamespacedKey key(String path) {
        return new NamespacedKey(plugin, path);
    }

    
    public void forceCreatePortal(Location center, String linkCode, UUID ownerUUID) {
        World world = center.getWorld();
        if (world == null) return;

        // Create basic obsidian frame only
        Location corner = findBottomLeftCorner(center);
        if (corner == null) corner = center.clone();

        String worldName = center.getWorld().getName();
        YamlConfiguration config = getOrCreatePlayerConfig(ownerUUID);

        ConfigurationSection frameSec = config.getConfigurationSection("links." + linkCode + "." + worldName + ".frame");
        String orientation = frameSec != null ? frameSec.getString("orientation", "X") : "X";
        int width = frameSec != null ? frameSec.getInt("width", 2) : 2;
        int height = frameSec != null ? frameSec.getInt("height", 3) : 3;

        // 🔨 Build frame
        int dx = orientation.equalsIgnoreCase("X") ? 1 : 0;
        int dz = orientation.equalsIgnoreCase("X") ? 0 : 1;
        //World world = corner.getWorld();

        for (int x = 0; x < width + 2; x++) {
            for (int y = 0; y < height + 2; y++) {
                Location blockLoc = corner.clone().add(dx * x, y, dz * x);
                boolean isEdge = (x == 0 || x == width + 1 || y == 0 || y == height + 1);
                blockLoc.getBlock().setType(isEdge ? Material.OBSIDIAN : Material.AIR);
            }
        }

        // 🔥 Trigger portal
        tryTriggerNaturalPortal(corner.clone().add(dx + 1, 1, dz + 1));


        // Let Minecraft naturally ignite the portal
        tryTriggerNaturalPortal(center);

        //plugin.getLogger().info("✅ Built frame and triggered portal at " + center);

        // Let marker and tag logic proceed
        tryRegisterArrivalPortal(center, linkCode, ownerUUID);
    }


    public Location findSafeNearbyLocation(Location origin, int initialRadius) {
        World world = origin.getWorld();
        if (world == null) return null;

        File globalFile = new File(plugin.getDataFolder(), "portalMap.yml");
        YamlConfiguration globalConfig = YamlConfiguration.loadConfiguration(globalFile);
        ConfigurationSection portalMap = globalConfig.getConfigurationSection("");

        int maxRadius = initialRadius * 2;
        for (int radius = initialRadius; radius <= maxRadius; radius += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only scan perimeter of current radius "ring"
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    Location test = origin.clone().add(dx, 0, dz);
                    if (isPortalNearby(test, 5)) continue;

                    boolean tooClose = false;

                    if (portalMap != null) {
                        for (String code : portalMap.getKeys(false)) {
                            ConfigurationSection entry = portalMap.getConfigurationSection(code);
                            if (entry == null) continue;

                            ConfigurationSection perWorld = entry.getConfigurationSection(world.getName());
                            if (perWorld == null) continue;

                            double x = perWorld.getDouble("location.x");
                            double y = perWorld.getDouble("location.y");
                            double z = perWorld.getDouble("location.z");

                            Location existing = new Location(world, x, y, z);
                            if (existing.distance(test) < 10) {
                                tooClose = true;
                                break;
                            }
                        }
                    }

                    if (!tooClose) {
                        //plugin.getLogger().info("✅ Found safe portal location at " + test);
                        return test;
                    }
                }
            }
        }

        //plugin.getLogger().warning("⚠ No safe location found after searching up to radius " + maxRadius);
        return null;
    }

    
    public Location calculateMarkerPosition(Location bottomLeft) {
        if (bottomLeft == null) return null;
        World world = bottomLeft.getWorld();
        if (world == null) return null;

        int x = bottomLeft.getBlockX();
        int y = bottomLeft.getBlockY();
        int z = bottomLeft.getBlockZ();

        // Check if it's a horizontal portal (X-axis)
        boolean facingX = world.getBlockAt(x + 1, y + 1, z).getType() == Material.NETHER_PORTAL;

        double markerX = x + (facingX ? 1.5 : 0.5);
        double markerZ = z + (facingX ? 0.5 : 1.5);
        double markerY = y + 1.0;

        return new Location(world, markerX, markerY, markerZ);
    }


    public void buildBasicPortalFrame(Location bottomLeft) {
        if (bottomLeft == null) return;

        World world = bottomLeft.getWorld();
        if (world == null) return;

        int x = bottomLeft.getBlockX();
        int y = bottomLeft.getBlockY();
        int z = bottomLeft.getBlockZ();

        // 🟪 Create a simple vertical frame: 4 tall, 3 wide
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                Block block = world.getBlockAt(x + dx, y + dy, z);
                if (dx == 0 || dx == 2 || dy == 0 || dy == 3) {
                    block.setType(Material.OBSIDIAN);
                } else {
                    block.setType(Material.NETHER_PORTAL);
                }
            }
        }
    }

    private NamespacedKey namespacedKey(String key) {
        return new NamespacedKey(plugin, key);
    }

    public void cleanupNearbyMarkers(Location center, String linkCode) {
        PortalFrame frame = scanFullPortalFrame(center);
        if (frame == null) {
            plugin.getLogger().warning("❌ [CLEANUP] Could not scan frame at " + formatLoc(center));
            return;
        }

        BoundingBox box = BoundingBox.of(frame.bottomLeft, frame.topRight).expand(0.5);
        Collection<Entity> entities = center.getWorld().getNearbyEntities(box);

        for (Entity entity : entities) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

            String existing = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "linkCode"),
                PersistentDataType.STRING
            );

            if (linkCode.equals(existing)) {
                stand.remove();
                plugin.getLogger().info("🧹 [CLEANUP] Removed marker for linkCode: " + linkCode + " inside portal frame.");
            }
        }
    }


    
    public Location getPendingPortalLocation(String linkCode, String worldName, UUID ownerUUID) {
        if (ownerUUID == null) return null;

        File file = new File(new File(plugin.getDataFolder(), "playerdata"), ownerUUID.toString() + ".yml");
        if (!file.exists()) return null;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection frameCorner = config.getConfigurationSection("links." + linkCode + "." + worldName + ".frame.corner");
        if (frameCorner == null) return null;

        String raw = frameCorner.getString("Bottom-Left");
        if (raw == null || raw.trim().equals("0, 0, 0")) {
            plugin.getLogger().warning("❌ [getPendingPortalLocation] Bottom-Left is invalid or missing for " + linkCode + " in world: " + worldName);
            return null;
        }

        return parseCoord(raw, worldName);
    }

    public UUID getPlayerUUIDFromLinkCode(String linkCode) {
        File playerDataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!playerDataFolder.exists()) return null;

        for (File file : playerDataFolder.listFiles()) {
            if (!file.getName().endsWith(".yml")) continue;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection links = config.getConfigurationSection("links");
            if (links != null && links.contains(linkCode)) {
                String uuidString = file.getName().replace(".yml", "");
                try {
                    return UUID.fromString(uuidString);
                } catch (IllegalArgumentException e) {
                    //plugin.getLogger().warning("Invalid UUID in filename: " + uuidString);
                }
            }
        }

        return null;
    }

    public boolean isMarkerFresh(String linkCode, Location pos) {
        for (Entity entity : pos.getWorld().getNearbyEntities(pos, 1, 1, 1)) {
            if (entity instanceof ArmorStand stand) {
                if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

                String code = stand.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "linkCode"),
                    PersistentDataType.STRING
                );

                if (code != null && code.equalsIgnoreCase(linkCode)) {
                    String status = stand.getPersistentDataContainer().get(
                        new NamespacedKey(plugin, "status"),
                        PersistentDataType.STRING
                    );
                    return "FRESH".equalsIgnoreCase(status);
                }
            }
        }
        return false;
    }

    public void markAllMarkersWithLinkCode(String linkCode) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                if (!(entity instanceof ArmorStand stand)) continue;
                if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

                PersistentDataContainer data = stand.getPersistentDataContainer();
                String code = data.get(new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
                if (!linkCode.equalsIgnoreCase(code)) continue;

                NamespacedKey statusKey = new NamespacedKey(plugin, "status");
                String current = data.getOrDefault(statusKey, PersistentDataType.STRING, "FRESH");

                if ("FRESH".equalsIgnoreCase(current)) {
                    data.set(statusKey, PersistentDataType.STRING, "NORMAL");
                    //plugin.getLogger().info("✅ Marker with linkCode " + linkCode + " updated to NORMAL at " + stand.getLocation());
                }
            }
        }
    }

    public void forceGeneratePairedPortal(String linkCode, String originWorld, UUID uuid, PortalFrame sourceFrame) {

        String destinationWorld = plugin.getOppositeWorld(originWorld);
        if (destinationWorld == null) return;

        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        ConfigurationSection linkSection = config.getConfigurationSection("links." + linkCode);
        if (linkSection == null) return;

        ConfigurationSection sourceData = linkSection.getConfigurationSection(originWorld);
        if (sourceData == null) return;

        Location sourceCorner = sourceFrame.bottomLeft.clone();
        Location rawCorner = sourceCorner.clone();

        // Ensure destination world exists/loaded
        org.bukkit.World destW = Bukkit.getWorld(destinationWorld);
        if (destW == null) {
            plugin.getLogger().warning("[GNP] Destination world not loaded: " + destinationWorld);
            return; // if you added requestPairedPortalBuild, it should create/load or defer
        }
        rawCorner.setWorld(destW);

        // Scale coords BEFORE creating destCorner
        double scaledX = rawCorner.getX();
        double scaledZ = rawCorner.getZ();
        if (originWorld.endsWith("_nether") && !destinationWorld.endsWith("_nether")) {
            scaledX *= 8; scaledZ *= 8;
        } else if (!originWorld.endsWith("_nether") && destinationWorld.endsWith("_nether")) {
            scaledX /= 8; scaledZ /= 8;
        }

        // Anchor for build
        Location destCorner = new Location(destW, Math.floor(scaledX), rawCorner.getBlockY(), Math.floor(scaledZ));

        // Load the actual destination chunk NOW (after destCorner is known)
        org.bukkit.Chunk destChunk = destCorner.getChunk();
        if (!destChunk.isLoaded()) destChunk.load();

        int portalHeight = sourceFrame.topLeft.getBlockY() - sourceFrame.bottomLeft.getBlockY() + 1;
        Location safeDest = findSafeValidPortalLocation(destCorner, 8, 16, portalHeight);
        if (safeDest == null) {
            plugin.getLogger().warning("❌ No safe destination found near: " + formatLoc(destCorner));
            return;
        }
        destCorner = safeDest;

        // Bedrock guard
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block nearby = destCorner.clone().add(dx, dy, dz).getBlock();
                    if (nearby.getType() == Material.BEDROCK) {
                        plugin.getLogger().warning("❌ Portal generation cancelled — would touch bedrock at: " + formatLoc(destCorner));
                        return;
                    }
                }
            }
        }

        // Avoid stacking on existing portals
        if (isPortalNearby(destCorner, 10)) {
            Location fallback = findSafeNearbyLocation(destCorner, 10);
            if (fallback != null) destCorner = fallback; else return;
        }

        // Clamp extreme Y (safety)
        if (destCorner.getBlockY() > 250 || destCorner.getBlockY() < 5) destCorner.setY(64);

        // === REPLACE (BEGIN) — Lines 2351–2365
        // Marker status gates — do NOT return; only decide whether to skip the frame copy.
        // We always proceed to scan + register B in the delayed block.
        boolean skipBuild = false;
        String markerStatus = getMarkerStatus(linkCode, destinationWorld);
        if ("NORMAL".equalsIgnoreCase(markerStatus)) skipBuild = true;

        int width = sourceFrame.orientation.equalsIgnoreCase("X")
            ? sourceFrame.bottomRight.getBlockX() - sourceFrame.bottomLeft.getBlockX() + 1
            : sourceFrame.bottomRight.getBlockZ() - sourceFrame.bottomLeft.getBlockZ() + 1;
        int height = sourceFrame.topLeft.getBlockY() - sourceFrame.bottomLeft.getBlockY() + 1;

        if ("FRESH".equalsIgnoreCase(markerStatus)) {
            if (!portalBlocksMissing(destCorner, width, height, sourceFrame.orientation)) skipBuild = true;
        }
        if (areBothMarkersPresent(linkCode, originWorld, destinationWorld)) {
            if (!portalBlocksMissing(destCorner, width, height, sourceFrame.orientation)) skipBuild = true;
        }
        // === REPLACE (END) — Lines 2351–2365

        // === REPLACE — Line 2367
        if (!skipBuild) copyPortalFrameFromTo(sourceFrame.bottomLeft, destCorner, width, height, sourceFrame.orientation);
        // === REPLACE (END) — Line 2367

        // Find a portal block to start scan from (if interior already lit elsewhere)
        if (destCorner.getBlock().getType() != Material.NETHER_PORTAL) {
            Location nearby = findNearbyPortalBlock(destCorner);
            if (nearby != null) destCorner = nearby;
        }

        // REPLACEMENT — use tight search around intended destination
        Location portalScanBase = findNearbyPortalBlock(destCorner, /*radius*/ 3);
        if (portalScanBase == null) portalScanBase = destCorner.clone();

        portalScanBase.setWorld(destW);
        final Location finalScanStart = portalScanBase;

        final World portalWorld = destCorner.getWorld();

        // === INSERT finals (captures for inner class) — Line ~2391
        final org.bukkit.Location destCornerFinal = destCorner.clone();
        final org.bukkit.Location finalScanStartFinal = finalScanStart.clone();
        final java.util.UUID uuidFinal = uuid;
        final java.lang.String linkCodeFinal = linkCode;
        final java.lang.String destinationWorldFinal = destinationWorld;
        // === INSERT finals (END) — Line ~2391

        // Delay a couple ticks: let physics light the portal, then scan + persist
        // === REPLACE timer block — Lines ~2393–2454
        new org.bukkit.scheduler.BukkitRunnable() {
            int attempts = 0; // up to ~2 seconds @ 2 ticks per attempt
            @Override public void run() {
                attempts++;

                // keep looking for the portal blocks that just formed
                org.bukkit.Location scanStart = findNearbyPortalBlock(destCornerFinal, /*radius*/ 3);
                if (scanStart == null) scanStart = finalScanStartFinal;

                java.util.Set<org.bukkit.Location> visited = new java.util.HashSet<>();
                PortalFrame newFrame = scanFullPortalFrame(scanStart, visited, false, false);
                if (newFrame == null) {
                    if (attempts >= 40) {
                        plugin.getLogger().warning("⚠️ [PAIR] Portal-B scan failed after " + attempts + " attempts at " + destCornerFinal);
                        this.cancel();
                    }
                    return;
                }

                // HARD GUARD — ensure we didn't snap to a distant, older portal
                double d2 = newFrame.bottomLeft.distanceSquared(destCornerFinal);
                if (d2 > 25.0) { // > 5 blocks from intended B corner? keep trying.
                    if (attempts >= 40) {
                        plugin.getLogger().warning("⚠️ [PAIR] Found portal too far from target (" + Math.sqrt(d2) + " blocks). Giving up at " + destCornerFinal);
                        this.cancel();
                    }
                    return;
                }

                // Marker + YAML + detection for B-side
                spawnPortalMarker(newFrame, linkCodeFinal, uuidFinal);

                org.bukkit.configuration.file.YamlConfiguration delayedConfig = getOrCreatePlayerConfig(uuidFinal);
                org.bukkit.configuration.ConfigurationSection destSection =
                        delayedConfig.getConfigurationSection("links." + linkCodeFinal + "." + destinationWorldFinal);
                if (destSection == null) destSection =
                        delayedConfig.createSection("links." + linkCodeFinal + "." + destinationWorldFinal);

                org.bukkit.Location destLoc = newFrame.bottomLeft;

                org.bukkit.configuration.ConfigurationSection locSection = destSection.createSection("location");
                locSection.set("world", destLoc.getWorld().getName());
                locSection.set("x", destLoc.getX());
                locSection.set("y", destLoc.getY());
                locSection.set("z", destLoc.getZ());
                locSection.set("yaw", destLoc.getYaw());
                locSection.set("pitch", destLoc.getPitch());

                org.bukkit.configuration.ConfigurationSection frameSection = destSection.createSection("frame");
                frameSection.set("orientation", newFrame.orientation);
                org.bukkit.configuration.ConfigurationSection cornerSection = frameSection.createSection("corner");
                cornerSection.set("Bottom-Left", formatCoord(newFrame.bottomLeft));
                cornerSection.set("Bottom-Right", formatCoord(newFrame.bottomRight));
                cornerSection.set("Top-Left", formatCoord(newFrame.topLeft));
                cornerSection.set("Top-Right", formatCoord(newFrame.topRight));

                if (!newFrame.portalBlocks.isEmpty()) {
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (org.bukkit.block.Block b : newFrame.portalBlocks) {
                        org.bukkit.Location l = b.getLocation();
                        minX = Math.min(minX, l.getBlockX());
                        minY = Math.min(minY, l.getBlockY());
                        minZ = Math.min(minZ, l.getBlockZ());
                        maxX = Math.max(maxX, l.getBlockX());
                        maxY = Math.max(maxY, l.getBlockY());
                        maxZ = Math.max(maxZ, l.getBlockZ());
                    }

                    org.bukkit.Location min = new org.bukkit.Location(destLoc.getWorld(), minX, minY, minZ);
                    org.bukkit.Location max = new org.bukkit.Location(destLoc.getWorld(), maxX, maxY, maxZ);

                    int expandX = 0, expandZ = 0;
                    if ("X".equalsIgnoreCase(newFrame.orientation)) expandZ = 1; else expandX = 1;

                    org.bukkit.Location expandedMin = min.clone().subtract(expandX, 0, expandZ);
                    org.bukkit.Location expandedMax = max.clone().add(expandX, 0, expandZ);

                    // in-memory + persist
                    detectionRegions.add(new DetectionRegion(expandedMin, expandedMax, linkCodeFinal));

                    org.bukkit.configuration.ConfigurationSection interiorSection = destSection.getConfigurationSection("interior");
                    if (interiorSection == null) interiorSection = destSection.createSection("interior");
                    interiorSection.set("min", formatCoord(expandedMin));
                    interiorSection.set("max", formatCoord(expandedMax));
                }

                try {
                    java.io.File file = new java.io.File(new java.io.File(plugin.getDataFolder(), "playerdata"), uuidFinal.toString() + ".yml");
                    delayedConfig.save(file);
                } catch (java.io.IOException e) {
                    plugin.getLogger().severe("❌ [PAIR] Failed to save portal config for: " + uuidFinal);
                    e.printStackTrace();
                }

                // keep other indices in sync and finalize
                addPortalToGlobalMap(linkCodeFinal, uuidFinal);

                org.bukkit.configuration.ConfigurationSection linkRoot =
                        delayedConfig.getConfigurationSection("links." + linkCodeFinal);
                if (linkRoot != null) {
                    linkRoot.set("pendingPair", false);
                    try {
                        java.io.File file = new java.io.File(new java.io.File(plugin.getDataFolder(), "playerdata"), uuidFinal.toString() + ".yml");
                        delayedConfig.save(file);
                    } catch (java.io.IOException ignored) {}
                }

                clearPendingLink(uuidFinal);
                plugin.getLogger().info("✅ [PAIR] Portal-B registered for " + linkCodeFinal + " at " + destLoc);
                this.cancel();
            }
        }.runTaskTimer(plugin, 2L, 2L);
        // === REPLACE timer block (END) — Lines ~2393–2454
    }


    public Location findSafeValidPortalLocation(Location base, int maxHorizontal, int maxVertical, int portalHeight) {
        World world = base.getWorld();
        int startX = base.getBlockX();
        int startY = base.getBlockY();
        int startZ = base.getBlockZ();

        String worldName = world.getName().toLowerCase();
        int minY = 6;
        int maxY = worldName.contains("nether") ? 120 : 255;

        int correctedY = startY;
        if (correctedY < minY) {
            correctedY = minY;
            plugin.getLogger().info("🔼 Adjusted portal Y upward to minY: " + correctedY);
        } else if ((correctedY + portalHeight + 1) > maxY) {
            correctedY = maxY - portalHeight - 1;
            plugin.getLogger().info("🔽 Adjusted portal Y downward to fit below ceiling: " + correctedY);
        }

        Location clamped = new Location(world, startX, correctedY, startZ);
        plugin.getLogger().info("✅ Using corrected destination location: " + formatLoc(clamped));

        int attempts = 0;
        int offsetStep = 4;
        int maxAttempts = 5;

        while (attempts < maxAttempts) {
            Location bl = clamped;
            Location br = bl.clone().add(3, 0, 0);
            Location tl = bl.clone().add(0, portalHeight - 1, 0);
            Location tr = br.clone().add(0, portalHeight - 1, 0);

            int minX = Math.min(Math.min(bl.getBlockX(), br.getBlockX()), Math.min(tl.getBlockX(), tr.getBlockX()));
            int maxX = Math.max(Math.max(bl.getBlockX(), br.getBlockX()), Math.max(tl.getBlockX(), tr.getBlockX()));
            int minYFrame = Math.min(Math.min(bl.getBlockY(), br.getBlockY()), Math.min(tl.getBlockY(), tr.getBlockY()));
            int maxYFrame = Math.max(Math.max(bl.getBlockY(), br.getBlockY()), Math.max(tl.getBlockY(), tr.getBlockY()));
            int minZ = Math.min(Math.min(bl.getBlockZ(), br.getBlockZ()), Math.min(tl.getBlockZ(), tr.getBlockZ()));
            int maxZ = Math.max(Math.max(bl.getBlockZ(), br.getBlockZ()), Math.max(tl.getBlockZ(), tr.getBlockZ()));

            boolean foundOverlap = false;
            for (Entity entity : world.getNearbyEntities(new Location(world, minX, minYFrame, minZ),
                                                         maxX - minX + 1, maxYFrame - minYFrame + 1, maxZ - minZ + 1)) {
                if (!(entity instanceof ArmorStand stand)) continue;
                if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

                String linkCode = stand.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "linkCode"), PersistentDataType.STRING);
                String ownerString = stand.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "owner"), PersistentDataType.STRING);

                if (linkCode == null || ownerString == null) continue;

                try {
                    UUID owner = UUID.fromString(ownerString);
                    YamlConfiguration config = plugin.getPortalManager().getOrCreatePlayerConfig(owner);
                    ConfigurationSection cornerSec = config.getConfigurationSection("links." + linkCode + "." + world.getName() + ".frame.corner");
                    if (cornerSec == null) continue;

                    String[] blData = cornerSec.getString("Bottom-Left").split(", ");
                    String[] trData = cornerSec.getString("Top-Right").split(", ");

                    int bx1 = Integer.parseInt(blData[0]);
                    int by1 = Integer.parseInt(blData[1]);
                    int bz1 = Integer.parseInt(blData[2]);
                    int bx2 = Integer.parseInt(trData[0]);
                    int by2 = Integer.parseInt(trData[1]);
                    int bz2 = Integer.parseInt(trData[2]);

                    boolean overlap = bx2 >= minX && bx1 <= maxX &&
                                      by2 >= minYFrame && by1 <= maxYFrame &&
                                      bz2 >= minZ && bz1 <= maxZ;

                    if (overlap) {
                        plugin.getLogger().warning("❌ Overlap detected with portal link " + linkCode + " at: " + formatLoc(stand.getLocation()));
                        foundOverlap = true;
                        break;
                    }

                } catch (Exception ex) {
                    plugin.getLogger().warning("⚠️ Failed to process marker data for link " + linkCode);
                }
            }

            if (!foundOverlap) {
                plugin.getLogger().info("✅ Destination frame volume is clear at: " + formatLoc(clamped));
                return clamped;
            }

            clamped.add(offsetStep, 0, offsetStep);
            plugin.getLogger().info("↪ Retrying new destination offset to: " + formatLoc(clamped));
            attempts++;
        }

        plugin.getLogger().warning("⚠ Gave up after " + maxAttempts + " attempts to avoid portal collision.");
        return clamped;
    }



    public boolean isObsidianNearby(Location base) {
        World world = base.getWorld();
        if (world == null) return false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = base.clone().add(dx, dy, dz).getBlock();
                    if (b.getType() == Material.OBSIDIAN) return true;
                }
            }
        }
        return false;
    }

    private boolean isFrameMaterial(Material mat) {
        return mat == Material.OBSIDIAN || mat == Material.DIAMOND_BLOCK;
    }

    public PortalFrame scanFullPortalFrame(Location origin) {
    	//return scanFullPortalFrame(origin, new HashSet<>(), true);
    	return scanFullPortalFrame(origin, new HashSet<>(), true, false);

    }
    public PortalFrame scanFullPortalFrame(Location origin, Set<Location> visited, boolean suppressDuplicate) {
        return scanFullPortalFrame(origin, visited, suppressDuplicate, false);
    }

    public PortalFrame scanFullPortalFrame(Location origin, Set<Location> visited, boolean suppressDuplicate, boolean isFromDispenser) {
        // 🚫 SCAN LOCK to prevent rapid repeat scans of the same spot
    	Location center = origin.getBlock().getLocation(); // ← Keep this line as is

    	String key = center.getWorld().getName() + "::" + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ();
    	long now = System.currentTimeMillis();
    	
        String existingCode = getLinkCodeFromBlock(origin);
        if (existingCode != null) {
            plugin.getLogger().info("🔗 Found existing portal with code: " + existingCode);
            return null; // Or return existing frame if needed
        }
        
    	if (suppressDuplicate) {
    	    long last = recentScanLock.getOrDefault(key, 0L);
    	    if (now - last < 1000) {
    	        plugin.getLogger().warning("⏳ [SCAN] Skipping duplicate scan at: " + key);
    	        return null;
    	    }
    	    recentScanLock.put(key, now);
    	}
        if (origin == null || origin.getWorld() == null) {
            plugin.getLogger().warning("❌ [SCAN] Null origin or world");
            return null;
        }


        //FROM HERE DOWN IS DEBUGGER
        if (origin.getBlockX() == 0 && origin.getBlockY() == 0 && origin.getBlockZ() == 0) {
            plugin.getLogger().warning("🛑 SCAN STARTED FROM (0,0,0) — TRACKING DOWN SOURCE");
            Thread.dumpStack(); // still useful for full trace
            return null;
        }

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stack.length; i++) {
            if (!stack[i].getClassName().contains("PortalManager")) {
                plugin.getLogger().warning("📌 Actual scanFullPortalFrame caller: " +
                    stack[i].getClassName() + "::" + stack[i].getMethodName() + " @ line " + stack[i].getLineNumber());
                break;
            }
        }
        //END OF DEBUGGERS

        World world = origin.getWorld();
        if (world == null) return null;

        //Location center = origin.getBlock().getLocation();
        if (visited.contains(center)) {
            plugin.getLogger().warning("🔁 [SCAN] Skipping already visited location: " + formatLoc(center));
            return null;
        }
        visited.add(center);
        plugin.getLogger().info("🌀 [SCAN] Scanning from portal block: " + formatLoc(center));

        Direction.Axis axis = detectPortalAxis(center);
        int dx = axis == Direction.Axis.X ? 1 : 0;
        int dz = axis == Direction.Axis.Z ? 1 : 0;

        /*while (world.getBlockAt(center.clone().add(0, -1, 0)).getType() == Material.NETHER_PORTAL) {
            center.add(0, -1, 0);
        }*/
        if (!isFromDispenser) {
            while (world.getBlockAt(center.clone().add(0, -1, 0)).getType() == Material.NETHER_PORTAL) {
                center.add(0, -1, 0);
            }
        } else {
            plugin.getLogger().info("🎯 Dispenser scan: skipping downward adjustment");
        }



        Location left = walkUntilNotPortal(center.clone(), -dx, 0, -dz);
        Location right = walkUntilNotPortal(center.clone(), dx, 0, dz);
        int width = Math.abs(right.getBlockX() - left.getBlockX() + right.getBlockZ() - left.getBlockZ()) + 1;

        int height = 1;
        while (world.getBlockAt(left.getBlockX(), center.getBlockY() + height, left.getBlockZ()).getType() == Material.NETHER_PORTAL) {
            height++;
        }

        if (width < 2 || height < 3) {
            plugin.getLogger().warning("❌ [SCAN] Frame too small. Width: " + width + ", Height: " + height);
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
                plugin.getLogger().warning("❌ Bottom edge block invalid at: " + formatLoc(b));
                valid = false;
            }
            if (!isFrame(t.getBlock().getType())) {
                plugin.getLogger().warning("❌ Top edge block invalid at: " + formatLoc(t));
                valid = false;
            }
        }

        for (int j = 1; j <= height; j++) {
            Location l = bottomLeft.clone().add(0, j, 0);
            Location r = bottomRight.clone().add(0, j, 0);
            if (!isFrame(l.getBlock().getType())) {
                plugin.getLogger().warning("❌ Left edge block invalid at: " + formatLoc(l));
                valid = false;
            }
            if (!isFrame(r.getBlock().getType())) {
                plugin.getLogger().warning("❌ Right edge block invalid at: " + formatLoc(r));
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
                    plugin.getLogger().warning("❌ Interior block invalid at: " + formatLoc(loc) + " was " + m);
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
                plugin.getLogger().info("💎 Diamond corner at: " + formatLoc(loc));
            }
        }

        plugin.getLogger().info("✅ [SCAN] Portal interior size: " + width + "w × " + height + "h (usable space)");
        plugin.getLogger().info("🧱 Frame outer size: " + (width + 2) + "w × " + (height + 2) + "h (including obsidian)");
        plugin.getLogger().info("🧭 Orientation axis: " + axis.name());
        plugin.getLogger().info("🔲 Frame corner coordinates:");
        plugin.getLogger().info("  ◼ Bottom-Left : " + formatLoc(bottomLeft));
        plugin.getLogger().info("  ◻ Bottom-Right: " + formatLoc(bottomRight));
        plugin.getLogger().info("  ◽ Top-Left    : " + formatLoc(topLeft));
        plugin.getLogger().info("  ◼ Top-Right   : " + formatLoc(topRight));

        plugin.getLogger().info("🟪 Portal blocks found: " + portalCount);
        plugin.getLogger().info("💎 Diamond corners: " + diamonds);

        for (Player p : world.getPlayers()) {
            showPortalScanVisualization(p, bottomLeft, width, height, axis);
        }

        if (bottomLeft == null || bottomRight == null || topLeft == null || topRight == null) {
            plugin.getLogger().warning("❌ [SCAN] Failed to determine all frame corners");
            return null;
        }
        return new PortalFrame(width + 2, height + 2, axis.name(),
        	    bottomLeft, bottomRight, topLeft, topRight, portalBlocks);

    }

    private Location walkUntilNotPortal(Location start, int dx, int dy, int dz) {
        Location current = start.clone();
        while (current.getBlock().getType() == Material.NETHER_PORTAL) {
            current.add(dx, dy, dz);
        }
        return current.subtract(dx, dy, dz);
    }

    public void showPortalScanVisualization(Player player, Location bottomLeft, int width, int height, Direction.Axis axis) {
        World world = bottomLeft.getWorld();
        int dx = axis == Direction.Axis.X ? 1 : 0;
        int dz = axis == Direction.Axis.Z ? 1 : 0;

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
                world.spawnParticle(Particle.SPELL_WITCH, inside, 1, 0, 0, 0, 0);
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

        plugin.getLogger().info("🎆 [SCAN] Portal preview rendered.");
    }

    private boolean isFrame(Material type) {
        return type == Material.OBSIDIAN || type == Material.DIAMOND_BLOCK;
    }

    private boolean isPortalOrAir(Block block) {
        if (!block.getChunk().isLoaded()) return false;
        Material type = block.getType();
        return type == Material.AIR || type == Material.NETHER_PORTAL;
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
                    plugin.getLogger().info("⚠️ Missing portal blocks — filled manually.");
                } else {
                    plugin.getLogger().info("✅ Portal verified: " + verified + " NETHER_PORTAL blocks found.");
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
 // INSERT (new helper) — PortalManager.java, inside class
    private @org.jetbrains.annotations.Nullable org.bukkit.Location findNearbyPortalBlock(org.bukkit.Location center, int maxDistanceBlocks) {
        if (center == null || center.getWorld() == null) return null;
        org.bukkit.World w = center.getWorld();
        final int r = Math.max(1, maxDistanceBlocks);

        // Tight cube around the expected frame; shallow Y band is plenty for portals
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
            drawOutlineCube(player, centered, 1.0, Particle.REDSTONE);


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
        player.spawnParticle(Particle.REDSTONE, loc, 1, 0, 0, 0, 0, dust);
    }
    
    public List<DetectionRegion> getDetectionRegions() {
        return detectionRegions;
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
                        plugin.getLogger().info("🟩 Loaded region from file: " + formatLoc(min) + " → " + formatLoc(max));
                    }
                }
            }
        }
    }
    
    public void clearQueuedPlayerConfigs() {
        queuedPlayerConfigs.clear();
        plugin.getLogger().info("🧹 Cleared all queued player configs from memory.");
    }

    public void reloadPortalMap() {
        File file = new File(plugin.getDataFolder(), "portalMap.yml");
        if (!file.exists()) {
            plugin.getLogger().warning("⚠ portalMap.yml not found — creating a new one.");
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("❌ Failed to create portalMap.yml");
                e.printStackTrace();
                return;
            }
        }

        this.globalPortalMap = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("🔄 Reloaded portalMap.yml into memory.");
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
            plugin.getLogger().warning("⚠ getFrameByLinkCode was called with null arguments.");
            return null;
        }

        String worldName = world.getName();
        plugin.getLogger().info("[FRAME-DEBUG] Looking up frame for linkCode: " + linkCode + " in world: " + worldName);

        YamlConfiguration portalMap = getPortalMap();
        if (!portalMap.contains(linkCode + ".owner")) {
            plugin.getLogger().warning("⚠ LinkCode " + linkCode + " does not exist in portalMap.");
            return null;
        }

        UUID ownerUUID = UUID.fromString(portalMap.getString(linkCode + ".owner"));
        YamlConfiguration config = getOrCreatePlayerConfig(ownerUUID);

        String base = "links." + linkCode + "." + worldName + ".frame";
        if (!config.contains(base + ".orientation")) {
            plugin.getLogger().warning("⚠ Frame orientation missing at: " + base + ".orientation");
            return null;
        }

        String orientation = config.getString(base + ".orientation");
        ConfigurationSection cornerSec = config.getConfigurationSection(base + ".corner");
        if (cornerSec == null) {
            plugin.getLogger().warning("⚠ Frame corner section missing at: " + base + ".corner");
            return null;
        }

        // Parse corners
        String rawBL = cornerSec.getString("Bottom-Left");
        String rawBR = cornerSec.getString("Bottom-Right");
        String rawTL = cornerSec.getString("Top-Left");
        String rawTR = cornerSec.getString("Top-Right");

        plugin.getLogger().info("[FRAME-DEBUG] Parsed corner strings:");
        plugin.getLogger().info("  Bottom-Left: " + rawBL);
        plugin.getLogger().info("  Bottom-Right: " + rawBR);
        plugin.getLogger().info("  Top-Left: " + rawTL);
        plugin.getLogger().info("  Top-Right: " + rawTR);

        Location bottomLeft = parseLocationString(world, rawBL);
        Location bottomRight = parseLocationString(world, rawBR);
        Location topLeft = parseLocationString(world, rawTL);
        Location topRight = parseLocationString(world, rawTR);

        if (bottomLeft == null || bottomRight == null || topLeft == null || topRight == null) {
            plugin.getLogger().warning("⚠ One or more portal corners could not be parsed for linkCode: " + linkCode);
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

        plugin.getLogger().info("[FRAME-DEBUG] Final frame: " + width + "x" + height + " (" + orientation + ")");
        plugin.getLogger().info("[FRAME-DEBUG] Frame BL location: " + bottomLeft);

        return new PortalFrame(width, height, orientation, bottomLeft, bottomRight, topLeft, topRight, new ArrayList<>());
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
            plugin.getLogger().warning("⚠ Failed to parse location string: " + raw);
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


    private Location getLocationFromSection(World world, ConfigurationSection sec) {
        if (sec == null || world == null) return null;
        return new Location(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"));
    }
    /*public String getOrCreateLinkCode(String playerName) {
        if (playerName == null) return null;

        UUID uuid = getUUIDFromName(playerName);
        if (uuid == null) return null;

        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        ConfigurationSection links = config.getConfigurationSection("links");

        if (links != null && !links.getKeys(false).isEmpty()) {
            for (String existingCode : links.getKeys(false)) {
                return existingCode; // Return first existing linkCode
            }
        }

        // Ensure new code is unused
        String newCode;
        do {
            newCode = generateLinkCode();
        } while (config.contains("links." + newCode) || getPortalMap().contains(newCode));

        return newCode;
    }*/

    public String generateUniqueLinkCode(UUID uuid) {
        YamlConfiguration config = getOrCreatePlayerConfig(uuid);
        Set<String> existingCodes = config.getConfigurationSection("links") != null
            ? config.getConfigurationSection("links").getKeys(false)
            : Collections.emptySet();

        String code;
        do {
            code = UUID.randomUUID().toString().substring(0, 6);
        } while (existingCodes.contains(code) || getPortalMap().contains(code));

        return code;
    }
    /*public String generateLinkCode() {
        return Integer.toHexString((int) (Math.random() * 0xFFFFFF)).toLowerCase();
    }*/
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
                    plugin.getLogger().warning("⚠ World not found for portal region: " + worldName);
                    continue;
                }

                Location min = parseCoord(minString);
                Location max = parseCoord(maxString);

                if (min == null || max == null) {
                    plugin.getLogger().warning("⚠ Failed to parse interior bounds for link: " + linkCode);
                    continue;
                }

                min.setWorld(world);
                max.setWorld(world);

                DetectionRegion region = new DetectionRegion(min, max, linkCode);
                detectionRegions.add(region);

                plugin.getLogger().info("✅ Reloaded portal zone for " + linkCode + " in world " + worldName);
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
            plugin.getLogger().warning("⚠️ Missing portalA or portalB for linkCode: " + linkCode);
            return;
        }

        String destinationWorld;
        if (currentWorldName.equals(portalA)) {
            destinationWorld = portalB;
        } else if (currentWorldName.equals(portalB)) {
            destinationWorld = portalA;
        } else {
            plugin.getLogger().warning("⚠️ Current world not listed in portalA or portalB for linkCode: " + linkCode);
            return;
        }

        String baseKey = "links." + linkCode + "." + destinationWorld + ".location";

        if (!playerData.contains(baseKey)) {
            plugin.getLogger().warning("⚠️ No saved return location for player in destination world: " + destinationWorld + " under linkCode: " + linkCode);
            plugin.getLogger().warning("🔍 baseKey: " + baseKey);

            ConfigurationSection linkSection = playerData.getConfigurationSection("links." + linkCode);
            if (linkSection != null) {
                plugin.getLogger().info("📖 Available worlds under link: " + linkSection.getKeys(false));
                ConfigurationSection destSection = linkSection.getConfigurationSection(destinationWorld);
                if (destSection != null) {
                    plugin.getLogger().info("📦 Keys under destination world: " + destSection.getKeys(false));
                } else {
                    plugin.getLogger().warning("❌ Could not find section: " + destinationWorld);
                }
            }
            return;
        }

        Location destination = deserializeLocation(playerData, baseKey);

        if (destination != null) {
            plugin.getLogger().info("🌍 Current world: " + currentWorldName);
            plugin.getLogger().info("🧭 Destination world: " + destinationWorld);
            plugin.getLogger().info("🔗 linkCode: " + linkCode);
            plugin.getLogger().info("🚀 Teleporting player " + player.getName() + " using linkCode: " + linkCode);

            try {
                player.getClass().getMethod("teleportAsync", Location.class); // reflection test
                player.getClass().getMethod("teleportAsync", Location.class)
                      .invoke(player, destination);
            } catch (Exception e) {
                player.teleport(destination); // fallback to sync
            }

        } else {
            plugin.getLogger().warning("❌ Failed to deserialize destination for linkCode: " + linkCode);
        }
    }

    public Location deserializeLocation(YamlConfiguration config, String path) {
        if (!config.contains(path)) return null;

        //World world = Bukkit.getWorld(config.getString(path + ".world"));
        String worldName = config.getString(path + ".world");
        if (worldName == null) {
            plugin.getLogger().severe("❌ deserializeLocation() failed — world name is null at: " + path);
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().severe("❌ deserializeLocation() failed — world '" + worldName + "' is not loaded.");
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
	                plugin.getLogger().warning("[GNP] Destination world not loaded: " + destWorldName);
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
	                plugin.getLogger().warning("[GNP] Could not verify B portal for " + linkCode
	                        + " in " + destW.getName());
	                return;
	            }
	
	            // Final re-scan + marker + detection region persist
	            java.util.Set<org.bukkit.Location> visited = new java.util.HashSet<>();
	            PortalFrame newFrame = scanFullPortalFrame(formed, visited, false, false);
	            if (newFrame == null) {
	                plugin.getLogger().warning("[GNP] B-side scan failed after creation for " + linkCode);
	                return;
	            }
	
	            spawnPortalMarker(newFrame, linkCode, uuid);
	            registerDetectionFor(linkCode, uuid, newFrame);
	
	            // (Removed addPortalLinkToMap — undefined / redundant)
	            plugin.getLogger().info("✅ [PAIR] Portal-B registered for " + linkCode
	                    + " at " + formatLoc(newFrame.bottomLeft));
	
	        } catch (Throwable t) {
	            plugin.getLogger().warning("[GNP] requestPairedPortalBuild failed: " + t.getMessage());
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
	 public void onWorldLoadedKickDeferredBuilds(String worldNameJustLoaded) {
	     var list = deferred.remove(worldNameJustLoaded);
	     if (list == null || list.isEmpty()) return;
	     plugin.getLogger().info("[GNP] Running " + list.size() + " deferred paired builds for world " + worldNameJustLoaded);
	     for (DeferredBuild db : list) {
	         org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
	             try {
	            	 forceGeneratePairedPortal(db.linkCode, db.originWorld, db.uuid, db.frame);
	             } catch (Exception ex) {
	                 plugin.getLogger().warning("[GNP] Deferred build failed (" + db.linkCode + "): " + ex.getMessage());
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
		        plugin.getLogger().warning("[GNP] Failed to save A-side interior for " + linkCode + ": " + e.getMessage());
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
	             plugin.getLogger().warning("[GNP] Queue job failed: " + t.getMessage());
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
	// === ADD: hasMarkerInFrame (place this immediately ABOVE the final closing brace of PortalManager)
	public boolean hasMarkerInFrame(String linkCode, PortalFrame frame) {
	    World world = frame.bottomLeft.getWorld();
	    if (world == null) return false;

	    BoundingBox box = BoundingBox.of(frame.bottomLeft, frame.topRight).expand(0.5);
	    int minX = Math.min(frame.bottomLeft.getBlockX(), frame.topRight.getBlockX());
	    int maxX = Math.max(frame.bottomLeft.getBlockX(), frame.topRight.getBlockX());
	    int minY = Math.min(frame.bottomLeft.getBlockY(), frame.topRight.getBlockY());
	    int maxY = Math.max(frame.bottomLeft.getBlockY(), frame.topRight.getBlockY());
	    int minZ = Math.min(frame.bottomLeft.getBlockZ(), frame.topRight.getBlockZ());
	    int maxZ = Math.max(frame.bottomLeft.getBlockZ(), frame.topRight.getBlockZ());

	    for (org.bukkit.entity.Entity e : world.getNearbyEntities(box)) {
	        if (!(e instanceof org.bukkit.entity.ArmorStand stand)) continue;
	        if (!stand.getScoreboardTags().contains("gnp_marker")) continue;

	        String code = stand.getPersistentDataContainer().get(
	            new org.bukkit.NamespacedKey(plugin, "linkCode"),
	            org.bukkit.persistence.PersistentDataType.STRING
	        );
	        if (!linkCode.equalsIgnoreCase(code)) continue;

	        org.bukkit.Location loc = stand.getLocation();
	        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
	        if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
	            return true;
	        }
	    }
	    return false;
	}
	// === END ADD

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
	
	    public @org.jetbrains.annotations.Nullable org.bukkit.Location findNearbyPortalBlock(
	            org.bukkit.Location center, int maxDistanceBlocks) {
	        return findNearbyPortalBlockTight(center, maxDistanceBlocks);
	    }
    }