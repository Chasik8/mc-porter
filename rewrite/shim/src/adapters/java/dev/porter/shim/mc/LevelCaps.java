// porter:adapter (generic) - Forge 1.19.2 capabilities attached to a Level (AttachCapabilitiesEvent<Level>) on
// 1.7.10 worlds. On the first capability query of a world the attach event is posted on MinecraftForge.EVENT_BUS;
// the providers are kept per world (weakly). On the server, serializable providers are saved with the world in a
// per-dimension WorldSavedData ("porter_caps", always dirty: written on every world save) and restored when the
// providers are created. Only Level attach events are posted (1.7.10 subscribers cannot tell generic types apart).
package dev.porter.shim.mc;

import dev.porter.shim.Direction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.MinecraftForge;

public final class LevelCaps {
    private static final Map<World, Map<String, ICapabilityProvider>> PROVIDERS = new WeakHashMap<>();
    static final String DATA = "porter_caps";

    private LevelCaps() {}

    public static <T> LazyOptional<T> get(World w, Capability<T> cap) { return get(w, cap, null); }

    public static <T> LazyOptional<T> get(World w, Capability<T> cap, Direction side) {
        if (w == null) return LazyOptional.empty();
        for (ICapabilityProvider p : providers(w).values()) {
            LazyOptional<T> o = p.getCapability(cap, side);
            if (o.isPresent()) return o;
        }
        return LazyOptional.empty();
    }

    public static synchronized Map<String, ICapabilityProvider> providers(World w) {
        Map<String, ICapabilityProvider> m = PROVIDERS.get(w);
        if (m != null) return m;
        AttachCapabilitiesEvent<World> e = new AttachCapabilitiesEvent<>(World.class, w);
        MinecraftForge.EVENT_BUS.post(e);
        m = new LinkedHashMap<>(e.getCapabilities());
        PROVIDERS.put(w, m);
        if (!w.isRemote && w.perWorldStorage != null) {
            Data d = (Data) w.perWorldStorage.loadData(Data.class, DATA);
            if (d == null) {
                d = new Data(DATA);
                w.perWorldStorage.setData(DATA, d);
            }
            d.attach(m);
        }
        return m;
    }

    /** per-world saved data of the serializable providers (key = provider ResourceLocation) */
    public static final class Data extends WorldSavedData {
        private NBTTagCompound pending = new NBTTagCompound();
        private Map<String, ICapabilityProvider> providers;

        public Data(String name) { super(name); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void attach(Map<String, ICapabilityProvider> m) {
            providers = m;
            for (Map.Entry<String, ICapabilityProvider> en : m.entrySet()) {
                if (en.getValue() instanceof INBTSerializable && pending.hasKey(en.getKey())) {
                    ((INBTSerializable) en.getValue()).deserializeNBT(pending.getTag(en.getKey()));
                }
            }
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) { pending = tag; }

        @Override
        @SuppressWarnings("rawtypes")
        public void writeToNBT(NBTTagCompound tag) {
            if (providers == null) {
                for (Object k : pending.func_150296_c()) tag.setTag((String) k, pending.getTag((String) k));
                return;
            }
            for (Map.Entry<String, ICapabilityProvider> en : providers.entrySet()) {
                if (en.getValue() instanceof INBTSerializable) {
                    Object v = ((INBTSerializable) en.getValue()).serializeNBT();
                    if (v instanceof NBTBase) tag.setTag(en.getKey(), (NBTBase) v);
                }
            }
        }

        @Override
        public boolean isDirty() { return true; }
    }
}
