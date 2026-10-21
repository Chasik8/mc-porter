// porter:adapter (generic) - 1.19.2 NbtUtils block-position helpers (same "X"/"Y"/"Z" keys) and int-list arrays.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;

public final class NbtUtils {
    private NbtUtils() {}

    public static NBTTagCompound writeBlockPos(BlockPos p) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("X", p.getX());
        t.setInteger("Y", p.getY());
        t.setInteger("Z", p.getZ());
        return t;
    }

    public static BlockPos readBlockPos(NBTTagCompound t) { return new BlockPos(t.getInteger("X"), t.getInteger("Y"), t.getInteger("Z")); }

    /** new IntArrayTag(List<Integer>) */
    public static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
