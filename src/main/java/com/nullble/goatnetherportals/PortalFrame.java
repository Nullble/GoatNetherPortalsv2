package com.nullble.goatnetherportals;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.List;

public class PortalFrame {
    public final int width;
    public final int height;
    public final String orientation;
    public Location cornerMarker; // Exact location of the marker (if created)

    public final Location bottomLeft;
    public final Location bottomRight;
    public final Location topLeft;
    public final Location topRight;

    public final List<Block> portalBlocks;
    public final int diamondBlocks;

    public PortalFrame(int width, int height, String orientation,
                       Location bottomLeft, Location bottomRight,
                       Location topLeft, Location topRight,
                       List<Block> portalBlocks, int diamondBlocks) {
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.portalBlocks = portalBlocks;
        this.diamondBlocks = diamondBlocks;
    }
}