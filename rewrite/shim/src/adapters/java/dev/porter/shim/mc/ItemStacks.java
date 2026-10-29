// porter:adapter (generic) - null-safe ItemStack accessors: 1.19.2 code never sees a null stack (ItemStack.EMPTY),
// 1.7.10 uses null for "no stack" (the EMPTY rule maps to null), so accessors on possibly-empty stacks must not NPE.
package dev.porter.shim.mc;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class ItemStacks {
    private ItemStacks() {}

    public static boolean isEmpty(ItemStack s) { return s == null || s.getItem() == null || s.stackSize <= 0; }

    public static Item item(ItemStack s) { return s == null ? null : s.getItem(); }

    public static int count(ItemStack s) { return s == null ? 0 : s.stackSize; }

    public static ItemStack copy(ItemStack s) { return s == null ? null : s.copy(); }

    public static NBTTagCompound tag(ItemStack s) { return s == null ? null : s.getTagCompound(); }

    public static boolean hasTag(ItemStack s) { return s != null && s.hasTagCompound(); }

    public static int maxSize(ItemStack s) { return s == null ? 64 : s.getMaxStackSize(); }

    public static int damage(ItemStack s) { return s == null ? 0 : s.getItemDamage(); }

    public static void shrink(ItemStack s, int n) { if (s != null) s.stackSize -= n; }

    public static void grow(ItemStack s, int n) { if (s != null) s.stackSize += n; }

    public static void setCount(ItemStack s, int n) { if (s != null) s.stackSize = n; }

    /** ItemStack.isSameItemSameTags */
    public static boolean sameItemSameTags(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) return isEmpty(a) && isEmpty(b);
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage() && ItemStack.areItemStackTagsEqual(a, b);
    }

    /**
     * ItemStack#sameItem: same item (1.7.10: and the same damage value unless the item is damageable, since 1.7.10 keeps
     * item variants in the damage) unless {@code a} carries the 1.7.10 wildcard damage 32767 (vanilla furnace recipes are
     * keyed that way, so any variant matches); false when either stack is empty, like 1.19.2.
     */
    public static boolean sameItemAs(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) return false;
        return a.getItem() == b.getItem() && (a.getItem().isDamageable() || a.getItemDamage() == b.getItemDamage()
            || a.getItemDamage() == net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
    }

    /** ItemStack.isSame (item + damage) */
    public static boolean sameItem(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) return isEmpty(a) && isEmpty(b);
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    /** ItemStack.matches (item, damage, count, tags) */
    public static boolean matches(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) return isEmpty(a) && isEmpty(b);
        return ItemStack.areItemStacksEqual(a, b);
    }

    public static NBTTagCompound getOrCreateTag(ItemStack s) {
        if (!s.hasTagCompound()) s.setTagCompound(new NBTTagCompound());
        return s.getTagCompound();
    }

    public static NBTTagCompound getOrCreateTagElement(ItemStack s, String key) {
        NBTTagCompound t = getOrCreateTag(s);
        if (!t.hasKey(key, 10)) t.setTag(key, new NBTTagCompound());
        return t.getCompoundTag(key);
    }

    public static void removeTagKey(ItemStack s, String key) {
        if (s != null && s.hasTagCompound()) {
            s.getTagCompound().removeTag(key);
            if (s.getTagCompound().hasNoTags()) s.setTagCompound(null);
        }
    }

    /** ItemStack.getTooltipLines(player, flag) */
    @SuppressWarnings("unchecked")
    public static java.util.List<net.minecraft.util.IChatComponent> tooltipLines(ItemStack s, net.minecraft.entity.player.EntityPlayer p, TooltipFlag f) {
        java.util.List<net.minecraft.util.IChatComponent> out = new java.util.ArrayList<>();
        if (s == null) return out;
        for (Object o : s.getTooltip(p, f != null && f.isAdvanced())) out.add(new net.minecraft.util.ChatComponentText(String.valueOf(o)));
        return out;
    }
}
