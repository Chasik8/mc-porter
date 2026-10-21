// porter:adapter (generic) - Forge 1.19.2 FMLJavaModLoadingContext: the mod bus of the mod being constructed
// (ModLifecycle.construct sets it).
package dev.porter.shim.mc;

public final class FMLJavaModLoadingContext {
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    private FMLJavaModLoadingContext() {}

    public static FMLJavaModLoadingContext get() { return INSTANCE; }

    public IEventBus getModEventBus() {
        String m = ModLifecycle.current;
        if (m == null) throw new IllegalStateException("getModEventBus() outside of the mod constructor (ModLifecycle.construct)");
        return ModLifecycle.bus(m);
    }
}
