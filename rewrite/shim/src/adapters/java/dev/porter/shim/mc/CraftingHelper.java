// porter:adapter (the mod) - Forge CraftingHelper#getItem(name, disallowsAir) on the 1.7.10 item registry.
package dev.porter.shim.mc;

import cpw.mods.fml.common.registry.GameData;
import net.minecraft.item.Item;

public final class CraftingHelper {
    private CraftingHelper() {}

    /** The item registered under "modid:name" (a bare name is "minecraft:name"); throws like Forge when unknown. */
    public static Item getItem(String name, boolean disallowsAir) {
        String key = name.indexOf(':') < 0 ? "minecraft:" + name : name;
        Item item = (Item) GameData.getItemRegistry().getObject(key);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item '" + name + "'");
        }
        return item;
    }
}
