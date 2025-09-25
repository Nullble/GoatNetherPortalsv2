package com.nullble.goatnetherportals;

import org.bukkit.Location;
import org.bukkit.World;

public class DetectionRegion {
    private final Location min;
    private final Location max;
    private final String linkCode;

    public DetectionRegion(Location corner1, Location corner2, String linkCode) {
        if (!corner1.getWorld().equals(corner2.getWorld())) {
            throw new IllegalArgumentException("DetectionRegion corners must be in the same world.");
        }
        this.min = getMin(corner1, corner2);
        this.max = getMax(corner1, corner2);
        this.linkCode = linkCode;
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || min == null || max == null) return false;
        if (!loc.getWorld().equals(min.getWorld())) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        return x >= min.getBlockX() && x <= max.getBlockX()
            && y >= min.getBlockY() && y <= max.getBlockY()
            && z >= min.getBlockZ() && z <= max.getBlockZ();
    }


    public Location getMin() { return min; }

    public Location getMax() { return max; }

    public String getLinkCode() { return linkCode; }

    public World getWorld() { return min.getWorld(); }

    private Location getMin(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.min(a.getBlockX(), b.getBlockX()),
                Math.min(a.getBlockY(), b.getBlockY()),
                Math.min(a.getBlockZ(), b.getBlockZ()));
    }

    private Location getMax(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.max(a.getBlockX(), b.getBlockX()),
                Math.max(a.getBlockY(), b.getBlockY()),
                Math.max(a.getBlockZ(), b.getBlockZ()));
    }

    @Override
    public String toString() {
        return "Region{" + getWorld().getName() + " [" + format(min) + " to " + format(max) + "] → " + linkCode + "}";
    }

    private String format(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
    
    
}
