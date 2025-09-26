package com.nullble.goatnetherportals;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public class Portal {

    private String linkCode;
    private UUID owner;
    private Location location;
    private boolean diamondOverride;
    private PortalFrame frame;

    public Portal(String linkCode, UUID owner, Location location, PortalFrame frame) {
        this.linkCode = linkCode;
        this.owner = owner;
        this.location = location;
        this.frame = frame;
        this.diamondOverride = false; // Default
    }

    // Getters
    public String getLinkCode() {
        return linkCode;
    }

    public UUID getOwner() {
        return owner;
    }

    public Location getLocation() {
        return location;
    }

    public World getWorld() {
        return location.getWorld();
    }

    public boolean hasDiamondOverride() {
        return diamondOverride;
    }

    public PortalFrame getFrame() {
        return this.frame;
    }

    // Setters
    public void setLinkCode(String linkCode) {
        this.linkCode = linkCode;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public void setDiamondOverride(boolean diamondOverride) {
        this.diamondOverride = diamondOverride;
    }

    public void setFrame(PortalFrame frame) {
        this.frame = frame;
    }
}