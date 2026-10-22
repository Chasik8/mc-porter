// porter:adapter (generic) - Forge 1.19.2 ForgeMod: the milk fluid a mod enables with enableMilkFluid(). On 1.7.10 the
// fluid named "milk" is used when another mod registered one, else it is registered here on first use (as Forge
// 1.19.2 does for the mods that ask for it).
package dev.porter.shim.mc;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

public final class ForgeMod {
    private ForgeMod() {}

    public static final RegistryObject<Fluid> MILK = new RegistryObject<>(new ResourceLocation("minecraft", "milk"), ForgeMod::milk);

    public static void enableMilkFluid() {}

    private static Fluid milk() {
        Fluid f = FluidRegistry.getFluid("milk");
        if (f == null) {
            f = new Fluid("milk").setUnlocalizedName("milk");
            FluidRegistry.registerFluid(f);
        }
        return f;
    }
}
