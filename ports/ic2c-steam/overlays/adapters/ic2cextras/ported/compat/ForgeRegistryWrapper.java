// porter:adapter (IC2C-specific) - ic2.core.platform.registries.ForgeRegistryWrapper on 1.7.10 (the hub is redirected here by a type rule): ic2.core.platform.registries.ForgeRegistryWrapper: registry keys of the 1.7.10
// registries (fluids by FluidRegistry name, "minecraft:empty" for no fluid as Fluids.EMPTY in 1.19.2).
package ic2cextras.ported.compat;

import javax.annotation.Nonnull;

import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

public class ForgeRegistryWrapper {

    public static ResourceLocation getItemKey(Item element) {
        Object name = element == null ? null : Item.itemRegistry.getNameForObject(element);
        return new ResourceLocation(name == null ? "minecraft:air" : name.toString());
    }

    public static ResourceLocation getFluidKey(Fluid element) {
        return new ResourceLocation(element == null ? "minecraft:empty" : element.getName());
    }

    @Nonnull
    public static Fluid getFluidValue(ResourceLocation key) {
        if (key == null) return null;
        Fluid f = FluidRegistry.getFluid(key.getResourcePath());
        return f != null ? f : FluidRegistry.getFluid(key.toString());
    }

    public static ResourceLocation getBlockKey(net.minecraft.block.Block element) {
        Object name = element == null ? null : net.minecraft.block.Block.blockRegistry.getNameForObject(element);
        return new ResourceLocation(name == null ? "minecraft:air" : name.toString());
    }

    public static ResourceLocation getMobEffectKey(net.minecraft.potion.Potion element) {
        return ic2cextras.ported.shim.mc.ForgeRegistries.MOB_EFFECTS.getKey(element);
    }

    public static Item getItemValue(ResourceLocation key) {
        return key == null ? null : (Item) Item.itemRegistry.getObject(key.toString());
    }

    public static net.minecraft.block.Block getBlockValue(ResourceLocation key) {
        return key == null ? null : (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject(key.toString());
    }
}
