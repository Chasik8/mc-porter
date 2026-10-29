// porter:adapter (the mod-specific, PRIVATE) - world helpers of the fluid pipes: fluid tick scheduling and the combined light level.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

public final class PipeWorld {
    private PipeWorld() {}

    /** LevelAccessor#scheduleTick(pos, fluid, delay): schedule a block update of the flowing water / lava block. */
    public static void scheduleFluidTick(World world, BlockPos pos, Fluid fluid, int delay) {
        if (world == null || fluid == null) return;
        if (fluid == FluidRegistry.LAVA) {
            world.scheduleBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), Blocks.flowing_lava, delay);
        } else if (fluid == FluidRegistry.WATER) {
            world.scheduleBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), Blocks.flowing_water, delay);
        }
    }

    /** LevelReader#getMaxLocalRawBrightness(pos): max(block light, sky light - sky darkness), what getBlockLightValue returns. */
    public static int maxLocalRawBrightness(World world, BlockPos pos) {
        return world.getBlockLightValue(pos.getX(), pos.getY(), pos.getZ());
    }
}
