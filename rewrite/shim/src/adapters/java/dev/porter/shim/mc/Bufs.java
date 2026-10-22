// porter:adapter (generic) - 1.19.2 FriendlyByteBuf members on the 1.7.10 PacketBuffer.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import java.io.IOException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

public final class Bufs {
    private Bufs() {}

    public static PacketBuffer writeUtf(PacketBuffer b, String s) {
        try {
            b.writeStringToBuffer(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return b;
    }

    public static String readUtf(PacketBuffer b) {
        try {
            return b.readStringFromBuffer(32767);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static PacketBuffer writeResourceLocation(PacketBuffer b, ResourceLocation r) { return writeUtf(b, r.toString()); }

    public static ResourceLocation readResourceLocation(PacketBuffer b) { return new ResourceLocation(readUtf(b)); }

    public static PacketBuffer writeItem(PacketBuffer b, ItemStack s) {
        try {
            b.writeItemStackToBuffer(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return b;
    }

    public static ItemStack readItem(PacketBuffer b) {
        try {
            return b.readItemStackFromBuffer();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static PacketBuffer writeNbt(PacketBuffer b, NBTTagCompound t) {
        try {
            b.writeNBTTagCompoundToBuffer(t);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return b;
    }

    public static NBTTagCompound readNbt(PacketBuffer b) {
        try {
            return b.readNBTTagCompoundFromBuffer();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static PacketBuffer writeVarInt(PacketBuffer b, int v) {
        b.writeVarIntToBuffer(v);
        return b;
    }

    public static PacketBuffer writeByteArray(PacketBuffer b, byte[] a) {
        b.writeVarIntToBuffer(a.length);
        b.writeBytes(a);
        return b;
    }

    public static byte[] readByteArray(PacketBuffer b) {
        byte[] a = new byte[b.readVarIntFromBuffer()];
        b.readBytes(a);
        return a;
    }

    public static PacketBuffer writeVarIntArray(PacketBuffer b, int[] a) {
        b.writeVarIntToBuffer(a.length);
        for (int i : a) b.writeVarIntToBuffer(i);
        return b;
    }

    public static int[] readVarIntArray(PacketBuffer b) {
        int[] a = new int[b.readVarIntFromBuffer()];
        for (int i = 0; i < a.length; i++) a[i] = b.readVarIntFromBuffer();
        return a;
    }

    public static PacketBuffer writeBlockPos(PacketBuffer b, BlockPos p) {
        b.writeLong(p.asLong());
        return b;
    }

    public static BlockPos readBlockPos(PacketBuffer b) { return BlockPos.of(b.readLong()); }

    public static PacketBuffer writeComponent(PacketBuffer b, IChatComponent c) { return writeUtf(b, IChatComponent.Serializer.func_150696_a(c)); }

    public static IChatComponent readComponent(PacketBuffer b) { return IChatComponent.Serializer.func_150699_a(readUtf(b)); }

    public static PacketBuffer writeFluidStack(PacketBuffer b, FluidStack f) {
        if (f == null) return writeNbt(b, null);
        return writeNbt(b, f.writeToNBT(new NBTTagCompound()));
    }

    public static FluidStack readFluidStack(PacketBuffer b) {
        NBTTagCompound t = readNbt(b);
        return t == null ? null : FluidStack.loadFluidStackFromNBT(t);
    }

    public static PacketBuffer writeUUID(PacketBuffer b, java.util.UUID u) {
        b.writeLong(u.getMostSignificantBits());
        b.writeLong(u.getLeastSignificantBits());
        return b;
    }

    public static java.util.UUID readUUID(PacketBuffer b) { return new java.util.UUID(b.readLong(), b.readLong()); }

    /** ResourceLocation.tryParse (null when the text is no valid location). */
    /** FriendlyByteBuf.writeRegistryIdUnsafe: the entry's registry name (1.7.10 numeric ids differ per world). */
    public static <V> PacketBuffer writeRegistryId(PacketBuffer b, IForgeRegistry<V> registry, Object value) {
        @SuppressWarnings("unchecked")
        ResourceLocation key = value == null ? null : registry.getKey((V) value);
        return writeUtf(b, key == null ? "" : key.toString());
    }

    /** FriendlyByteBuf.readRegistryIdUnsafe */
    public static <V> V readRegistryId(PacketBuffer b, IForgeRegistry<V> registry) {
        String s = readUtf(b);
        return s.isEmpty() ? null : registry.getValue(new ResourceLocation(s));
    }

    public static ResourceLocation tryParse(String s) {
        if (s == null || s.isEmpty() || s.indexOf(':') != s.lastIndexOf(':')) return null;
        return new ResourceLocation(s);
    }

    /** CraftingHelper.getItemStack(json, readNbt): {"item": "modid:name", "count": n, "data": meta, "nbt": "{...}"}. */
    public static ItemStack itemFromJson(com.google.gson.JsonObject j) {
        net.minecraft.item.Item item = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry.getObject(j.get("item").getAsString());
        if (item == null) return null;
        int count = j.has("count") ? j.get("count").getAsInt() : 1;
        int meta = j.has("data") ? j.get("data").getAsInt() : 0;
        ItemStack s = new ItemStack(item, count, meta);
        if (j.has("nbt")) s.setTagCompound(Nbt.parseTag(j.get("nbt").isJsonObject() ? j.get("nbt").toString() : j.get("nbt").getAsString()));
        return s;
    }
}
