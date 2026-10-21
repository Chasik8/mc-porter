// porter:adapter (generic) - Forge 1.19.2 ForgeCapabilities keys.
package dev.porter.shim.mc;

public final class ForgeCapabilities {
    public static final Capability<IItemHandler> ITEM_HANDLER = new Capability<>("item_handler");
    public static final Capability<IFluidHandler> FLUID_HANDLER = new Capability<>("fluid_handler");
    public static final Capability<IFluidHandlerItem> FLUID_HANDLER_ITEM = new Capability<>("fluid_handler_item");
    public static final Capability<Object> ENERGY = new Capability<>("energy");

    private ForgeCapabilities() {}
}
