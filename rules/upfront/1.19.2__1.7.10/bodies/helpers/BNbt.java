package dev.porter.shim.gen.nbt;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagString;

/**
 * Conversion between the 1.19.2 mirror tags and 1.7.10 NBT values. Long arrays are stored as int pairs (hi, lo), the
 * same layout the NBT shim uses for {@code putLongArray}.
 */
public final class BNbt {
    private BNbt() {}

    /** The 1.7.10 value of a mirror tag (or of an NBT value itself); null when there is none. */
    public static NBTBase unwrap(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof NBTBase) {
            return (NBTBase) o;
        }
        if (o instanceof BTagBox) {
            return ((BTagBox) o).nbt;
        }
        if (o instanceof BStringTag) {
            return ((BStringTag) o).nbt;
        }
        if (o instanceof ByteArrayTag) {
            Object v = BState.get(o, "v");
            return new NBTTagByteArray(v instanceof byte[] ? (byte[]) v : new byte[0]);
        }
        if (o instanceof IntArrayTag) {
            Object v = BState.get(o, "v");
            return new NBTTagIntArray(v instanceof int[] ? (int[]) v : new int[0]);
        }
        if (o instanceof LongArrayTag) {
            Object v = BState.get(o, "v");
            long[] l = v instanceof long[] ? (long[]) v : new long[0];
            int[] a = new int[l.length * 2];
            for (int i = 0; i < l.length; i++) {
                a[2 * i] = (int) (l[i] >>> 32);
                a[2 * i + 1] = (int) l[i];
            }
            return new NBTTagIntArray(a);
        }
        return null;
    }

    /** The mirror view of a 1.7.10 NBT value; null stays null. */
    public static Tag wrap(NBTBase b) {
        if (b == null) {
            return null;
        }
        if (b instanceof NBTTagString) {
            return new BStringTag((NBTTagString) b);
        }
        return new BTagBox(b);
    }
}
