// porter:adapter (IC2C-specific) - host items of the installed IC2C 1.2.6 seen through the ported 2.x item
// API: the 1.2.6 filled fuel can (NBT "value"/"maxValue") as an IFuelableItem.
package ic2cextras.ported.compat;

import ic2cextras.ported.api.items.IFuelableItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class Ic2HostItems {
    private Ic2HostItems() {}

    /** the IFuelableItem view of a stack (ported item or IC2C 1.2.6 fuel can), null when it holds no fuel item. */
    public static IFuelableItem fuelable(ItemStack s) {
        if (s == null || s.getItem() == null) return null;
        if (s.getItem() instanceof IFuelableItem) return (IFuelableItem) s.getItem();
        String cls = s.getItem().getClass().getName();
        if (cls.equals("ic2.core.item.ItemFuelCanFilled") || cls.equals("ic2.core.item.ItemFuelCanEmpty")) return FUEL_CAN;
        return null;
    }

    private static ic2cextras.ported.core.item.food_and_drink.IC2Drink emptyMug;

    /**
     * IC2C 2.x's empty mug (a drink container) is the 1.2.6 empty mug: the barrel and the refinery see a host mug through
     * an (unregistered) MugItem, whose filled containers are the port's registered mugs.
     */
    public static synchronized ic2cextras.ported.core.item.food_and_drink.IC2Drink emptyMug() {
        if (emptyMug == null) emptyMug = new ic2cextras.ported.core.item.food_and_drink.MugItem();
        return emptyMug;
    }

    /** the drink-container view of a stack: a ported drink item, the host empty mug (as MugItem), else null */
    public static ic2cextras.ported.api.items.IDrinkContainer drinkContainer(ItemStack s) {
        if (s == null || s.getItem() == null) return null;
        if (s.getItem() instanceof ic2cextras.ported.api.items.IDrinkContainer) return (ic2cextras.ported.api.items.IDrinkContainer) s.getItem();
        if (s.getItem().getClass().getName().equals("ic2.core.item.ItemMug") && s.getItemDamage() == 0) return emptyMug();
        return null;
    }

    /** the IC2C 1.2.6 single-use battery */
    public static boolean isSingleUseBattery(ItemStack s) {
        return s != null && s.getItem() != null && s.getItem().getClass().getName().equals("ic2.core.item.ItemBatterySU");
    }

    static NBTTagCompound tag(ItemStack s) {
        if (!s.hasTagCompound()) s.setTagCompound(new NBTTagCompound());
        return s.getTagCompound();
    }

    static final IFuelableItem FUEL_CAN = new IFuelableItem() {
        public ItemStack fill(ItemStack s, int amount) {
            NBTTagCompound t = tag(s);
            int max = t.hasKey("maxValue") ? t.getInteger("maxValue") : 10000;
            t.setInteger("value", Math.min(max, t.getInteger("value") + amount));
            return s;
        }

        public boolean canFuel(ItemStack s) {
            NBTTagCompound t = tag(s);
            return t.getInteger("value") < (t.hasKey("maxValue") ? t.getInteger("maxValue") : 10000);
        }

        public boolean hasFuel(ItemStack s) {
            return s.getItem().getClass().getName().equals("ic2.core.item.ItemFuelCanFilled") && s.hasTagCompound() && s.getTagCompound().getInteger("value") > 0;
        }

        /** 2.x semantics: take up to {@code requested} fuel; {@code drain} = actually remove it from the can. */
        public int getFuel(ItemStack s, int requested, boolean drain) {
            if (!hasFuel(s)) return 0;
            NBTTagCompound t = tag(s);
            int have = t.getInteger("value");
            int got = Math.min(have, requested);
            if (drain) t.setInteger("value", have - got);
            return got;
        }
    };
}
