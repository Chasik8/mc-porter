// porter:adapter (generic) - 1.19.2 ItemLike (Block or Item) -> 1.7.10 Item.
package dev.porter.shim.mc;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class ItemLikes {
    private ItemLikes() {}

    public static Item item(Object o) {
        if (o instanceof Item) return (Item) o;
        if (o instanceof Block) return Item.getItemFromBlock(MetaBlock.host((Block) o));
        if (o instanceof ItemStack) return ((ItemStack) o).getItem();
        return null;
    }

    public static ItemStack stack(Object o, int count) {
        if (o instanceof ItemStack) {
            ItemStack s = ((ItemStack) o).copy();
            s.stackSize = count;
            return s;
        }
        if (o instanceof Block) return new ItemStack(MetaBlock.host((Block) o), count, MetaBlock.meta((Block) o));
        Item i = item(o);
        return i == null ? null : new ItemStack(i, count);
    }

    public static ItemStack stack(Object o) { return stack(o, 1); }
}
