// porter:adapter (generic) - Forge 1.19.2 RegisterCapabilitiesEvent (registration is implicit on 1.7.10).
package dev.porter.shim.mc;

public class RegisterCapabilitiesEvent extends ModEvents.Lifecycle {
    public <T> void register(Class<T> type) {}
}
