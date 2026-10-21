// porter:adapter (generic) - 1.19.2 Heightmap.Types + Level.getHeight(type, x, z): 1.7.10 keeps one height map (the
// first y above the top light-blocking block), used for every type.
package dev.porter.shim.mc;

import net.minecraft.world.World;

public final class Heightmap {
    private Heightmap() {}

    public enum Types { WORLD_SURFACE_WG, WORLD_SURFACE, OCEAN_FLOOR_WG, OCEAN_FLOOR, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES }

    public static int getHeight(World w, Types type, int x, int z) { return w.getHeightValue(x, z); }
}
