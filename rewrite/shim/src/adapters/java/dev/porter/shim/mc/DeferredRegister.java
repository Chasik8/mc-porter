// porter:adapter (generic) - Forge 1.19.2 DeferredRegister on the 1.7.10 GameRegistry. register(name, supplier)
// records an entry; register(bus) - called from the mod constructor, i.e. in 1.7.10 preInit - creates and registers
// every entry in order. Blocks are registered without an ItemBlock (block items are registered by the item
// register, FML links an ItemBlock to its block), get unlocalized name "<modid>.<camelName>" (porter legacy names,
// = the generated lang files) and texture "<modid>:<name>" unless the constructor set them. Block entity types
// register their tile class under "<modid>:<name>".
package dev.porter.shim.mc;

import cpw.mods.fml.common.registry.GameRegistry;
import dev.porter.shim.BlockPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

public class DeferredRegister<T> {
    /** 1.19.2 Registry.*_REGISTRY keys, as the registry kinds this adapter knows */
    public static final String BLOCK_REGISTRY = "block";
    public static final String ITEM_REGISTRY = "item";
    public static final String BLOCK_ENTITY_TYPE_REGISTRY = "block_entity_type";
    public static final String ENTITY_TYPE_REGISTRY = "entity_type";
    public static final String MENU_REGISTRY = "menu";
    public static final String SOUND_EVENT_REGISTRY = "sound_event";
    public static final String RECIPE_SERIALIZER_REGISTRY = "recipe_serializer";
    public static final String RECIPE_TYPE_REGISTRY = "recipe_type";

    private final String kind;
    private final String modid;
    private final List<RegistryObject<? extends T>> entries = new ArrayList<>();
    private boolean registered;

    private DeferredRegister(String kind, String modid) {
        this.kind = kind;
        this.modid = modid;
    }

    public static <T> DeferredRegister<T> create(String registryKind, String modid) { return new DeferredRegister<>(registryKind, modid); }

    public static <T> DeferredRegister<T> create(IForgeRegistry<T> registry, String modid) {
        return new DeferredRegister<>(registry.getRegistryName().getResourcePath(), modid);
    }

    public static <T> DeferredRegister<T> create(ResourceLocation registryName, String modid) {
        return new DeferredRegister<>(registryName.getResourcePath(), modid);
    }

    public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> sup) {
        if (registered) throw new IllegalStateException("DeferredRegister " + modid + ":" + kind + " already registered, cannot add " + name);
        RegistryObject<I> o = new RegistryObject<>(new ResourceLocation(modid, name), sup);
        entries.add(o);
        return o;
    }

    public <I extends T> RegistryObject<I> register(String name, Function<ResourceLocation, ? extends I> f) {
        final ResourceLocation id = new ResourceLocation(modid, name);
        return register(name, () -> f.apply(id));
    }

    public Collection<RegistryObject<T>> getEntries() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Collection<RegistryObject<T>> c = (Collection) Collections.unmodifiableList(entries);
        return c;
    }

    /** registers every entry now (1.7.10 preInit) */
    public void register(IEventBus bus) {
        if (registered) return;
        registered = true;
        for (RegistryObject<? extends T> o : entries) registerOne(o);
    }

    private void registerOne(RegistryObject<? extends T> o) {
        String name = o.getId().getResourcePath();
        Object v = o.get();
        if (v instanceof Block) {
            Block b = (Block) v;
            if (b.getUnlocalizedName().equals("tile.null")) b.setBlockName(modid + "." + camel(name));
            if (texture(b) == null) b.setBlockTextureName(modid + ":" + name);
            if (b instanceof StateBlock) ((StateBlock) b).porterPostInit(); // final shape -> opacity, bounds
            GameRegistry.registerBlock(b, null, name);
        } else if (v instanceof Item) {
            Item i = (Item) v;
            if (i.getUnlocalizedName().equals("item.null")) i.setUnlocalizedName(modid + "." + camel(name));
            if (!(i instanceof net.minecraft.item.ItemBlock) && itemTexture(i) == null) i.setTextureName(modid + ":" + name);
            GameRegistry.registerItem(i, name);
        } else if (v instanceof BlockEntityType) {
            TileEntity te = ((BlockEntityType<?>) v).create(new BlockPos(0, 0, 0), null);
            if (te != null) GameRegistry.registerTileEntity(te.getClass(), modid + ":" + name);
        } else if (v != null) {
            ModLifecycle.LOG.info("DeferredRegister {}:{} - {} kept in memory only (no 1.7.10 registry)", modid, kind, name);
        }
    }

    static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '/' || c == '.') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }

    private static String texture(Block b) {
        try {
            java.lang.reflect.Field f = Block.class.getDeclaredField("textureName");
            f.setAccessible(true);
            return (String) f.get(b);
        } catch (ReflectiveOperationException e) {
            try {
                java.lang.reflect.Field f = Block.class.getDeclaredField("field_149768_d");
                f.setAccessible(true);
                return (String) f.get(b);
            } catch (ReflectiveOperationException e2) {
                return null;
            }
        }
    }

    private static String itemTexture(Item i) {
        try {
            java.lang.reflect.Field f = Item.class.getDeclaredField("iconString");
            f.setAccessible(true);
            return (String) f.get(i);
        } catch (ReflectiveOperationException e) {
            try {
                java.lang.reflect.Field f = Item.class.getDeclaredField("field_111218_cA");
                f.setAccessible(true);
                return (String) f.get(i);
            } catch (ReflectiveOperationException e2) {
                return null;
            }
        }
    }
}
