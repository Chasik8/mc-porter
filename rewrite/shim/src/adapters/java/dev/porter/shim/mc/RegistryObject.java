// porter:adapter (generic) - Forge 1.19.2 RegistryObject: the entry of a DeferredRegister, created once on first
// get() (the register creates it while registering in preInit).
package dev.porter.shim.mc;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.util.ResourceLocation;

public final class RegistryObject<T> implements Supplier<T> {
    private final ResourceLocation id;
    private Supplier<? extends T> supplier;
    private T value;

    RegistryObject(ResourceLocation id, Supplier<? extends T> supplier) {
        this.id = id;
        this.supplier = supplier;
    }

    public ResourceLocation getId() { return id; }

    @Override
    public synchronized T get() {
        if (supplier != null) {
            value = supplier.get();
            supplier = null;
        }
        return value;
    }

    public boolean isPresent() { return get() != null; }

    public Optional<T> getHolder() { return Optional.ofNullable(get()); }

    @Override
    public String toString() { return "RegistryObject[" + id + "]"; }
}
