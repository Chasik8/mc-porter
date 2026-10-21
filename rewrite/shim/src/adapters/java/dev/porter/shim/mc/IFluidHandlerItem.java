// porter:adapter (generic) - Forge 1.19.2 IFluidHandlerItem.
package dev.porter.shim.mc;

import net.minecraft.item.ItemStack;

public interface IFluidHandlerItem extends IFluidHandler {
    ItemStack getContainer();
}
