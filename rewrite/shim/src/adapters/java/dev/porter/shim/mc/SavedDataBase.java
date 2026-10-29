// porter:adapter (the mod teleporters) - 1.19.2 SavedData on 1.7.10 WorldSavedData. The ported classes keep their 1.19 shape
// (no-arg ctor, ctor(NBTTagCompound) = load, save(NBTTagCompound) = save); 1.7.10 needs the map name in the super ctor and
// builds saved data through a reflective ctor(String), so instances are created only through Storage.computeIfAbsent
// (the 1.19 DimensionDataStorage), which hands the name to the super ctor via a thread local.
package dev.porter.shim.mc;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

public abstract class SavedDataBase extends WorldSavedData {
    private static final ThreadLocal<String> PENDING_NAME = new ThreadLocal<String>();
    private static final Map<MapStorage, Map<String, SavedDataBase>> CACHE = new WeakHashMap<MapStorage, Map<String, SavedDataBase>>();

    protected SavedDataBase() {
        super(takeName());
    }

    private static String takeName() {
        String n = PENDING_NAME.get();
        PENDING_NAME.remove();
        return n != null ? n : "porter_unnamed";
    }

    /** 1.19 SavedData#save: writes into (and returns) the given tag. */
    public abstract NBTTagCompound save(NBTTagCompound tag);

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        // loading happens in the 1.19-style ctor(NBTTagCompound)
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        this.save(tag);
    }

    /** 1.19 SavedData#setDirty(). */
    public void setDirty() {
        this.markDirty();
    }

    /** 1.19 ServerLevel#getDataStorage(). */
    public static Storage storage(World world) {
        return new Storage(world);
    }

    /** 1.19 DimensionDataStorage. */
    public static final class Storage {
        private final World world;

        Storage(World world) {
            this.world = world;
        }

        @SuppressWarnings("unchecked")
        public <T extends SavedDataBase> T computeIfAbsent(Function<NBTTagCompound, T> load, Supplier<T> create, String name) {
            MapStorage ms = this.world.provider.dimensionId == 0 ? this.world.mapStorage : this.world.perWorldStorage;
            synchronized (CACHE) {
                Map<String, SavedDataBase> byName = CACHE.get(ms);
                if (byName == null) {
                    byName = new HashMap<String, SavedDataBase>();
                    CACHE.put(ms, byName);
                }
                SavedDataBase cached = byName.get(name);
                if (cached != null) {
                    return (T) cached;
                }
                NBTTagCompound data = readFile(this.world, name);
                PENDING_NAME.set(name);
                T result;
                try {
                    result = data != null ? load.apply(data) : create.get();
                } finally {
                    PENDING_NAME.remove();
                }
                byName.put(name, result);
                ms.setData(name, result);
                return result;
            }
        }

        private static NBTTagCompound readFile(World world, String name) {
            try {
                if (world.getSaveHandler() == null) {
                    return null;
                }
                File file = world.getSaveHandler().getMapFileFromName(name);
                if (file == null || !file.exists()) {
                    return null;
                }
                FileInputStream in = new FileInputStream(file);
                try {
                    return CompressedStreamTools.readCompressed(in).getCompoundTag("data");
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                throw new RuntimeException("cannot read saved data " + name, e);
            }
        }
    }
}
