package dev.porter.shim.gen.nbt;

import net.minecraft.nbt.NBTBase;

/** A 1.19.2 {@code Tag} view of a 1.7.10 NBT value (1.7.10 NBT classes do not implement the mirror interface). */
public final class BTagBox implements Tag {
    public final NBTBase nbt;

    public BTagBox(NBTBase nbt) {
        this.nbt = nbt;
    }

    @Override
    public String toString() {
        return String.valueOf(nbt);
    }
}
