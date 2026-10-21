// porter:adapter (generic) - Forge 1.19.2 IEventBus as the MOD event bus of a ported mod (lifecycle events only).
// The Forge bus itself is the 1.7.10 MinecraftForge.EVENT_BUS (cpw EventBus), which keeps its own type.
package dev.porter.shim.mc;

import cpw.mods.fml.common.eventhandler.EventPriority;
import java.util.function.Consumer;

public interface IEventBus {
    <T> void addListener(Consumer<T> listener);

    <T> void addListener(EventPriority priority, Consumer<T> listener);

    <T> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> listener);

    /** an object or class with @SubscribeEvent methods for lifecycle events */
    void register(Object target);

    boolean post(Object event);
}
