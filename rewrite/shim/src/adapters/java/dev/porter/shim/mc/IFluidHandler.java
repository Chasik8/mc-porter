// porter:adapter (generic) - Forge 1.19.2 IFluidHandler (tank-indexed). FluidAction is a boolean on 1.7.10
// (true = EXECUTE, like 1.7.10's doFill/doDrain). FluidStack is the 1.7.10 Forge class (empty = null).
package dev.porter.shim.mc;

import net.minecraftforge.fluids.FluidStack;

public interface IFluidHandler {
    int getTanks();

    FluidStack getFluidInTank(int tank);

    int getTankCapacity(int tank);

    boolean isFluidValid(int tank, FluidStack stack);

    int fill(FluidStack resource, boolean execute);

    FluidStack drain(FluidStack resource, boolean execute);

    FluidStack drain(int maxDrain, boolean execute);
}
