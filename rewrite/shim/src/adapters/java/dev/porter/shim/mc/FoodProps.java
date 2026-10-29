// porter:adapter (the mod) - 1.19.2 Item#isEdible()/getFoodProperties().getNutrition() on 1.7.10 ItemFood.
package dev.porter.shim.mc;

import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;

public final class FoodProps {
    private final Item item;

    private FoodProps(Item item) { this.item = item; }

    public static boolean isEdible(Item item) { return item instanceof ItemFood; }

    public static FoodProps of(Item item) { return new FoodProps(item); }

    /** hunger points restored (ItemFood heal amount). */
    public int getNutrition() {
        return item instanceof ItemFood ? ((ItemFood) item).func_150905_g(new ItemStack(item)) : 0;
    }
}
