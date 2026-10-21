// porter:adapter (generic) - capability lookup on anything 1.7.10 hands back (tile entities, players, item stacks):
// ported providers answer themselves, plain 1.7.10 objects are wrapped (IFluidHandler, IInventory, containers).
package dev.porter.shim.mc;

import dev.porter.shim.Direction;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

public final class Caps {
    private Caps() {}

    public static ForgeDirection fd(Direction d) { return d == null ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(d.get3DDataValue()); }

    @SuppressWarnings("unchecked")
    public static <T> LazyOptional<T> get(Object o, Capability<T> cap, Direction side) {
        if (o == null || cap == null) return LazyOptional.empty();
        if (o instanceof ICapabilityProvider) return ((ICapabilityProvider) o).getCapability(cap, side);
        if (cap == ForgeCapabilities.FLUID_HANDLER && o instanceof net.minecraftforge.fluids.IFluidHandler) {
            final IFluidHandler h = wrap((net.minecraftforge.fluids.IFluidHandler) o, side);
            return (LazyOptional<T>) (LazyOptional<?>) LazyOptional.of(() -> h);
        }
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (o instanceof EntityPlayer) {
                final IItemHandler h = ItemHandlers.wrap(((EntityPlayer) o).inventory, -1);
                return (LazyOptional<T>) (LazyOptional<?>) LazyOptional.of(() -> h);
            }
            if (o instanceof IInventory) {
                final IItemHandler h = ItemHandlers.wrap((IInventory) o, side == null ? -1 : side.get3DDataValue());
                return (LazyOptional<T>) (LazyOptional<?>) LazyOptional.of(() -> h);
            }
        }
        if ((cap == ForgeCapabilities.FLUID_HANDLER_ITEM || cap == ForgeCapabilities.FLUID_HANDLER) && o instanceof ItemStack) {
            return (LazyOptional<T>) (LazyOptional<?>) FluidUtil.getFluidHandler((ItemStack) o);
        }
        return LazyOptional.empty();
    }

    public static <T> LazyOptional<T> get(Object o, Capability<T> cap) { return get(o, cap, null); }

    /** a 1.7.10 sided fluid handler as a 1.19.2 tank-indexed handler. */
    public static IFluidHandler wrap(final net.minecraftforge.fluids.IFluidHandler h, Direction side) {
        final ForgeDirection d = fd(side);
        return new IFluidHandler() {
            FluidTankInfo[] info() {
                FluidTankInfo[] i = h.getTankInfo(d);
                return i == null ? new FluidTankInfo[0] : i;
            }

            public int getTanks() { return info().length; }

            public FluidStack getFluidInTank(int t) {
                FluidTankInfo[] i = info();
                return t < i.length ? i[t].fluid : null;
            }

            public int getTankCapacity(int t) {
                FluidTankInfo[] i = info();
                return t < i.length ? i[t].capacity : 0;
            }

            public boolean isFluidValid(int t, FluidStack s) { return s != null && h.canFill(d, s.getFluid()); }

            public int fill(FluidStack r, boolean ex) { return r == null || !h.canFill(d, r.getFluid()) ? 0 : h.fill(d, r, ex); }

            public FluidStack drain(FluidStack r, boolean ex) { return r == null || !h.canDrain(d, r.getFluid()) ? null : h.drain(d, r, ex); }

            public FluidStack drain(int m, boolean ex) { return h.drain(d, m, ex); }
        };
    }
}
