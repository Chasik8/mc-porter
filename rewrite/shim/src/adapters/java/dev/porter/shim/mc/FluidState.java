// porter:adapter (generic) - 1.19.2 FluidState of a block state: 1.7.10 liquids (BlockLiquid, IFluidBlock).
package dev.porter.shim.mc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;

public final class FluidState {
    public static final FluidState EMPTY = new FluidState(null, false);
    private final Fluid fluid;
    private final boolean source;

    private FluidState(Fluid fluid, boolean source) {
        this.fluid = fluid;
        this.source = source;
    }

    public static FluidState of(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof IFluidBlock) {
            return new FluidState(((IFluidBlock) b).getFluid(), s.getMeta() == 0);
        }
        if (b instanceof BlockLiquid) {
            Fluid f = b.getMaterial() == Material.lava ? FluidRegistry.LAVA : FluidRegistry.WATER;
            return new FluidState(f, s.getMeta() == 0);
        }
        return EMPTY;
    }

    public boolean isEmpty() { return fluid == null; }

    public boolean isSource() { return fluid != null && source; }

    public Fluid getType() { return fluid; }

    public int getAmount() { return fluid == null ? 0 : source ? 8 : 7; }

    /** Fluid.defaultFluidState() */
    public static FluidState ofFluid(Fluid f) { return new FluidState(f, true); }

    /** Fluid.isSource(state): 1.7.10 registered fluids are source fluids. */
    public static boolean isSourceFluid(Fluid f, FluidState s) { return s == null || s.isSource(); }
}
