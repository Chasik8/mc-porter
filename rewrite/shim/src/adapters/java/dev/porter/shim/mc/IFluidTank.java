// porter:adapter (generic) - Forge 1.19.2 IFluidTank on top of the 1.7.10 one (same fill/drain(int) shape).
package dev.porter.shim.mc;

import net.minecraftforge.fluids.FluidStack;

public interface IFluidTank extends net.minecraftforge.fluids.IFluidTank {
    boolean isFluidValid(FluidStack stack);

    FluidStack drain(FluidStack resource, boolean execute);

    @Override
    default net.minecraftforge.fluids.FluidTankInfo getInfo() {
        FluidStack f = getFluid();
        return new net.minecraftforge.fluids.FluidTankInfo(f == null ? null : f.copy(), getCapacity());
    }
}
