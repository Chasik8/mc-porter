package dev.porter.shim.gen.nbt;

import net.minecraft.nbt.NBTTagString;

/** The mirror {@code StringTag} over a 1.7.10 NBTTagString. */
public final class BStringTag extends StringTag {
    public final NBTTagString nbt;

    public BStringTag(NBTTagString nbt) {
        this.nbt = nbt;
    }

    @Override
    public String toString() {
        return String.valueOf(nbt);
    }
}
