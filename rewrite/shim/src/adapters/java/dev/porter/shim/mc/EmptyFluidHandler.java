// porter:adapter (the mod-specific, PRIVATE) - net.minecraftforge.fluids.capability.templates.EmptyFluidHandler on 1.7.10
// (the tank-indexed shim IFluidHandler with no tanks).
package dev.porter.shim.mc;


import net.minecraftforge.fluids.FluidStack;

public final class EmptyFluidHandler implements IFluidHandler {
    public static final EmptyFluidHandler INSTANCE = new EmptyFluidHandler();

    private EmptyFluidHandler() {}

    @Override
    public int getTanks() {
        return 0;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return null;
    }

    @Override
    public int getTankCapacity(int tank) {
        return 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return false;
    }

    @Override
    public int fill(FluidStack resource, boolean execute) {
        return 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, boolean execute) {
        return null;
    }

    @Override
    public FluidStack drain(int maxDrain, boolean execute) {
        return null;
    }
}
