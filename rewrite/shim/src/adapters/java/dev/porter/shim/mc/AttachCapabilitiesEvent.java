// porter:adapter (generic) - Forge 1.19.2 AttachCapabilitiesEvent<T> (LevelCaps posts it for worlds).
package dev.porter.shim.mc;

import cpw.mods.fml.common.eventhandler.Event;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.ResourceLocation;

public class AttachCapabilitiesEvent<T> extends Event {
    private final Class<T> type;
    private final T object;
    private final Map<String, ICapabilityProvider> caps = new LinkedHashMap<>();

    public AttachCapabilitiesEvent(Class<T> type, T object) {
        this.type = type;
        this.object = object;
    }

    public T getObject() { return object; }

    public Class<T> getGenericType() { return type; }

    public void addCapability(ResourceLocation key, ICapabilityProvider cap) {
        String k = key.toString();
        if (caps.containsKey(k)) throw new IllegalStateException("Duplicate Capability Key: " + key + " " + cap);
        caps.put(k, cap);
    }

    public Map<String, ICapabilityProvider> getCapabilities() { return Collections.unmodifiableMap(caps); }

    public void addListener(Runnable r) {}
}
