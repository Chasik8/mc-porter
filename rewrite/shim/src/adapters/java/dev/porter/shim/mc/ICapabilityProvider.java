// porter:adapter (generic) - Forge 1.19.2 ICapabilityProvider.
package dev.porter.shim.mc;

import dev.porter.shim.Direction;

public interface ICapabilityProvider {
    <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);

    default <T> LazyOptional<T> getCapability(Capability<T> cap) { return getCapability(cap, null); }
}
