// porter:adapter (generic) - Forge 1.19.2 IItemHandler (empty stack = null on 1.7.10).
package dev.porter.shim.mc;

import net.minecraft.item.ItemStack;

public interface IItemHandler {
    int getSlots();

    ItemStack getStackInSlot(int slot);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);
}
