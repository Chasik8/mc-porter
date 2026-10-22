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

    /** Ingredient.of(ItemLike...): items or blocks (or stacks) */
    public static Ingredient of(Object... likes) {
        List<ItemStack> l = new ArrayList<>();
        for (Object o : likes) {
            ItemStack s = o instanceof ItemStack ? (ItemStack) o : ItemLikes.stack(o);
            if (s != null) l.add(s);
        }
        return new Ingredient(l.toArray(new ItemStack[0]));
    }

    /** Ingredient.of(TagKey): the tag's explicit members plus the ore dictionary entries of its ore name. */
    public static Ingredient of(TagKey<?> tag) {
        List<ItemStack> l = new ArrayList<>();
        for (Object o : tag.members()) {
            ItemStack s = o instanceof ItemStack ? (ItemStack) o : ItemLikes.stack(o);
            if (s != null) l.add(s);
        }
        if (tag.oreName() != null) l.addAll(OreDictionary.getOres(tag.oreName()));
        return new Ingredient(l.toArray(new ItemStack[0]));
    }

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
