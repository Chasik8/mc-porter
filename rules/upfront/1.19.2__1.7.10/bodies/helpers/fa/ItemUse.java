// porter:helper (generic) - 1.19.2 item use / durability bar semantics on 1.7.10 items.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.mc.InteractionResultHolder;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public final class ItemUse {
    private ItemUse() {}

    /** Item.use(level, player, hand): right click with the held stack; success when the stack changed. */
    @SuppressWarnings("rawtypes")
    public static InteractionResultHolder use(Item item, World world, EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        int n = held == null ? 0 : held.stackSize;
        int d = held == null ? 0 : held.getItemDamage();
        ItemStack res = item.onItemRightClick(held, world, player);
        boolean changed = res != held || (res != null && (res.stackSize != n || res.getItemDamage() != d));
        return changed ? InteractionResultHolder.success(res) : InteractionResultHolder.pass(res);
    }

    /** Item.finishUsingItem: eating for players, the stack itself otherwise. */
    public static ItemStack finish(Item item, ItemStack stack, World world, EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) {
            return item.onEaten(stack, world, (EntityPlayer) entity);
        }
        return stack;
    }

    public static boolean barVisible(Item item, ItemStack stack) {
        return item != null && stack != null && item.showDurabilityBar(stack);
    }

    /** 13 pixel wide bar, full when undamaged. */
    public static int barWidth(Item item, ItemStack stack) {
        if (item == null || stack == null) {
            return 0;
        }
        double r = Math.max(0.0D, Math.min(1.0D, item.getDurabilityForDisplay(stack)));
        return Math.round((float) (13.0D - r * 13.0D));
    }

    /** green (full) to red (empty), like Mth.hsvToRgb((1 - damage) / 3, 1, 1). */
    public static int barColor(Item item, ItemStack stack) {
        double r = item == null || stack == null ? 0.0D : Math.max(0.0D, Math.min(1.0D, item.getDurabilityForDisplay(stack)));
        return java.awt.Color.HSBtoRGB((float) ((1.0D - r) / 3.0D), 1.0F, 1.0F) & 0xFFFFFF;
    }
}
