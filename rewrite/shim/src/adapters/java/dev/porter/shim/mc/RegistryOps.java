// porter:adapter (generic) - Forge 1.19.2 IForgeRegistry#register(name, value) on the 1.7.10 registries.
package dev.porter.shim.mc;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

public final class RegistryOps {
    private RegistryOps() {}

    /**
     * Registers {@code value} under {@code name}. Blocks and items go through GameRegistry (the domain comes from the
     * active mod); potions register themselves by numeric id in their constructor, so only the name is attached.
     */
    public static <V> void register(IForgeRegistry<V> registry, ResourceLocation name, V value) {
        if (value instanceof Block) {
            GameRegistry.registerBlock((Block) value, name.getResourcePath());
        } else if (value instanceof Item) {
            GameRegistry.registerItem((Item) value, name.getResourcePath());
        } else if (value instanceof Potion) {
            ((Potion) value).setPotionName("potion." + name.getResourcePath());
        } else {
            throw new UnsupportedOperationException("register " + name + " in " + registry.getRegistryName());
        }
    }
}
