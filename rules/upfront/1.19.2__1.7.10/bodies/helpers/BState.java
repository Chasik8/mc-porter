package dev.porter.shim.gen.nbt;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Generic per-instance slots for generated mirror classes that cannot declare fields (a body replaces statements only).
 * Keyed by instance, weakly, so an unreferenced mirror object is collected with its slots.
 */
public final class BState {
    private static final Map<Object, Map<String, Object>> SLOTS = new WeakHashMap<Object, Map<String, Object>>();

    private BState() {}

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

    public static int getInt(Object owner, String slot, int def) {
        Object v = get(owner, slot);
        return v instanceof Integer ? ((Integer) v).intValue() : def;
    }
}
