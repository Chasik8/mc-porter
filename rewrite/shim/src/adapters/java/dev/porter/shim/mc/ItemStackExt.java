// porter:adapter (generic) - 1.19.2 ItemStack members that live on Item (container item) or take other arguments on 1.7.10.
package dev.porter.shim.mc;

import java.util.Random;
import net.minecraft.item.ItemStack;

public final class ItemStackExt {
    private ItemStackExt() {}

    /** ItemStack#hasCraftingRemainingItem -> Item.hasContainerItem(stack). */
    public static boolean hasCraftingRemainingItem(ItemStack s) {
        return s != null && s.getItem() != null && s.getItem().hasContainerItem(s);
    }

    /** ItemStack#getCraftingRemainingItem -> Item.getContainerItem(stack); null when there is none. */
    public static ItemStack getCraftingRemainingItem(ItemStack s) {
        return s == null || s.getItem() == null ? null : s.getItem().getContainerItem(s);
    }

    /** ItemStack#hurt(amount, random, player) -> attemptDamageItem(amount, random); true when the stack broke. */
    public static boolean hurt(ItemStack s, int amount, Random rnd) {
        return s != null && s.attemptDamageItem(amount, rnd);
    }
}
