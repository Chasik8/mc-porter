package dev.porter.testkit.mc1710;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;

/**
 * What a pack can make (-Dporter.dumpObtainable=&lt;file&gt; on a dedicated server): every registered item, every crafting
 * recipe output and every smelting output, as registry names ("modid:name", "@meta" when not 0). The basis of a
 * survival check (items no recipe makes), compared between two builds of a mod by {@code porter test obtainable}.
 */
final class ObtainableDump {
    private ObtainableDump() {
    }

    static String name(ItemStack s) {
        if (s == null || s.getItem() == null) {
            return null;
        }
        Object n = Item.itemRegistry.getNameForObject(s.getItem());
        if (n == null) {
            return null;
        }
        int meta = s.getItemDamage();
        return meta == 0 || meta == Short.MAX_VALUE ? n.toString() : n + "@" + meta;
    }

    @SuppressWarnings("unchecked")
    static void write(File f) throws IOException {
        TreeSet<String> items = new TreeSet<>();
        for (Object k : Item.itemRegistry.getKeys()) {
            items.add(k.toString());
        }
        TreeSet<String> crafted = new TreeSet<>();
        for (Object r : CraftingManager.getInstance().getRecipeList()) {
            try {
                String n = name(((IRecipe) r).getRecipeOutput());
                if (n != null) {
                    crafted.add(n);
                }
            } catch (RuntimeException e) {
                // a mod recipe without a fixed output
            }
        }
        TreeSet<String> smelted = new TreeSet<>();
        for (Map.Entry<ItemStack, ItemStack> e : ((Map<ItemStack, ItemStack>) FurnaceRecipes.smelting().getSmeltingList()).entrySet()) {
            String n = name(e.getValue());
            if (n != null) {
                smelted.add(n);
            }
        }
        JsonObject o = new JsonObject();
        o.add("items", array(items));
        o.add("crafting", array(crafted));
        o.add("smelting", array(smelted));
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
        }
    }

    private static JsonArray array(TreeSet<String> s) {
        JsonArray a = new JsonArray();
        for (String x : s) {
            a.add(new com.google.gson.JsonPrimitive(x));
        }
        return a;
    }
}
