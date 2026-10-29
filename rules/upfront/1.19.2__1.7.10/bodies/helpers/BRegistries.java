package dev.porter.shim.gen.core;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

/** Registration of a value into the 1.7.10 registry that matches its type (blocks, items, fluids). */
public final class BRegistries {
    private BRegistries() {}

    /** Registers {@code value} under the path of {@code name} (the active mod supplies the namespace). */
    public static void register(String name, Object value) {
        if (name == null || value == null) {
            return;
        }
        int i = name.indexOf(':');
        String path = i >= 0 ? name.substring(i + 1) : name;
        if (value instanceof Block) {
            GameRegistry.registerBlock((Block) value, path);
        } else if (value instanceof Item) {
            GameRegistry.registerItem((Item) value, path);
        } else if (value instanceof Fluid) {
            FluidRegistry.registerFluid((Fluid) value);
        }
    }
}
