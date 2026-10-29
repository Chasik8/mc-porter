package dev.porter.shim.gen.helpers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Per-instance state for generated shim classes that have no fields of their own (builders, value holders).
 * Entries die with their owner.
 */
public final class DdState {
    private static final Map<Object, Map<String, Object>> STATE =
            Collections.synchronizedMap(new WeakHashMap<Object, Map<String, Object>>());

    private DdState() {
    }

    private static Map<String, Object> of(Object owner) {
        synchronized (STATE) {
            Map<String, Object> m = STATE.get(owner);
            if (m == null) {
                m = new HashMap<String, Object>();
                STATE.put(owner, m);
            }
            return m;
        }
    }

    public static void put(Object owner, String key, Object value) {
        Map<String, Object> m = of(owner);
        synchronized (m) {
            m.put(key, value);
        }
    }

    public static Object get(Object owner, String key) {
        Map<String, Object> m = of(owner);
        synchronized (m) {
            return m.get(key);
        }
    }

    /** The value stored under key, created from the supplier on first use. */
    @SuppressWarnings("unchecked")
    public static <T> T lazy(Object owner, String key, Supplier<T> create) {
        Map<String, Object> m = of(owner);
        synchronized (m) {
            Object v = m.get(key);
            if (v == null) {
                v = create.get();
                m.put(key, v);
            }
            return (T) v;
        }
    }

    /** A java.util.function.Supplier over any object that has a public no-argument get() (a Forge NonNullSupplier). */
    public static Supplier<Object> supplier(final Object source) {
        if (source instanceof Supplier) {
            @SuppressWarnings("unchecked")
            Supplier<Object> s = (Supplier<Object>) source;
            return s;
        }
        return new Supplier<Object>() {
            @Override
            public Object get() {
                return DdReflect.invoke(source, "get", new Class<?>[0]);
            }
        };
    }
}
