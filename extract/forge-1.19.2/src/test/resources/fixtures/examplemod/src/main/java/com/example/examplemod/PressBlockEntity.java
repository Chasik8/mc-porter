package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class PressBlockEntity extends BlockEntity {
    private final NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
    private int progress;
    private String owner = "";
    private boolean locked;

    public PressBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRESS.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PressBlockEntity be) {
        be.progress++;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Progress", this.progress);
        tag.putString("Owner", this.owner);
        if (this.locked) {
            tag.putBoolean("Locked", true);
        }
        ContainerHelper.saveAllItems(tag, this.items);
    }
}
