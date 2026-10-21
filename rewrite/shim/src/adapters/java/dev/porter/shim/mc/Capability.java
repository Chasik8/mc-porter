// porter:adapter (generic) - Forge 1.19.2 capability key. 1.7.10 has no capabilities: the keys only live inside
// ported code; Caps bridges them to the 1.7.10 interfaces (IFluidHandler, IInventory, fluid containers).
package dev.porter.shim.mc;

public final class Capability<T> {
    private final String name;

    public Capability(String name) { this.name = name; }

    public String getName() { return name; }

    public boolean isRegistered() { return true; }

    /** 1.19.2 Capability.orEmpty(toCheck, inst): inst cast when the capability matches, empty otherwise. */
    @SuppressWarnings("unchecked")
    public <R> LazyOptional<R> orEmpty(Capability<R> toCheck, LazyOptional<T> inst) {
        return this == toCheck ? (LazyOptional<R>) inst : LazyOptional.<R>empty();
    }

    @Override
    public String toString() { return "Capability[" + name + "]"; }
}
