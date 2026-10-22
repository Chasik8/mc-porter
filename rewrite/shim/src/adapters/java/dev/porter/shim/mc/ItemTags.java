// porter:adapter (generic) - 1.19.2 vanilla item tags as 1.7.10 member sets (the items that carry them in 1.7.10).
package dev.porter.shim.mc;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

public final class ItemTags {
    private ItemTags() {}

    public static final TagKey<Item> WOOL = TagKey.<Item>of("item", "minecraft:wool").add(Item.getItemFromBlock(Blocks.wool));
    public static final TagKey<Item> PLANKS = TagKey.<Item>of("item", "minecraft:planks").add(Item.getItemFromBlock(Blocks.planks));
    public static final TagKey<Item> LOGS = TagKey.<Item>of("item", "minecraft:logs").add(Item.getItemFromBlock(Blocks.log),
        Item.getItemFromBlock(Blocks.log2));
    public static final TagKey<Item> SAPLINGS = TagKey.<Item>of("item", "minecraft:saplings").add(Item.getItemFromBlock(Blocks.sapling));
    public static final TagKey<Item> LEAVES = TagKey.<Item>of("item", "minecraft:leaves").add(Item.getItemFromBlock(Blocks.leaves),
        Item.getItemFromBlock(Blocks.leaves2));
    public static final TagKey<Item> COALS = TagKey.<Item>of("item", "minecraft:coals").add(Items.coal);
    public static final TagKey<Item> ARROWS = TagKey.<Item>of("item", "minecraft:arrows").add(Items.arrow);

    public static TagKey<Item> create(ResourceLocation location) { return TagKey.create("item", location); }
}
