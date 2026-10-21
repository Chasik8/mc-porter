// porter:adapter (generic) - Forge 1.19.2 FluidUtil on 1.7.10 fluid containers (FluidContainerRegistry and
// IFluidContainerItem). Items are single containers (stack size handled by the caller, as in 1.19.2).
package dev.porter.shim.mc;

import java.util.Optional;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

public final class FluidUtil {
    private FluidUtil() {}

    /** single-item view of a container as IFluidHandlerItem (null when the item holds no fluid container). */
    public static IFluidHandlerItem handler(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return null;
        final ItemStack one = stack.copy();
        one.stackSize = 1;
        if (one.getItem() instanceof IFluidContainerItem) {
            final IFluidContainerItem c = (IFluidContainerItem) one.getItem();
            return new IFluidHandlerItem() {
                public ItemStack getContainer() { return one; }
                public int getTanks() { return 1; }
                public FluidStack getFluidInTank(int t) { return c.getFluid(one); }
                public int getTankCapacity(int t) { return c.getCapacity(one); }
                public boolean isFluidValid(int t, FluidStack s) { return true; }
                public int fill(FluidStack r, boolean ex) { return c.fill(one, r, ex); }
                public FluidStack drain(FluidStack r, boolean ex) {
                    FluidStack in = c.getFluid(one);
                    return r == null || in == null || !in.isFluidEqual(r) ? null : c.drain(one, r.amount, ex);
                }
                public FluidStack drain(int m, boolean ex) { return c.drain(one, m, ex); }
            };
        }
        if (FluidContainerRegistry.isContainer(one)) {
            return new IFluidHandlerItem() {
                ItemStack cur = one;
                public ItemStack getContainer() { return cur; }
                public int getTanks() { return 1; }
                public FluidStack getFluidInTank(int t) { return FluidContainerRegistry.getFluidForFilledItem(cur); }
                public int getTankCapacity(int t) {
                    FluidStack f = FluidContainerRegistry.getFluidForFilledItem(cur);
                    return f != null ? f.amount : FluidContainerRegistry.getContainerCapacity(new FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 1), cur);
                }
                public boolean isFluidValid(int t, FluidStack s) { return true; }
                public int fill(FluidStack r, boolean ex) {
                    if (r == null || !FluidContainerRegistry.isEmptyContainer(cur)) return 0;
                    ItemStack filled = FluidContainerRegistry.fillFluidContainer(r, cur);
                    if (filled == null) return 0;
                    FluidStack in = FluidContainerRegistry.getFluidForFilledItem(filled);
                    if (in == null || in.amount > r.amount) return 0;
                    if (ex) cur = filled;
                    return in.amount;
                }
                public FluidStack drain(FluidStack r, boolean ex) {
                    FluidStack in = FluidContainerRegistry.getFluidForFilledItem(cur);
                    return r == null || in == null || !in.isFluidEqual(r) ? null : drain(r.amount, ex);
                }
                public FluidStack drain(int m, boolean ex) {
                    FluidStack in = FluidContainerRegistry.getFluidForFilledItem(cur);
                    if (in == null || in.amount > m) return null; // containers drain completely or not at all
                    if (ex) cur = FluidContainerRegistry.drainFluidContainer(cur);
                    return in.copy();
                }
            };
        }
        return null;
    }

    public static LazyOptional<IFluidHandlerItem> getFluidHandler(ItemStack stack) {
        final IFluidHandlerItem h = handler(stack);
        return h == null ? LazyOptional.<IFluidHandlerItem>empty() : LazyOptional.of(() -> h);
    }

    public static Optional<FluidStack> getFluidContained(ItemStack stack) {
        IFluidHandlerItem h = handler(stack);
        if (h == null) return Optional.empty();
        FluidStack f = h.drain(Integer.MAX_VALUE, false);
        return f == null || f.amount <= 0 ? Optional.<FluidStack>empty() : Optional.of(f);
    }

    /** fill the container from the source (1.19.2 semantics: simulate first, then execute). */
    public static FluidActionResult tryFillContainer(ItemStack container, IFluidHandler source, int max, EntityPlayer player, boolean doFill) {
        IFluidHandlerItem h = handler(container);
        if (h == null || source == null) return FluidActionResult.FAILURE;
        FluidStack sim = source.drain(max, false);
        if (sim == null || sim.amount <= 0) return FluidActionResult.FAILURE;
        int accepted = h.fill(sim, false);
        if (accepted <= 0) return FluidActionResult.FAILURE;
        if (!doFill) {
            h.fill(sim, true);
            return new FluidActionResult(h.getContainer());
        }
        FluidStack real = source.drain(accepted, true);
        if (real == null || real.amount <= 0) return FluidActionResult.FAILURE;
        h.fill(real, true);
        return new FluidActionResult(h.getContainer());
    }

    /** empty the container into the destination. */
    public static FluidActionResult tryEmptyContainer(ItemStack container, IFluidHandler dest, int max, EntityPlayer player, boolean doDrain) {
        IFluidHandlerItem h = handler(container);
        if (h == null || dest == null) return FluidActionResult.FAILURE;
        FluidStack sim = h.drain(max, false);
        if (sim == null || sim.amount <= 0) return FluidActionResult.FAILURE;
        int accepted = dest.fill(sim, false);
        if (accepted <= 0 || accepted < sim.amount && FluidContainerRegistry.isContainer(container)) return FluidActionResult.FAILURE;
        FluidStack drained = h.drain(accepted, true);
        if (drained == null) return FluidActionResult.FAILURE;
        if (doDrain) dest.fill(drained, true);
        return new FluidActionResult(h.getContainer());
    }
}
