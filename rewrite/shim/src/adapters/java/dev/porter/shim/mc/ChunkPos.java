// porter:adapter (generic) - 1.19.2 ChunkPos (static long packing helpers + instance) on 1.7.10 ChunkCoordIntPair.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.world.ChunkCoordIntPair;

public class ChunkPos extends ChunkCoordIntPair {
    public ChunkPos(int x, int z) { super(x, z); }

    public ChunkPos(BlockPos p) { super(p.getX() >> 4, p.getZ() >> 4); }

    public ChunkPos(long packed) { super(getX(packed), getZ(packed)); }

    public static long asLong(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; }

    public static int getX(long packed) { return (int) (packed & 0xFFFFFFFFL); }

    public static int getZ(long packed) { return (int) (packed >>> 32 & 0xFFFFFFFFL); }

    public long toLong() { return asLong(chunkXPos, chunkZPos); }

    public int getMinBlockX() { return chunkXPos << 4; }

    public int getMinBlockZ() { return chunkZPos << 4; }
}
