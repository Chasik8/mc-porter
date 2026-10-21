// porter:adapter (generic) - 1.19.2 TagKey. 1.7.10 has no tags: a tag is an explicit member set, filled by the
// ported registration code (e.g. a hub's registerTags) and, for items, also answered by the OreDictionary using
// the Forge naming convention (forge:ingots/copper -> ingotCopper).
package dev.porter.shim.mc;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

public final class TagKey<T> {
    private static final Map<String, TagKey<?>> KEYS = new ConcurrentHashMap<>();
    private final String registry;
    private final ResourceLocation location;
    private final Set<Object> members = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile java.util.function.Predicate<Object> predicate;

    private TagKey(String registry, ResourceLocation location) {
        this.registry = registry;
        this.location = location;
    }

    @SuppressWarnings("unchecked")
    public static <T> TagKey<T> create(Object registry, ResourceLocation location) {
        String r = registry instanceof ResourceKey ? ((ResourceKey<?>) registry).location().toString() : String.valueOf(registry);
        return (TagKey<T>) KEYS.computeIfAbsent(r + "#" + location, k -> new TagKey<>(r, location));
    }

    public static <T> TagKey<T> of(String registry, String location) { return create(registry, new ResourceLocation(location)); }

    public ResourceLocation location() { return location; }

    @SafeVarargs
    public final TagKey<T> add(T... values) {
        Collections.addAll(members, values);
        return this;
    }

    /** members also given by a predicate (e.g. all gaseous fluids). */
    public TagKey<T> withPredicate(java.util.function.Predicate<Object> p) {
        this.predicate = p;
        return this;
    }

    public Set<Object> members() { return Collections.unmodifiableSet(new LinkedHashSet<>(members)); }

    public boolean contains(Object value) {
        if (value == null) return false;
        if (members.contains(value)) return true;
        if (predicate != null && predicate.test(value)) return true;
        if (value instanceof ItemStack) {
            ItemStack s = (ItemStack) value;
            if (members.contains(s.getItem())) return true;
            String ore = oreName();
            if (ore != null) for (int id : OreDictionary.getOreIDs(s)) if (OreDictionary.getOreName(id).equals(ore)) return true;
        }
        return false;
    }

    /** forge:ingots/copper -> ingotCopper (null when the path has no folder). */
    public String oreName() {
        String p = location.getResourcePath();
        int i = p.indexOf('/');
        if (i <= 0 || i == p.length() - 1) return null;
        String kind = p.substring(0, i);
        if (kind.endsWith("s")) kind = kind.substring(0, kind.length() - 1);
        String name = p.substring(i + 1).replace("/", "_");
        StringBuilder sb = new StringBuilder(kind);
        for (String part : name.split("_")) if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return sb.toString();
    }

    @Override
    public String toString() { return "TagKey[" + registry + " / " + location + "]"; }
}
