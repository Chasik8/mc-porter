// porter:adapter (generic) - Forge 1.19.2 FluidActionResult.
package dev.porter.shim.mc;

import net.minecraft.item.ItemStack;

public class FluidActionResult {
    public static final FluidActionResult FAILURE = new FluidActionResult(false, null);
    public final boolean success;
    public final ItemStack result;

    public FluidActionResult(ItemStack result) { this(true, result); }

    private FluidActionResult(boolean success, ItemStack result) {
        this.success = success;
        this.result = result;
    }

    public boolean isSuccess() { return success; }

    public ItemStack getResult() { return result; }
}
