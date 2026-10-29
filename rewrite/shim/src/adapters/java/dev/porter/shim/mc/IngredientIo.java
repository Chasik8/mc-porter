// porter:adapter (the mod) - Ingredient JSON/network (de)serialisation. 1.7.10 has no datapack recipes: the wire format is the explicit stack list.
package dev.porter.shim.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

public final class IngredientIo {
    private IngredientIo() {}

    public static Ingredient fromNetwork(PacketBuffer buffer) {
        int n = buffer.readVarIntFromBuffer();
        ItemStack[] stacks = new ItemStack[n];
        try {
            for (int i = 0; i < n; i++) {
                stacks[i] = buffer.readItemStackFromBuffer();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Ingredient.of(stacks);
    }

    public static void toNetwork(Ingredient ingredient, PacketBuffer buffer) {
        ItemStack[] items = ingredient.getItems();
        buffer.writeVarIntToBuffer(items.length);
        try {
            for (ItemStack s : items) {
                buffer.writeItemStackToBuffer(s);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** {"item":"modid:name","meta":n} or an array of those; {"ore":"name"} is the ore dictionary. */
    public static Ingredient fromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            throw new com.google.gson.JsonSyntaxException("Item cannot be null");
        }
        List<ItemStack> out = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) {
                collect(e.getAsJsonObject(), out);
            }
        } else {
            collect(json.getAsJsonObject(), out);
        }
        return Ingredient.of(out.toArray(new ItemStack[0]));
    }

    private static void collect(JsonObject o, List<ItemStack> out) {
        if (o.has("ore")) {
            out.addAll(net.minecraftforge.oredict.OreDictionary.getOres(o.get("ore").getAsString()));
            return;
        }
        Item item = CraftingHelper.getItem(o.get("item").getAsString(), true);
        out.add(new ItemStack(item, 1, o.has("meta") ? o.get("meta").getAsInt() : net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE));
    }

    public static JsonElement toJson(Ingredient ingredient) {
        JsonArray arr = new JsonArray();
        for (ItemStack s : ingredient.getItems()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", String.valueOf(Item.itemRegistry.getNameForObject(s.getItem())));
            o.addProperty("meta", s.getItemDamage());
            arr.add(o);
        }
        return arr;
    }
}
