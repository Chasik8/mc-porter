package com.example.examplemod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class PressMenu extends AbstractContainerMenu {
    private final ContainerData data;

    public PressMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, new SimpleContainer(3), new SimpleContainerData(2));
    }

    public PressMenu(int id, Inventory inv, Container container, ContainerData data) {
        super(ModMenus.PRESS.get(), id);
        this.data = data;
        this.addSlot(new Slot(container, 0, 56, 17));
        this.addSlot(new Slot(container, 1, 56, 53));
        this.addSlot(new FurnaceResultSlot(inv.player, container, 2, 116, 35));
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 98 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, 156));
        }
        this.addDataSlots(data);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
