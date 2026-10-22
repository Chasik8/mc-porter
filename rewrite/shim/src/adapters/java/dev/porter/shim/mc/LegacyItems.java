// porter:adapter (generic) - vanilla items of 1.13+ that 1.7.10 does not have, as the closest 1.7.10 substitute
// (a recipe that names one must keep producing its result: raw ore -> the metal's dust, mud -> dirt, ...). Items 1.7.10
// has under another item's damage value (cocoa beans = dye:3) are stacks. ABSENT is an unregistered placeholder for
// the few without any sensible substitute (lingering potions, tipped arrows): nothing matches it.
package dev.porter.shim.mc;

import java.util.List;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

public final class LegacyItems {
    private LegacyItems() {}

    /** Placeholder for an item without a 1.7.10 counterpart (never registered, never obtainable). */
    public static final Item ABSENT = new Item().setUnlocalizedName("porter.absent");

    public static final Item MUD = Item.getItemFromBlock(Blocks.dirt);
    public static final Item WET_SPONGE = Item.getItemFromBlock(Blocks.sponge);
    public static final Item SPONGE = Item.getItemFromBlock(Blocks.sponge);
    public static final Item DIRT = Item.getItemFromBlock(Blocks.dirt);
    public static final Item REDSTONE_BLOCK = Item.getItemFromBlock(Blocks.redstone_block);
    public static final Item SPLASH_POTION = Items.potionitem;
    public static final Item PHANTOM_MEMBRANE = Items.feather;
    public static final Item RABBIT_FOOT = ABSENT;
    public static final Item LINGERING_POTION = ABSENT;
    public static final Item TIPPED_ARROW = ABSENT;
    public static final Item PUFFERFISH = Items.fish;

    public static ItemStack cocoaBeans() { return new ItemStack(Items.dye, 1, 3); }

    /** The first item registered under an ore name (e.g. a mod's copper dust), else ABSENT. */
    public static Item ore(String oreName) { return ore(oreName, ABSENT); }

    public static Item ore(String oreName, Item fallback) {
        List<ItemStack> l = OreDictionary.getOres(oreName);
        return l.isEmpty() || l.get(0) == null ? fallback : l.get(0).getItem();
    }

    /** Raw ores (1.17+): the pack's dust of the metal (smelts into one ingot, like raw ore), else the ore block. */
    public static Item rawIron() { return ore("dustIron", Item.getItemFromBlock(Blocks.iron_ore)); }

    public static Item rawGold() { return ore("dustGold", Item.getItemFromBlock(Blocks.gold_ore)); }

    public static Item rawCopper() { return ore("dustCopper", ore("oreCopper")); }
}
