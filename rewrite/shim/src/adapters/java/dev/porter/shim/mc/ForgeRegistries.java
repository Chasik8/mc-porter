// porter:adapter (generic) - Forge 1.19.2 ForgeRegistries (and vanilla Registry.BLOCK/ITEM) on 1.7.10 registries.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

public final class ForgeRegistries {
    private ForgeRegistries() {}

    public static final IForgeRegistry<Block> BLOCKS = new Simple<>("block", () -> list(Block.blockRegistry.getKeys(), k -> (Block) Block.blockRegistry.getObject(k)),
        b -> name(Block.blockRegistry.getNameForObject(b)), k -> (Block) Block.blockRegistry.getObject(k.toString()));
    public static final IForgeRegistry<Item> ITEMS = new Simple<>("item", () -> list(Item.itemRegistry.getKeys(), k -> (Item) Item.itemRegistry.getObject(k)),
        i -> name(Item.itemRegistry.getNameForObject(i)), k -> (Item) Item.itemRegistry.getObject(k.toString()));
    public static final IForgeRegistry<Fluid> FLUIDS = new Simple<>("fluid", () -> new ArrayList<>(FluidRegistry.getRegisteredFluids().values()),
        f -> f == null ? null : new ResourceLocation(f.getName()), k -> FluidRegistry.getFluid(k.getResourcePath()));
    public static final IForgeRegistry<Enchantment> ENCHANTMENTS = new Simple<>("enchantment", ForgeRegistries::enchantments,
        e -> e == null ? null : new ResourceLocation(e.getName()), k -> {
            for (Enchantment e : enchantments()) if (new ResourceLocation(e.getName()).equals(k)) return e;
            return null;
        });
    public static final IForgeRegistry<Potion> MOB_EFFECTS = new Simple<>("mob_effect", ForgeRegistries::potions,
        p -> p == null ? null : new ResourceLocation(p.getName()), k -> {
            for (Potion p : potions()) if (new ResourceLocation(p.getName()).equals(k)) return p;
            return null;
        });

    static ResourceLocation name(Object n) { return n == null ? null : new ResourceLocation(n.toString()); }

    @SuppressWarnings("rawtypes")
    static <V> List<V> list(Set keys, Function<String, V> get) {
        List<V> l = new ArrayList<>();
        for (Object k : keys) {
            V v = get.apply(k.toString());
            if (v != null) l.add(v);
        }
        return l;
    }

    static List<Enchantment> enchantments() {
        List<Enchantment> l = new ArrayList<>();
        for (Enchantment e : Enchantment.enchantmentsList) if (e != null) l.add(e);
        return l;
    }

    static List<Potion> potions() {
        List<Potion> l = new ArrayList<>();
        for (Potion p : Potion.potionTypes) if (p != null) l.add(p);
        return l;
    }

    static final class Simple<V> implements IForgeRegistry<V> {
        private final ResourceLocation name;
        private final Supplier<List<V>> values;
        private final Function<V, ResourceLocation> key;
        private final Function<ResourceLocation, V> value;

        Simple(String name, Supplier<List<V>> values, Function<V, ResourceLocation> key, Function<ResourceLocation, V> value) {
            this.name = new ResourceLocation("minecraft", name);
            this.values = values;
            this.key = key;
            this.value = value;
        }

        public ResourceLocation getKey(V v) { return key.apply(v); }

        public V getValue(ResourceLocation k) { return k == null ? null : value.apply(k); }

        public boolean containsKey(ResourceLocation k) { return getValue(k) != null; }

        public boolean containsValue(V v) { return getKey(v) != null; }

        public Set<ResourceLocation> getKeys() {
            Set<ResourceLocation> s = new LinkedHashSet<>();
            for (V v : values.get()) s.add(getKey(v));
            return s;
        }

        public Collection<V> getValues() { return values.get(); }

        public ResourceLocation getRegistryName() { return name; }

        public Iterator<V> iterator() { return values.get().iterator(); }
    }

    /** Forge 1.19.2 GameData.checkPrefix(name, warn): "modid:name" with the active mod's id when no domain is given. */
    public static ResourceLocation checkPrefix(String name) {
        if (name.indexOf(':') >= 0) return new ResourceLocation(name);
        cpw.mods.fml.common.ModContainer mc = cpw.mods.fml.common.Loader.instance().activeModContainer();
        return new ResourceLocation(mc == null ? "minecraft" : mc.getModId().toLowerCase(java.util.Locale.ROOT), name);
    }
}
