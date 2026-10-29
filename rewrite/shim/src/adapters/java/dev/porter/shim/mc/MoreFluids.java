// porter:adapter (the mod-specific, PRIVATE) - Fluids.FLOWING_LAVA / FLOWING_WATER: 1.7.10 has one Forge fluid per
// liquid, flowing or not. These sentinels never equal a registered fluid, so a `fluid == FLOWING_*` branch does not
// fire and the plain LAVA/WATER branch next to it handles every state.
package dev.porter.shim.mc;

import net.minecraftforge.fluids.Fluid;

public final class MoreFluids {
    public static final Fluid FLOWING_LAVA = new Fluid("porter_flowing_lava");
    public static final Fluid FLOWING_WATER = new Fluid("porter_flowing_water");

    private MoreFluids() {}
}
