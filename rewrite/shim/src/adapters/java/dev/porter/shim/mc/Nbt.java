// porter:adapter (generic) - 1.19.2 CompoundTag/ListTag members without a 1.7.10 equivalent: long arrays (stored as
// an int array of hi/lo pairs under the same key), size(), list element access, tag values as strings.
package dev.porter.shim.mc;

import cpw.mods.fml.relauncher.ReflectionHelper;
import java.util.List;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;

public final class Nbt {
    private Nbt() {}

    public static void putLongArray(NBTTagCompound t, String key, long[] v) {
        int[] a = new int[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            a[2 * i] = (int) (v[i] >>> 32);
            a[2 * i + 1] = (int) v[i];
        }
        t.setIntArray(key, a);
    }

    public static long[] getLongArray(NBTTagCompound t, String key) {
        int[] a = t.getIntArray(key);
        long[] v = new long[a.length / 2];
        for (int i = 0; i < v.length; i++) v[i] = (long) a[2 * i] << 32 | a[2 * i + 1] & 0xFFFFFFFFL;
        return v;
    }

    public static int size(NBTTagCompound t) { return t.func_150296_c().size(); }

    @SuppressWarnings("unchecked")
    static List<NBTBase> raw(NBTTagList l) {
        return (List<NBTBase>) ReflectionHelper.getPrivateValue(NBTTagList.class, l, "field_74747_a", "tagList");
    }

    /** ListTag.get(i) */
    public static NBTBase get(NBTTagList l, int i) {
        List<NBTBase> r = raw(l);
        return i >= 0 && i < r.size() ? r.get(i) : null;
    }

    /** ListTag.set(i, tag) */
    public static NBTBase set(NBTTagList l, int i, NBTBase tag) {
        return raw(l).set(i, tag);
    }

    /** ListTag.getInt(i) */
    public static int getInt(NBTTagList l, int i) {
        NBTBase b = get(l, i);
        return b instanceof NBTBase.NBTPrimitive ? ((NBTBase.NBTPrimitive) b).func_150287_d() : 0;
    }

    /** ListTag.getIntArray(i) */
    public static int[] getIntArray(NBTTagList l, int i) {
        NBTBase b = get(l, i);
        return b instanceof NBTTagIntArray ? ((NBTTagIntArray) b).func_150302_c() : new int[0];
    }

    /** Tag.getAsString() */
    /** CompoundTag.merge: copies every entry of {@code from} into {@code into}, merging nested compounds. */
    public static NBTTagCompound merge(NBTTagCompound into, NBTTagCompound from) {
        if (from == null || from == into) return into;
        for (String k : keys(from)) {
            NBTBase v = from.getTag(k);
            if (v instanceof NBTTagCompound && into.hasKey(k, 10)) merge(into.getCompoundTag(k), (NBTTagCompound) v);
            else into.setTag(k, v.copy());
        }
        return into;
    }

    public static String asString(NBTBase b) { return b == null ? "" : b.toString(); }

    /** CompoundTag.getAllKeys() on a compound: 1.7.10 func_150296_c */
    @SuppressWarnings("unchecked")
    public static java.util.Set<String> keys(NBTTagCompound t) { return t.func_150296_c(); }

    /** CompoundTag.putUUID: 1.19.2 stores an int[4]; 1.7.10 readers of these tags are the ported code only. */
    public static void putUUID(NBTTagCompound t, String key, java.util.UUID u) {
        long m = u.getMostSignificantBits(), l = u.getLeastSignificantBits();
        t.setIntArray(key, new int[] {(int) (m >> 32), (int) m, (int) (l >> 32), (int) l});
    }

    public static java.util.UUID getUUID(NBTTagCompound t, String key) {
        int[] a = t.getIntArray(key);
        if (a.length != 4) return new java.util.UUID(0L, 0L);
        return new java.util.UUID((long) a[0] << 32 | a[1] & 0xFFFFFFFFL, (long) a[2] << 32 | a[3] & 0xFFFFFFFFL);
    }

    public static boolean hasUUID(NBTTagCompound t, String key) { return t.getIntArray(key).length == 4; }

    /** ListTag iteration (1.7.10 NBTTagList is not Iterable). */
    public static Iterable<NBTBase> iterate(NBTTagList l) { return raw(l); }

    /** LongArrayTag.getAsLongArray (long arrays are int pairs on 1.7.10, see putLongArray). */
    public static long[] longs(NBTTagIntArray t) {
        int[] a = t.func_150302_c();
        long[] v = new long[a.length / 2];
        for (int i = 0; i < v.length; i++) v[i] = (long) a[2 * i] << 32 | a[2 * i + 1] & 0xFFFFFFFFL;
        return v;
    }

    /** TagParser.parseTag (SNBT); 1.7.10 JsonToNBT reads the same syntax for compounds. */
    public static NBTTagCompound parseTag(String s) {
        try {
            NBTBase b = net.minecraft.nbt.JsonToNBT.func_150315_a(s);
            return b instanceof NBTTagCompound ? (NBTTagCompound) b : new NBTTagCompound();
        } catch (net.minecraft.nbt.NBTException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** NbtUtils.writeBlockState: {Name: registry name, Meta: metadata}. */
    public static NBTTagCompound writeBlockState(BlockState s) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString("Name", String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(s.getBlock())));
        t.setInteger("Meta", s.getMeta());
        return t;
    }

    public static BlockState readBlockState(NBTTagCompound t) {
        net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName(t.getString("Name"));
        return BlockState.of(b, t.getInteger("Meta"));
    }
}
