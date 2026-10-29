// porter:helper (generic) - the 1.7.10 Forge event bus seen through the 1.19.2 IEventBus shape.
package dev.porter.shim.gen.helpers.fa;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import dev.porter.shim.mc.IEventBus;
import java.util.function.Consumer;

public final class ForgeBus implements IEventBus {
    private final EventBus bus;

    private ForgeBus(EventBus bus) {
        this.bus = bus;
    }

    private static final java.util.Map<EventBus, ForgeBus> CACHE =
            new java.util.IdentityHashMap<EventBus, ForgeBus>();

    public static synchronized IEventBus of(EventBus bus) {
        ForgeBus b = CACHE.get(bus);
        if (b == null) {
            b = new ForgeBus(bus);
            CACHE.put(bus, b);
        }
        return b;
    }

    @Override
    public <T> void addListener(Consumer<T> listener) {
        throw new UnsupportedOperationException("the 1.7.10 bus registers annotated handlers (register), not typed lambdas");
    }

    @Override
    public <T> void addListener(EventPriority priority, Consumer<T> listener) {
        addListener(listener);
    }

    @Override
    public <T> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> listener) {
        addListener(listener);
    }

    @Override
    public void register(Object target) {
        bus.register(target);
    }

    @Override
    public boolean post(Object event) {
        return event instanceof Event && bus.post((Event) event);
    }
}
