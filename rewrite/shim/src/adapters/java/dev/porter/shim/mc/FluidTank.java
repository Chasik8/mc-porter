// porter:adapter (generic) - Forge 1.19.2 templates.FluidTank (validator, onContentsChanged, tank-indexed
// IFluidHandler) implementing the 1.7.10 IFluidTank. Empty fluid = null (1.7.10 convention).
package dev.porter.shim.mc;

import java.util.function.Predicate;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

public class FluidTank implements IFluidHandler, IFluidTank {
    protected Predicate<FluidStack> validator;
    protected FluidStack fluid = null;
    protected int capacity;

    public FluidTank(int capacity) { this(capacity, e -> true); }

    public FluidTank(int capacity, Predicate<FluidStack> validator) {
        this.capacity = capacity;
        this.validator = validator;
    }

    public FluidTank setCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public FluidTank setValidator(Predicate<FluidStack> validator) {
        if (validator != null) this.validator = validator;
        return this;
    }

    @Override
    public boolean isFluidValid(FluidStack stack) { return validator.test(stack); }

    @Override
    public int getCapacity() { return capacity; }

    @Override
    public FluidStack getFluid() { return fluid; }

    @Override
    public int getFluidAmount() { return fluid == null ? 0 : fluid.amount; }

    @Override
    public FluidTankInfo getInfo() { return new FluidTankInfo(fluid == null ? null : fluid.copy(), capacity); }

    public FluidTank readFromNBT(NBTTagCompound nbt) {
        fluid = nbt.hasKey("Empty") ? null : FluidStack.loadFluidStackFromNBT(nbt);
        return this;
    }

    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        if (fluid != null) fluid.writeToNBT(nbt);
        else nbt.setString("Empty", "");
        return nbt;
    }

    @Override
    public int getTanks() { return 1; }

    @Override
    public FluidStack getFluidInTank(int tank) { return getFluid(); }

    @Override
    public int getTankCapacity(int tank) { return getCapacity(); }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) { return isFluidValid(stack); }

    @Override
    public int fill(FluidStack resource, boolean execute) {
        if (resource == null || resource.amount <= 0 || !isFluidValid(resource)) return 0;
        if (!execute) {
            if (fluid == null) return Math.min(capacity, resource.amount);
            if (!fluid.isFluidEqual(resource)) return 0;
            return Math.min(capacity - fluid.amount, resource.amount);
        }
        if (fluid == null) {
            fluid = new FluidStack(resource, Math.min(capacity, resource.amount));
            onContentsChanged();
            return fluid.amount;
        }
        if (!fluid.isFluidEqual(resource)) return 0;
        int filled = capacity - fluid.amount;
        if (resource.amount < filled) {
            fluid.amount += resource.amount;
            filled = resource.amount;
        } else {
            fluid.amount = capacity;
        }
        if (filled > 0) onContentsChanged();
        return filled;
    }

    @Override
    public FluidStack drain(FluidStack resource, boolean execute) {
        if (resource == null || resource.amount <= 0 || fluid == null || !resource.isFluidEqual(fluid)) return null;
        return drain(resource.amount, execute);
    }

    @Override
    public FluidStack drain(int maxDrain, boolean execute) {
        if (fluid == null || maxDrain <= 0) return null;
        int drained = Math.min(maxDrain, fluid.amount);
        FluidStack stack = new FluidStack(fluid, drained);
        if (execute && drained > 0) {
            fluid.amount -= drained;
            if (fluid.amount <= 0) fluid = null;
            onContentsChanged();
        }
        return stack;
    }

    protected void onContentsChanged() {}

    public void setFluid(FluidStack stack) { this.fluid = stack == null || stack.amount <= 0 ? null : stack; }

    public boolean isEmpty() { return fluid == null || fluid.amount <= 0; }

    public int getSpace() { return Math.max(0, capacity - getFluidAmount()); }
}
