package dev.porter.shim.gen.eventbus.api;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.porter.shim.mc.IEventBus;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** A private event bus (a bus a mod creates for its own events): listeners by lambda or by annotated methods. */
public final class BEventBus implements IEventBus {
    private static final class Listener {
        final EventPriority priority;
        final Consumer<Object> consumer;
        final Class<?> type;
        final Object owner;

        Listener(EventPriority priority, Consumer<Object> consumer, Class<?> type, Object owner) {
            this.priority = priority;
            this.consumer = consumer;
            this.type = type;
            this.owner = owner;
        }
    }

    private final List<Listener> listeners = new ArrayList<Listener>();

    @Override
    public <T> void addListener(Consumer<T> listener) {
        addListener(EventPriority.NORMAL, listener);
    }

    @Override
    public <T> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> listener) {
        addListener(priority, listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> void addListener(EventPriority priority, Consumer<T> listener) {
        listeners.add(new Listener(priority, (Consumer<Object>) listener, null, listener));
    }

    @Override
    public synchronized void register(Object target) {
        final Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        final Object inst = target instanceof Class ? null : target;
        for (final Method m : c.getDeclaredMethods()) {
            SubscribeEvent sub = m.getAnnotation(SubscribeEvent.class);
            if (sub == null || m.getParameterTypes().length != 1 || (inst == null) != Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            m.setAccessible(true);
            listeners.add(new Listener(sub.priority(), new Consumer<Object>() {
                @Override
                public void accept(Object ev) {
                    try {
                        m.invoke(inst, ev);
                    } catch (Exception x) {
                        throw new RuntimeException(x);
                    }
                }
            }, m.getParameterTypes()[0], target));
        }
    }

    public synchronized void unregister(Object target) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            if (listeners.get(i).owner == target) {
                listeners.remove(i);
            }
        }
    }

    /** Delivers the event to every listener that takes it; returns whether a listener cancelled it. */
    @Override
    public boolean post(Object event) {
        List<Listener> sorted;
        synchronized (this) {
            sorted = new ArrayList<Listener>(listeners);
        }
        sorted.sort(Comparator.comparingInt(new java.util.function.ToIntFunction<Listener>() {
            @Override
            public int applyAsInt(Listener l) {
                return l.priority.ordinal();
            }
        }));
        for (Listener l : sorted) {
            if (l.type != null) {
                if (l.type.isInstance(event)) {
                    l.consumer.accept(event);
                }
                continue;
            }
            try {
                l.consumer.accept(event);
            } catch (ClassCastException notMine) {
                // a lambda without a runtime event type rejects an event of another type with its own cast
            }
        }
        return event instanceof Event && ((Event) event).isCancelable() && ((Event) event).isCanceled();
    }
}
