// porter:adapter (the mod-specific, PRIVATE) - BlockState#canSurvive(level, pos) on 1.7.10: Block.canBlockStay(world, x, y, z).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public final class Survive {
    private Survive() {}

    public static boolean canSurvive(BlockState state, IBlockAccess world, BlockPos pos) {
        if (state == null || !(world instanceof World)) return true;
        Block b = state.getBlock();
        return b == null || b.canBlockStay((World) world, pos.getX(), pos.getY(), pos.getZ());
    }
}
