// porter:adapter (the mod-specific, PRIVATE) - ItemStack.canEquip(slot, entity) for the charging bench's sorted mode.
package dev.porter.shim.mc;


import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

public final class MoreEquip {
    private MoreEquip() {}

    /** 1.19.2 EquipmentSlot HEAD..FEET index 3..0 is ItemArmor.armorType 0..3 (helmet..boots) on 1.7.10. */
    public static boolean canEquip(ItemStack stack, EquipmentSlot slot, Object entity) {
        if (stack == null || slot == null || slot.getType() != EquipmentSlot.Type.ARMOR) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof ItemArmor && ((ItemArmor) item).armorType == 3 - slot.getIndex();
    }
}
