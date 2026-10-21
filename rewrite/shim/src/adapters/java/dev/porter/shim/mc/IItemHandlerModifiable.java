// porter:adapter (generic) - Forge 1.19.2 IItemHandlerModifiable.
package dev.porter.shim.mc;

import net.minecraft.item.ItemStack;

public interface IItemHandlerModifiable extends IItemHandler {
    void setStackInSlot(int slot, ItemStack stack);
}
