package com.example.demo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Mth;

/** A 1.19.2-style machine after a partial rewrite: the loop has to finish the job (synthetic test data). */
public class Machine extends TileEntity {
    private int energy;

    @Override
    public void tick() {
        if (worldObj.isClientSide) {
            return;
        }
        energy++;
    }

    public void save(CompoundTag tag) {
        tag.putInt("energy", energy);
    }

    public void load(CompoundTag tag) {
        energy = tag.getInt("energy");
    }

    public int scaled() {
        return Mth.clamp(energy, 0, 100);
    }
}
