// porter:adapter (the mod-specific, PRIVATE) - small 1.19.2 calls of the Rocket Miner (MiningRocketEntity), Chunkloader and
// Fusion Reactor on 1.7.10 (the mod batch 08b).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public final class HeavyB {
    /** First free DataWatcher id of the mining rocket (Entity itself uses 0..16 on 1.7.10). */
    public static final int TARGET_ID = 20;

    private HeavyB() {}

    // ---- ServerLevel#sendParticles(options, x, y, z, count, dx, dy, dz, speed)

    public static void sendParticles(World world, String name, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        if (world instanceof WorldServer) {
            ((WorldServer) world).func_147487_a(name, x, y, z, count, dx, dy, dz, speed);
        }
    }

    // ---- new AABB(BlockPos, BlockPos), AABB#deflate(double)

    public static AxisAlignedBB aabb(BlockPos a, BlockPos b) {
        return AxisAlignedBB.getBoundingBox(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public static AxisAlignedBB deflate(AxisAlignedBB box, double d) {
        return box.expand(-d, -d, -d);
    }

    // ---- BlockEntity#saveWithoutMetadata(): the tile's data without id and position

    public static NBTTagCompound saveWithoutMetadata(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        tag.removeTag("id");
        tag.removeTag("x");
        tag.removeTag("y");
        tag.removeTag("z");
        return tag;
    }

    /** tile.load(tag) of data saved by saveWithoutMetadata: the tile keeps its own position. */
    public static void loadKeepPos(TileEntity tile, NBTTagCompound saved) {
        NBTTagCompound tag = (NBTTagCompound) saved.copy();
        tag.setInteger("x", tile.xCoord);
        tag.setInteger("y", tile.yCoord);
        tag.setInteger("z", tile.zCoord);
        tile.readFromNBT(tag);
    }

    // ---- MiningRocketEntity: SynchedEntityData BlockPos TARGET (a String DataWatcher entry), Entity#move(MoverType, Vec3)

    public static void defineTarget(DataWatcher data) {
        data.addObject(TARGET_ID, Long.toString(BlockPos.ZERO.asLong()));
    }

    public static void setTarget(DataWatcher data, BlockPos pos) {
        data.updateObject(TARGET_ID, Long.toString(pos.asLong()));
    }

    public static BlockPos getTarget(DataWatcher data) {
        try {
            return BlockPos.of(Long.parseLong(data.getWatchableObjectString(TARGET_ID)));
        } catch (RuntimeException e) {
            return BlockPos.ZERO;
        }
    }

    /** Entity#move(MoverType.SELF, delta) */
    public static void move(Entity entity, Vec3 delta) {
        entity.moveEntity(delta.xCoord, delta.yCoord, delta.zCoord);
    }
}
