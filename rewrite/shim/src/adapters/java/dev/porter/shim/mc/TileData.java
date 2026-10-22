// porter:adapter (generic) - Forge 1.19.2 IForgeBlockEntity.getTileData() / getPersistentData(): a per-tile NBT
// compound that is not part of the tile's own save data. 1.7.10 tiles have no such method; the compound is kept per
// tile instance (weak keys); ModernTileEntity saves and loads it under "ForgeData" (Forge's key), like Forge does.
package dev.porter.shim.mc;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

public final class TileData {
    private static final Map<TileEntity, NBTTagCompound> DATA = new WeakHashMap<>();

    private TileData() {}

    /** The persistent-data compound of a tile, created empty on first use. */
    public static synchronized NBTTagCompound get(TileEntity tile) {
        return DATA.computeIfAbsent(tile, t -> new NBTTagCompound());
    }

    /** The compound if the tile has one (null otherwise): what a save writes. */
    public static synchronized NBTTagCompound peek(TileEntity tile) {
        return DATA.get(tile);
    }

    /** Replaces the compound (loading a tile). */
    public static synchronized void set(TileEntity tile, NBTTagCompound data) {
        DATA.put(tile, data);
    }
}
