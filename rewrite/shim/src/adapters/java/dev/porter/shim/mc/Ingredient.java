// porter:adapter (generic) - 1.19.2 crafting Ingredient (item predicate) on 1.7.10 stacks / ore names.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

public class Ingredient implements Predicate<ItemStack> {
    public static final Ingredient EMPTY = new Ingredient(new ItemStack[0]);
    private final ItemStack[] items;

    protected Ingredient(ItemStack[] items) { this.items = items; }

    public static Ingredient of(ItemStack... stacks) { return new Ingredient(stacks); }

    public static Ingredient ofOre(String ore) {
        List<ItemStack> l = new ArrayList<>(OreDictionary.getOres(ore));
        return new Ingredient(l.toArray(new ItemStack[0]));
    }

    public ItemStack[] getItems() { return items.clone(); }

    public boolean isEmpty() { return items.length == 0; }

    @Override
    public boolean test(ItemStack s) {
        if (s == null) return items.length == 0;
        for (ItemStack i : items) if (OreDictionary.itemMatches(i, s, false)) return true;
        return false;
    }

    @Override
    public String toString() { return "Ingredient" + Arrays.toString(items); }
}
