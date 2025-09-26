package com.nullble.goatnetherportals;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import com.nullble.goatnetherportals.GNPCommand;

import net.luckperms.api.LuckPerms;


public class GoatNetherPortals extends JavaPlugin {

    private static GoatNetherPortals instance;
    private PortalManager portalManager;
    private LuckPerms luckPerms;
    private PendingDeleteManager deleteManager;
    private File globalPortalMapFile;
    private YamlConfiguration globalPortalMap;
    private final Set<UUID> inspectingPlayers = new HashSet<>();
    private boolean debugMarkersVisible = false;
    private boolean debugMode = false;
    private InspectListener inspectListener;
    private final Random random = new Random();

    public static GoatNetherPortals getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // ✅ Setup LuckPerms API
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }

        // ✅ Ensure plugin folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // ✅ Save default config if not present
        saveDefaultConfig();
        reloadConfig();
        this.debugMode = getConfig().getBoolean("debug", false);

        // ✅ Initialize global portal map file
        globalPortalMapFile = new File(getDataFolder(), "portalMap.yml");
        if (!globalPortalMapFile.exists()) {
            try {
                globalPortalMapFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("❌ Could not create portalMap.yml");
            }
        }
        globalPortalMap = YamlConfiguration.loadConfiguration(globalPortalMapFile);

        // ✅ Initialize managers
        this.portalManager = new PortalManager(this);
        portalManager.loadAllInteriorRegionsFromFiles();
        this.deleteManager = new PendingDeleteManager();
        this.inspectListener = new InspectListener(this);
        getServer().getPluginManager().registerEvents(inspectListener, this);


        Bukkit.getPluginManager().registerEvents(new InspectListener(this), this);

        // ✅ Register portal event listener
        getServer().getPluginManager().registerEvents(new PortalListener(this, portalManager), this);
     // Inside onEnable()
        //getServer().getPluginManager().registerEvents(new PortalListener(this, portalManager), this);
        getServer().getPluginManager().registerEvents(new PortalEntryListener(this), this);
        
        //getServer().getPluginManager().registerEvents(new PortalEntryListener(this), this);
		// ✅ Register portal region listener


        // ✅ /gnp command handler
        getCommand("gnp").setExecutor(new GNPCommand(this));
        getCommand("gnp").setTabCompleter(new GNPCommandTabCompleter());

        getLogger().info("✅ GoatNetherPortals enabled.");
        
        org.bukkit.Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) {
                String w = e.getWorld().getName();
                getPortalManager().onWorldLoadedKickDeferredBuilds(w);
            }
        }, this);

    }

    @Override
    public void onDisable() {
        getLogger().info("❌ GoatNetherPortals disabled.");
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public LuckPerms getLuckPerms() {
        return this.luckPerms;
    }

    public PendingDeleteManager getDeleteManager() {
        return deleteManager;
    }
    public boolean isForceReturnWorld(String worldName) {
        return getConfig().getStringList("forceReturnWorlds").contains(worldName);
    }

    public void loadGlobalPortalMap() {
        globalPortalMapFile = new File(getDataFolder(), "global_portalmap.yml");
        if (!globalPortalMapFile.exists()) {
            try {
                globalPortalMapFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("❌ Failed to create global_portalmap.yml");
                e.printStackTrace();
            }
        }
        globalPortalMap = YamlConfiguration.loadConfiguration(globalPortalMapFile);
    }
    
    public YamlConfiguration getGlobalPortalMap() {
        return globalPortalMap;
    }

    public File getGlobalPortalMapFile() {
        return globalPortalMapFile;
    }

    
    public void saveGlobalMap() {
        try {
            globalPortalMap.save(globalPortalMapFile);
        } catch (IOException e) {
        	getLogger().warning("❌ Failed to save global portalMap.yml");
        }
    }
    
    public boolean isInspecting(UUID uuid) {
        return inspectingPlayers.contains(uuid);
    }

    public void toggleInspect(UUID uuid) {
        if (inspectingPlayers.contains(uuid)) {
            inspectingPlayers.remove(uuid);
        } else {
            inspectingPlayers.add(uuid);
        }
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
    }
    
    public boolean toggleDebugMarkers() {
        debugMarkersVisible = !debugMarkersVisible;
        return debugMarkersVisible;
    }
    
    public InspectListener getInspectListener() {
        return this.inspectListener;
    }


    public void updateAllPortalMarkersDebugVisibility() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand stand)) continue;

                PersistentDataContainer data = stand.getPersistentDataContainer();
                NamespacedKey key = new NamespacedKey(this, "linkCode");

                if (!data.has(key, PersistentDataType.STRING)) continue; // not a portal marker

                if (debugMarkersVisible) {
                    stand.setCustomName("§bPortalMarker");
                    stand.setCustomNameVisible(true);
                    stand.setInvisible(false);
                } else {
                    stand.setCustomNameVisible(false);
                    stand.setInvisible(true);
                }
            }
        }
    }
    
    public Random getRandom() {
        return random;
    }

    /*public String getOppositeWorld(String worldName) {
        if (worldName.endsWith("_nether")) {
            return worldName.replace("_nether", "");
        } else {
            return worldName + "_nether";
        }
    }*/

    public String getOppositeWorld(String worldName) {
        if (worldName == null) return null;
        
        if (worldName.endsWith("_nether")) {
            return worldName.replace("_nether", "");
        } else {
            String netherWorld = worldName + "_nether";
            return Bukkit.getWorld(netherWorld) != null ? netherWorld : null;
        }
    }
    
    public void loadPlayerData(UUID uuid) {
        File file = new File(getDataFolder(), "playerdata/" + uuid.toString() + ".yml");
        if (!file.exists()) {
            getLogger().warning("⚠️ Tried to load portal data, but no file exists for: " + uuid);
            return;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            getPortalManager().loadFromConfig(uuid, config);
            getLogger().info("✅ Loaded portal data for: " + uuid);
        } catch (Exception e) {
            getLogger().severe("❌ Failed to load portal data for: " + uuid);
            e.printStackTrace();
        }
    }

}
