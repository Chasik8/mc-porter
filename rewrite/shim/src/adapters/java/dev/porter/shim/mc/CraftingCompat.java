// porter:adapter (the mod-specific, PRIVATE) - 1.19.2 CraftingContainer#clearContent() on the 1.7.10 InventoryCrafting.
package dev.porter.shim.mc;

import net.minecraft.inventory.InventoryCrafting;

public final class CraftingCompat {
    private CraftingCompat() {}

    public static void clear(InventoryCrafting grid) {
        for (int i = 0; i < grid.getSizeInventory(); i++) {
            grid.setInventorySlotContents(i, null);
        }
    }
}
