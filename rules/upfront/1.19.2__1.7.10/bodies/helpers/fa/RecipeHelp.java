// porter:helper (generic) - 1.19.2 Recipe members over the 1.7.10 crafting manager.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.mc.Ingredient;
import dev.porter.shim.mc.NonNullList;
import java.util.List;
import java.util.Optional;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

public final class RecipeHelp {
    private RecipeHelp() {}

    /** container items left in the grid after crafting (null where nothing stays). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static NonNullList remaining(IInventory inv) {
        int n = inv.getSizeInventory();
        NonNullList out = NonNullList.withSize(n, null);
        for (int i = 0; i < n; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.getItem() != null && s.getItem().hasContainerItem(s)) {
                out.set(i, s.getItem().getContainerItem(s));
            }
        }
        return out;
    }

    /** 1.7.10 recipes have no ids: the registry name of the output item stands in. */
    public static ResourceLocation id(IRecipe r) {
        ItemStack out = r.getRecipeOutput();
        if (out == null || out.getItem() == null) {
            return null;
        }
        String n = Item.itemRegistry.getNameForObject(out.getItem());
        return n == null ? null : new ResourceLocation(n);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static NonNullList ingredients(IRecipe r) {
        NonNullList list = NonNullList.create();
        Object[] in = null;
        if (r instanceof ShapedRecipes) {
            in = ((ShapedRecipes) r).recipeItems;
        } else if (r instanceof ShapelessRecipes) {
            in = ((ShapelessRecipes) r).recipeItems.toArray();
        } else if (r instanceof ShapedOreRecipe) {
            in = ((ShapedOreRecipe) r).getInput();
        } else if (r instanceof ShapelessOreRecipe) {
            in = ((ShapelessOreRecipe) r).getInput().toArray();
        }
        if (in != null) {
            for (Object o : in) {
                if (o instanceof ItemStack) {
                    list.add(Ingredient.of(new ItemStack[] {(ItemStack) o}));
                } else if (o instanceof String) {
                    list.add(Ingredient.ofOre((String) o));
                } else if (o instanceof List) {
                    list.add(Ingredient.of((ItemStack[]) ((List) o).toArray(new ItemStack[0])));
                } else {
                    list.add(Ingredient.EMPTY);
                }
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public static Optional<IRecipe> byKey(ResourceLocation key) {
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            IRecipe r = (IRecipe) o;
            if (key != null && key.equals(id(r))) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public static Optional<IRecipe> find(IInventory inv, World world) {
        if (!(inv instanceof InventoryCrafting)) {
            return Optional.empty();
        }
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            IRecipe r = (IRecipe) o;
            if (r.matches((InventoryCrafting) inv, world)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
