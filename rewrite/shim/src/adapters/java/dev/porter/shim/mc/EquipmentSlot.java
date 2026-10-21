// porter:adapter (generic) - 1.19.2 EquipmentSlot. 1.7.10 armour slots: EntityPlayer.inventory.armorInventory[0..3]
// = feet..head, equipment index 0 = held item.
package dev.porter.shim.mc;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

public enum EquipmentSlot {
    MAINHAND(Type.HAND, 0, 0, "mainhand"),
    OFFHAND(Type.HAND, 1, 5, "offhand"),
    FEET(Type.ARMOR, 0, 1, "feet"),
    LEGS(Type.ARMOR, 1, 2, "legs"),
    CHEST(Type.ARMOR, 2, 3, "chest"),
    HEAD(Type.ARMOR, 3, 4, "head");

    private final Type type;
    private final int index;
    private final int filterFlag;
    private final String name;

    EquipmentSlot(Type type, int index, int flag, String name) {
        this.type = type;
        this.index = index;
        this.filterFlag = flag;
        this.name = name;
    }

    public Type getType() { return type; }

    public int getIndex() { return index; }

    public int getFilterFlag() { return filterFlag; }

    public String getName() { return name; }

    public static EquipmentSlot byName(String n) {
        for (EquipmentSlot s : values()) if (s.name.equals(n)) return s;
        throw new IllegalArgumentException("Invalid slot '" + n + "'");
    }

    /** 1.19.2 LivingEntity.getItemBySlot (1.7.10 has no off hand: null). */
    public static ItemStack get(EntityLivingBase e, EquipmentSlot s) {
        if (s == OFFHAND) return null;
        return e.getEquipmentInSlot(s == MAINHAND ? 0 : s.index + 1);
    }

    public static void set(EntityLivingBase e, EquipmentSlot s, ItemStack stack) {
        if (s == OFFHAND) return;
        e.setCurrentItemOrArmor(s == MAINHAND ? 0 : s.index + 1, stack);
    }

    public enum Type {
        HAND,
        ARMOR
    }
}
