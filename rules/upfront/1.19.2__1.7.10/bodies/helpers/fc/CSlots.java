// porter:helper (generic) - per-instance state for generated mirror classes that cannot declare fields.
package dev.porter.shim.gen.helpers.fc;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Weakly keyed named slots: a generated body can keep state for its (field-less) receiver here. */
public final class CSlots {
    private static final Map<Object, Map<String, Object>> SLOTS = new WeakHashMap<Object, Map<String, Object>>();

    private CSlots() {}

    public static synchronized void put(Object owner, String slot, Object value) {
        Map<String, Object> m = SLOTS.get(owner);
        if (m == null) {
            m = new HashMap<String, Object>();
            SLOTS.put(owner, m);
        }
        m.put(slot, value);
    }

    public static synchronized Object get(Object owner, String slot) {
        Map<String, Object> m = SLOTS.get(owner);
        return m == null ? null : m.get(slot);
    }

    /** The map stored in a slot (created on first use). */
    @SuppressWarnings("unchecked")
    public static synchronized Map<Object, Object> map(Object owner, String slot) {
        Object v = get(owner, slot);
        if (!(v instanceof Map)) {
            v = new HashMap<Object, Object>();
            put(owner, slot, v);
        }
        return (Map<Object, Object>) v;
    }
}
