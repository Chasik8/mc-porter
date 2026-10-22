// porter:adapter (generic) - null-safe FluidStack checks (1.19.2 FluidStack.EMPTY is null on 1.7.10).
package dev.porter.shim.mc;

import net.minecraftforge.fluids.FluidStack;

public final class FluidStacks {
    private FluidStacks() {}

    public static boolean isEmpty(FluidStack s) { return s == null || s.getFluid() == null || s.amount <= 0; }

    /** FluidStack.getFluid() (EMPTY: no fluid). */
    public static net.minecraftforge.fluids.Fluid fluid(FluidStack s) { return s == null ? null : s.getFluid(); }

    /** FluidStack.getAmount() (EMPTY: 0). */
    public static int amount(FluidStack s) { return s == null ? 0 : s.amount; }

    /** FluidStack.copy() (EMPTY stays EMPTY). */
    public static FluidStack copy(FluidStack s) { return s == null ? null : s.copy(); }

    /** FluidStack.isFluidEqual(other): EMPTY equals EMPTY only. */
    public static boolean isFluidEqual(FluidStack s, FluidStack o) {
        if (isEmpty(s) || isEmpty(o)) return isEmpty(s) && isEmpty(o);
        return s.isFluidEqual(o);
    }

    /** FluidStack.containsFluid(other): EMPTY contains only EMPTY (a recipe without a second input needs an empty tank). */
    public static boolean containsFluid(FluidStack s, FluidStack o) {
        if (isEmpty(s) || isEmpty(o)) return isEmpty(s) && isEmpty(o);
        return s.containsFluid(o);
    }

    /** new FluidStack(stack, amount): a stack of the same fluid; EMPTY (null here) stays EMPTY. */
    public static FluidStack withAmount(FluidStack s, int amount) {
        return isEmpty(s) ? null : new FluidStack(s, amount);
    }
}
