package ic2cextras.book.client;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.apache.commons.io.IOUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2.core.Ic2Items;
import ic2cextras.IC2CExtras;

/**
 * The page registry, read from {@code assets/ic2cextras/guide/guide.json} in every resource pack / mod jar that has
 * one (categories with the same id are merged, so a later file can add entries to an existing category). See
 * docs/guide-book.md for the format.
 */
@SideOnly(Side.CLIENT)
public final class GuideData {

    public static final ResourceLocation FILE = new ResourceLocation(IC2CExtras.MODID, "guide/guide.json");

    public static final class Page {

        public final String type;
        public final JsonObject json;

        Page(String type, JsonObject json) {
            this.type = type;
            this.json = json;
        }

        public String str(String key) {
            JsonElement e = json.get(key);
            return e == null || e.isJsonNull() ? null : e.getAsString();
        }

        public List<String> list(String key) {
            List<String> out = new ArrayList<String>();
            JsonElement e = json.get(key);
            if (e == null) return out;
            if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
            else out.add(e.getAsString());
            return out;
        }
    }

    public static final class Entry {

        public final String id;
        public final String titleKey;
        public final String icon;
        public final List<Page> pages = new ArrayList<Page>();
        public Category category;

        Entry(String id, String titleKey, String icon) {
            this.id = id;
            this.titleKey = titleKey;
            this.icon = icon;
        }

        public String title() {
            if (titleKey != null && StatCollector.canTranslate(titleKey))
                return StatCollector.translateToLocal(titleKey);
            ItemStack s = iconStack();
            return s != null ? s.getDisplayName() : id;
        }

        public ItemStack iconStack() {
            return parseItem(icon);
        }
    }

    public static final class Category {

        public final String id;
        public String titleKey;
        public String icon;
        public final List<Entry> entries = new ArrayList<Entry>();

        Category(String id) {
            this.id = id;
        }

        public String title() {
            return StatCollector.translateToLocal(titleKey != null ? titleKey : "ic2cextras.guide.cat." + id);
        }
    }

    public final List<Category> categories = new ArrayList<Category>();
    public final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    private GuideData() {}

    /** Reads every guide.json; errors are logged and the broken file skipped. */
    @SuppressWarnings("unchecked")
    public static GuideData load() {
        GuideData data = new GuideData();
        Map<String, Category> byId = new LinkedHashMap<String, Category>();
        List<IResource> files;
        try {
            files = Minecraft.getMinecraft()
                .getResourceManager()
                .getAllResources(FILE);
        } catch (Exception e) {
            IC2CExtras.LOG.error("[guide] no {}", FILE, e);
            files = Collections.emptyList();
        }
        for (IResource res : files) {
            InputStream in = null;
            try {
                in = res.getInputStream();
                JsonObject root = new JsonParser().parse(new InputStreamReader(in, "UTF-8"))
                    .getAsJsonObject();
                for (JsonElement ce : root.getAsJsonArray("categories")) {
                    JsonObject co = ce.getAsJsonObject();
                    String id = co.get("id")
                        .getAsString();
                    Category cat = byId.get(id);
                    if (cat == null) {
                        cat = new Category(id);
                        byId.put(id, cat);
                    }
                    if (co.has("title")) cat.titleKey = co.get("title")
                        .getAsString();
                    if (co.has("icon")) cat.icon = co.get("icon")
                        .getAsString();
                    JsonArray list = co.getAsJsonArray("entries");
                    if (list == null) continue;
                    for (JsonElement ee : list) {
                        Entry entry = readEntry(ee.getAsJsonObject());
                        entry.category = cat;
                        Entry old = data.entries.put(entry.id, entry);
                        if (old != null) old.category.entries.remove(old);
                        cat.entries.add(entry);
                    }
                }
            } catch (Exception e) {
                IC2CExtras.LOG.error("[guide] broken guide file {}", FILE, e);
            } finally {
                IOUtils.closeQuietly(in);
            }
        }
        data.categories.addAll(byId.values());
        return data;
    }

    private static Entry readEntry(JsonObject o) {
        String id = o.get("id")
            .getAsString();
        String title = o.has("title") ? o.get("title")
            .getAsString() : "ic2cextras.guide." + id;
        Entry entry = new Entry(
            id,
            title,
            o.has("icon") ? o.get("icon")
                .getAsString() : null);
        JsonArray pages = o.getAsJsonArray("pages");
        if (pages != null) for (JsonElement pe : pages) {
            JsonObject po = pe.getAsJsonObject();
            entry.pages.add(
                new Page(
                    po.get("type")
                        .getAsString(),
                    po));
        }
        return entry;
    }

    /**
     * Item spec: {@code modid:name}, {@code modid:name@meta} (registry name), or {@code ic2:fieldName[@meta]} for a
     * field of IC2C's {@code Ic2Items}. Returns null if unknown.
     */
    public static ItemStack parseItem(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        int meta = -1;
        int at = spec.lastIndexOf('@');
        if (at > 0) {
            try {
                meta = Integer.parseInt(spec.substring(at + 1));
            } catch (NumberFormatException ignored) {}
            spec = spec.substring(0, at);
        }
        ItemStack stack = null;
        if (spec.startsWith("ic2:")) {
            try {
                Field f = Ic2Items.class.getField(spec.substring(4));
                Object v = f.get(null);
                if (v instanceof ItemStack) stack = ((ItemStack) v).copy();
            } catch (Exception ignored) {}
        } else {
            Object o = Item.itemRegistry.getObject(spec);
            if (o instanceof Item) stack = new ItemStack((Item) o);
        }
        if (stack == null || stack.getItem() == null) return null;
        stack.stackSize = 1;
        if (meta >= 0) stack.setItemDamage(meta);
        return stack;
    }
}
