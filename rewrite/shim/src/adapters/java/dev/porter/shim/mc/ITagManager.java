// porter:adapter (generic) - Forge 1.19.2 ITagManager (IForgeRegistry.tags()): the values of a tag on 1.7.10 are the
// TagKey's explicit members (and predicate) plus, for items, the ore dictionary entries of its ore name.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

public final class ITagManager<V> {
    private final IForgeRegistry<V> registry;

    ITagManager(IForgeRegistry<V> registry) { this.registry = registry; }

    /** The values of a tag (an Iterable, like Forge's ITag). */
    @SuppressWarnings("unchecked")
    public List<V> getTag(TagKey<V> tag) {
        Set<V> out = new LinkedHashSet<>();
        for (V v : registry.getValues()) if (tag.contains(v)) out.add(v);
        for (Object o : tag.members()) out.add((V) o);
        if (tag.oreName() != null) {
            for (ItemStack s : OreDictionary.getOres(tag.oreName())) {
                Item i = s == null ? null : s.getItem();
                if (i != null && registry.containsValue((V) i)) out.add((V) i);
            }
        }
        return new ArrayList<>(out);
    }

    public boolean isKnownTagName(TagKey<V> tag) { return true; }
}
