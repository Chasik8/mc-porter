// porter:adapter (generic) - 1.19.2 PotionUtils: custom effects in item NBT ("CustomPotionEffects", the 1.7.10 key
// ItemPotion reads), effect colours.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionHelper;

public final class PotionUtils {
    private PotionUtils() {}

    public static ItemStack setCustomEffects(ItemStack stack, Collection<PotionEffect> effects) {
        if (stack == null || effects == null || effects.isEmpty()) return stack;
        NBTTagCompound tag = ItemStacks.getOrCreateTag(stack);
        NBTTagList list = tag.getTagList("CustomPotionEffects", 10);
        for (PotionEffect e : effects) list.appendTag(e.writeCustomPotionEffectToNBT(new NBTTagCompound()));
        tag.setTag("CustomPotionEffects", list);
        return stack;
    }

    public static List<PotionEffect> getCustomEffects(ItemStack stack) {
        List<PotionEffect> out = new ArrayList<>();
        if (stack == null || !stack.hasTagCompound()) return out;
        NBTTagList list = stack.getTagCompound().getTagList("CustomPotionEffects", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            PotionEffect e = PotionEffect.readCustomPotionEffectFromNBT(list.getCompoundTagAt(i));
            if (e != null) out.add(e);
        }
        return out;
    }

    public static int getColor(Collection<PotionEffect> effects) {
        return effects == null || effects.isEmpty() ? 3694022 : PotionHelper.calcPotionLiquidColor(effects);
    }
}
