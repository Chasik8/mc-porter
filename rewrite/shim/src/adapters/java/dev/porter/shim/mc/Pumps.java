// porter:adapter (the mod-specific, PRIVATE) - fluid-block tests of the the mod pumps (Pump, Ranged Pump) on 1.7.10:
// BucketPickup / LiquidBlock / BubbleColumnBlock have no 1.7.10 classes, the Forge IFluidBlock and the vanilla
// BlockLiquid stand in for them (BucketPickup is a plain Block here).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

public final class Pumps {
    private Pumps() {}

    /** block instanceof BucketPickup: any fluid block a pump can drain. */
    public static boolean isPickup(Object block) {
        return block instanceof BlockLiquid || block instanceof IFluidBlock;
    }

    /** block instanceof LiquidBlock: a flowing/static fluid block (vanilla or Forge fluid). */
    public static boolean isLiquid(Object block) {
        return block instanceof BlockLiquid || block instanceof IFluidBlock;
    }

    /** BucketPickup#pickupBlock(level, pos, state): drains the fluid block (the pickup "view" is the Block itself). */
    public static void pickupBlock(Object block, World world, BlockPos pos, BlockState state) {
        Block b = block instanceof Block ? (Block) block : state == null ? world.getBlock(pos.getX(), pos.getY(), pos.getZ()) : state.getBlock();
        if (b instanceof IFluidBlock) {
            ((IFluidBlock) b).drain(world, pos.getX(), pos.getY(), pos.getZ(), true);
        } else {
            world.setBlockToAir(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /** LevelTicks#clearArea(BoundingBox) of the fluid ticks: 1.7.10 keeps no separate fluid tick list. */
    public static void clearFluidTicks() {}
}
