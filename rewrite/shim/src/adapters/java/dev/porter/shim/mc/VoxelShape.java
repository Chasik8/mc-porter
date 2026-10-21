// porter:adapter (generic) - 1.19.2 VoxelShape as one axis-aligned box in block units (0..1). Block.box(...) (pixel
// units) and Shapes.block()/empty()/or(...) build it; StateBlock applies it as the 1.7.10 block bounds. Unions keep
// their bounding box (1.7.10 blocks have one box).
package dev.porter.shim.mc;

import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;

public final class VoxelShape {
    private static final VoxelShape BLOCK = new VoxelShape(0, 0, 0, 1, 1, 1);
    private static final VoxelShape EMPTY = new VoxelShape(0, 0, 0, 0, 0, 0);
    public final double minX, minY, minZ, maxX, maxY, maxZ;

    private VoxelShape(double x0, double y0, double z0, double x1, double y1, double z1) {
        minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
    }

    /** 1.19.2 Block.box(x0, y0, z0, x1, y1, z1) in pixels */
    public static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new VoxelShape(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0);
    }

    /** 1.19.2 Shapes.box(...) in block units */
    public static VoxelShape create(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new VoxelShape(x0, y0, z0, x1, y1, z1);
    }

    public static VoxelShape block() { return BLOCK; }

    public static VoxelShape empty() { return EMPTY; }

    public static VoxelShape or(VoxelShape a, VoxelShape... more) {
        VoxelShape r = a;
        for (VoxelShape b : more) {
            if (b.isEmpty()) continue;
            r = r.isEmpty() ? b : new VoxelShape(Math.min(r.minX, b.minX), Math.min(r.minY, b.minY), Math.min(r.minZ, b.minZ),
                Math.max(r.maxX, b.maxX), Math.max(r.maxY, b.maxY), Math.max(r.maxZ, b.maxZ));
        }
        return r;
    }

    public boolean isEmpty() { return maxX <= minX || maxY <= minY || maxZ <= minZ; }

    public boolean isFullBlock() { return minX <= 0 && minY <= 0 && minZ <= 0 && maxX >= 1 && maxY >= 1 && maxZ >= 1; }

    public VoxelShape move(double dx, double dy, double dz) {
        return new VoxelShape(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public AxisAlignedBB bounds() { return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }

    public void applyBounds(Block b) {
        if (isEmpty()) b.setBlockBounds(0, 0, 0, 0, 0, 0);
        else b.setBlockBounds((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }
}
