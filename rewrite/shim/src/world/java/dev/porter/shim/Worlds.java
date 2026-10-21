package dev.porter.shim;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Small static world helpers taking a {@link BlockPos}, used by rewrite rules when a position expression is not
 * side-effect free (pure positions are expanded inline to {@code x, y, z}). Each helper is a one-line delegation
 * to the 1.7.10 API (MCP names, Forge 10.13.4.1614). Deliberately NOT a Level/BlockEntity facade.
 */
public final class Worlds {
    private Worlds() {
    }

    public static TileEntity getTileEntity(IBlockAccess world, BlockPos pos) {
        return world.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
    }

    public static Block getBlock(IBlockAccess world, BlockPos pos) {
        return world.getBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int getBlockMetadata(IBlockAccess world, BlockPos pos) {
        return world.getBlockMetadata(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean isAirBlock(IBlockAccess world, BlockPos pos) {
        return world.isAirBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean blockExists(World world, BlockPos pos) {
        return world.blockExists(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean setBlockToAir(World world, BlockPos pos) {
        return world.setBlockToAir(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void markBlockForUpdate(World world, BlockPos pos) {
        world.markBlockForUpdate(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean isBlockIndirectlyGettingPowered(World world, BlockPos pos) {
        return world.isBlockIndirectlyGettingPowered(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int getStrongestIndirectPower(World world, BlockPos pos) {
        return world.getStrongestIndirectPower(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Position of a tile entity ({@code xCoord, yCoord, zCoord}). */
    public static BlockPos posOf(TileEntity tile) {
        return new BlockPos(tile.xCoord, tile.yCoord, tile.zCoord);
    }

    public static ForgeDirection toForge(Direction dir) {
        return dir == null ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(dir.get3DDataValue());
    }

    /** {@code ForgeDirection.UNKNOWN} (and null) map to null. */
    public static Direction fromForge(ForgeDirection dir) {
        return dir == null || dir == ForgeDirection.UNKNOWN ? null : Direction.from3DDataValue(dir.ordinal());
    }

    /** 1.7.10 side number (0..5) to a direction; anything else is null. */
    public static Direction fromSide(int side) {
        return side < 0 || side > 5 ? null : Direction.from3DDataValue(side);
    }
}
