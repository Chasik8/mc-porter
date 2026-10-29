package dev.porter.shim.gen.helpers;

import dev.porter.shim.mc.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Block drop helpers over the 1.7.10 drop methods that need no world. */
public final class DdBlocks {
    private DdBlocks() {
    }

    /** The stacks a block drops by default (item, quantity and damage of the block); empty for null or no drop. */
    public static List<ItemStack> drops(BlockState state) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        if (state == null) {
            return out;
        }
        Block b = state.getBlock();
        int meta = state.getMeta();
        Random rnd = new Random();
        Item item = b.getItemDropped(meta, rnd, 0);
        if (item != null) {
            out.add(new ItemStack(item, b.quantityDropped(rnd), b.damageDropped(meta)));
        }
        return out;
    }
}
