// porter:adapter (IC2C-specific) - ic2.core.inventory.filter.SpecialFilters on 1.7.10 (the hub is redirected here by a type rule): ic2.core.inventory.filter.SpecialFilters: the filters the closure uses. FUEL_CAN accepts
// ported IFuelableItems and the IC2C 1.2.6 filled fuel can (compat.Ic2HostItems); tool filters have no closure items.
package ic2cextras.ported.compat;

import ic2cextras.ported.shim.mc.DyeColor;

import ic2cextras.ported.core.inventory.filter.IFilter;

public class SpecialFilters {
    public static final IFilter ALWAYS_FALSE = s -> false;
    public static final IFilter HANDLER_ITEM = s -> s != null && s.getItem() instanceof ic2cextras.ported.core.item.base.features.IHandlerItem;
    public static final IFilter FUEL_CAN = s -> {
        ic2cextras.ported.api.items.IFuelableItem f = Ic2HostItems.fuelable(s);
        return f != null && f.hasFuel(s);
    };
    public static final IFilter EU_READER = ALWAYS_FALSE;
    public static final IFilter THERMOMETER = ALWAYS_FALSE;
    public static final IFilter CROP_SCANNER = ALWAYS_FALSE;
    public static final IFilter EMPTY_PAINTER = ALWAYS_FALSE;
    public static final IFilter PAINTER = ALWAYS_FALSE;

    /** The colour after {@code c} in the 16-colour cycle; with {@code allowNull} "no colour" follows the last one. */
    public static DyeColor getNextColor(DyeColor c, boolean allowNull) { return cycle(c, 1, allowNull); }

    /** The colour before {@code c} (see {@link #getNextColor}). */
    public static DyeColor getPrevColor(DyeColor c, boolean allowNull) { return cycle(c, -1, allowNull); }

    private static DyeColor cycle(DyeColor c, int step, boolean allowNull) {
        if (c == null && !allowNull) throw new IllegalStateException("Null Color is not allowed");
        int colours = 16;
        int positions = allowNull ? colours + 1 : colours; // the extra position is "no colour"
        int at = c == null ? colours : c.getId();
        int to = Math.floorMod(at + step, positions);
        return to == colours ? null : DyeColor.byId(to);
    }

    private static IFilter chargeFilter;

    /** IC2C: dischargeable electric items, redstone and single-use batteries (the 1.2.6 RE battery covers both). */
    public static IFilter createChargeFilter() {
        if (chargeFilter == null) {
            IFilter electric = ic2cextras.ported.core.inventory.filter.special.ElectricItemFilter.DISCHARGE_EXTERNAL_FILTER;
            chargeFilter = s -> s != null && (electric.matches(s) || s.getItem() == net.minecraft.init.Items.redstone
                || Ic2HostItems.isSingleUseBattery(s));
        }
        return chargeFilter;
    }
}
