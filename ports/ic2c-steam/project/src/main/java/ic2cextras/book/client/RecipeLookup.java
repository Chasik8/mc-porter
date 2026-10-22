package ic2cextras.book.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2.core.AdvRecipe;
import ic2.core.AdvShapelessRecipe;

/**
 * Finds the real crafting recipes of an item in {@link CraftingManager} and turns them into a 3x3 grid of
 * alternatives. Knows vanilla shaped/shapeless, Forge ore recipes and IC2C AdvRecipe/AdvShapelessRecipe.
 */
@SideOnly(Side.CLIENT)
public final class RecipeLookup {

    /** One recipe as the book draws it. */
    public static final class View {

        public final ItemStack output;
        /** 9 cells, row-major; each is a list of alternatives (cycled) or null. */
        public final List<List<ItemStack>> grid;
        public final boolean shapeless;
        /** Furnace recipe: the input is in the centre cell. */
        public final boolean smelting;

        View(ItemStack output, List<List<ItemStack>> grid, boolean shapeless) {
            this(output, grid, shapeless, false);
        }

        View(ItemStack output, List<List<ItemStack>> grid, boolean shapeless, boolean smelting) {
            this.output = output;
            this.grid = grid;
            this.shapeless = shapeless;
            this.smelting = smelting;
        }
    }

    private static Field oreWidth, oreHeight;

    private RecipeLookup() {}

    public static List<View> find(ItemStack target) {
        List<View> out = new ArrayList<View>();
        if (target == null) return out;
        for (Object o : CraftingManager.getInstance()
            .getRecipeList()) {
            if (!(o instanceof IRecipe)) continue;
            IRecipe r = (IRecipe) o;
            ItemStack result;
            try {
                result = r.getRecipeOutput();
            } catch (Exception e) {
                continue;
            }
            if (result == null || result.getItem() != target.getItem()) continue;
            if (target.getItemDamage() != OreDictionary.WILDCARD_VALUE
                && result.getItemDamage() != target.getItemDamage()) continue;
            try {
                View v = convert(r, result);
                if (v != null) out.add(v);
            } catch (Exception ignored) {
                // unknown or broken recipe type: not shown
            }
        }
        // furnace
        for (Object o : FurnaceRecipes.smelting()
            .getSmeltingList()
            .entrySet()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            if (!(e.getKey() instanceof ItemStack) || !(e.getValue() instanceof ItemStack)) continue;
            ItemStack result = (ItemStack) e.getValue();
            if (result.getItem() != target.getItem()) continue;
            if (target.getItemDamage() != OreDictionary.WILDCARD_VALUE
                && result.getItemDamage() != target.getItemDamage()) continue;
            List<List<ItemStack>> grid = new ArrayList<List<ItemStack>>(Collections.nCopies(9, (List<ItemStack>) null));
            grid.set(4, alternatives(e.getKey()));
            out.add(new View(result.copy(), grid, false, true));
        }
        return out;
    }

    private static View convert(IRecipe r, ItemStack result) throws Exception {
        if (r instanceof ShapedRecipes) {
            ShapedRecipes s = (ShapedRecipes) r;
            return shaped(result, s.recipeWidth, s.recipeHeight, s.recipeItems);
        }
        if (r instanceof ShapedOreRecipe) {
            if (oreWidth == null) {
                oreWidth = ShapedOreRecipe.class.getDeclaredField("width");
                oreHeight = ShapedOreRecipe.class.getDeclaredField("height");
                oreWidth.setAccessible(true);
                oreHeight.setAccessible(true);
            }
            return shaped(result, oreWidth.getInt(r), oreHeight.getInt(r), ((ShapedOreRecipe) r).getInput());
        }
        if (r instanceof AdvRecipe) {
            AdvRecipe a = (AdvRecipe) r;
            return shaped(result, a.length, a.height, a.input);
        }
        if (r instanceof ShapelessRecipes) return shapeless(result, ((ShapelessRecipes) r).recipeItems);
        if (r instanceof ShapelessOreRecipe) return shapeless(result, ((ShapelessOreRecipe) r).getInput());
        if (r instanceof AdvShapelessRecipe) return shapeless(result, ((AdvShapelessRecipe) r).input);
        return null;
    }

    private static View shaped(ItemStack result, int w, int h, Object[] items) {
        List<List<ItemStack>> grid = new ArrayList<List<ItemStack>>(Collections.nCopies(9, (List<ItemStack>) null));
        for (int y = 0; y < h && y < 3; y++)
            for (int x = 0; x < w && x < 3; x++) grid.set(y * 3 + x, alternatives(items[y * w + x]));
        return new View(result.copy(), grid, false);
    }

    private static View shapeless(ItemStack result, List<?> items) {
        List<List<ItemStack>> grid = new ArrayList<List<ItemStack>>(Collections.nCopies(9, (List<ItemStack>) null));
        for (int i = 0; i < items.size() && i < 9; i++) grid.set(i, alternatives(items.get(i)));
        return new View(result.copy(), grid, true);
    }

    private static List<ItemStack> alternatives(Object o) {
        if (o == null) return null;
        List<ItemStack> out = new ArrayList<ItemStack>();
        if (o instanceof ItemStack) add(out, (ItemStack) o);
        else if (o instanceof List) for (Object x : (List<?>) o) if (x instanceof ItemStack) add(out, (ItemStack) x);
        return out.isEmpty() ? null : out;
    }

    private static void add(List<ItemStack> out, ItemStack s) {
        if (s.getItem() == null) return;
        ItemStack c = s.copy();
        c.stackSize = 1;
        if (c.getItemDamage() == OreDictionary.WILDCARD_VALUE) c.setItemDamage(0);
        out.add(c);
    }
}
