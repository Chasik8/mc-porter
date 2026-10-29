// porter:helper (generic) - values attached to generated shim objects that have no fields of their own.
package dev.porter.shim.gen.helpers.fa;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Weak, identity-like side store: lets a generated shell class (no fields) carry state for its member bodies. */
public final class GenAttach {
    private static final Map<Object, Map<String, Object>> STORE =
            Collections.synchronizedMap(new WeakHashMap<Object, Map<String, Object>>());

    private GenAttach() {}

    public static void put(Object owner, String key, Object value) {
        if (owner == null) {
            return;
        }
        synchronized (STORE) {
            Map<String, Object> m = STORE.get(owner);
            if (m == null) {
                m = new HashMap<String, Object>();
                STORE.put(owner, m);
            }
            m.put(key, value);
        }
    }

    public static Object get(Object owner, String key) {
        if (owner == null) {
            return null;
        }
        synchronized (STORE) {
            Map<String, Object> m = STORE.get(owner);
            return m == null ? null : m.get(key);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object owner, String key, T def) {
        Object v = get(owner, key);
        return v == null ? def : (T) v;
    }
}
