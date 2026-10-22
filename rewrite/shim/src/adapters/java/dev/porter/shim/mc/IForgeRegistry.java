// porter:adapter (generic) - Forge 1.19.2 IForgeRegistry over the 1.7.10 registries (names = "modid:name").
package dev.porter.shim.mc;

import java.util.Collection;
import java.util.Set;
import net.minecraft.util.ResourceLocation;

public interface IForgeRegistry<V> extends Iterable<V> {
    ResourceLocation getKey(V value);

    V getValue(ResourceLocation key);

    boolean containsKey(ResourceLocation key);

    boolean containsValue(V value);

    Set<ResourceLocation> getKeys();

    Collection<V> getValues();

    ResourceLocation getRegistryName();

    /** 1.19.2 registries carry tags; 1.7.10 has none (OreDictionary is per item stack). */
    default java.util.stream.Stream<TagKey<V>> getTagNames() { return java.util.stream.Stream.empty(); }

    default boolean isEmpty() { return getValues().isEmpty(); }

    /** The registry's tag manager: tag members are the TagKey's explicit members plus, for items, the ore dictionary. */
    default ITagManager<V> tags() { return new ITagManager<>(this); }
}
