// porter:adapter (generic) - Forge 1.19.2 CapabilityManager.get(new CapabilityToken<>(){}): one Capability key per
// token type (the anonymous token's generic superclass names the capability interface).
package dev.porter.shim.mc;

import java.util.HashMap;
import java.util.Map;

public final class CapabilityManager {
    private static final Map<String, Capability<?>> CAPS = new HashMap<>();

    private CapabilityManager() {}

    @SuppressWarnings("unchecked")
    public static synchronized <T> Capability<T> get(CapabilityToken<T> token) {
        String name = token.getType();
        Capability<?> c = CAPS.get(name);
        if (c == null) {
            c = new Capability<T>(name);
            CAPS.put(name, c);
        }
        return (Capability<T>) c;
    }
}
