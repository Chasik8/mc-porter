package dev.porter.extract.forge1192.interp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.declaration.CtElement;

/**
 * Facts recorded while interpreting registration code: registry entries, network messages, screen bindings and
 * pack-specific events (custom recipes...). Concept extractors read these afterwards.
 */
public final class Facts {
    /**
     * One registry entry.
     *
     * @param id         namespaced id string, or null if unknown (then {@code idUnknown} says why)
     * @param conditions source snippets of enclosing undecidable conditions (both-branches mode), empty if none
     */
    public record Registration(String registry, String id, String idUnknown, Object value, CtElement site,
            List<String> conditions) {
    }

    /** SimpleChannel message registration. */
    public record Message(Object channel, Object index, Object messageClass, Object encoder, Object decoder,
            Object handler, Object direction, CtElement site, List<String> conditions) {
    }

    /** MenuScreens.register(menuType, screenFactory). */
    public record ScreenBinding(Object menuType, Object screenFactory, CtElement site) {
    }

    /**
     * A generic event recorded by a builtin or pattern pack (e.g. a mod-specific recipe call).
     *
     * @param root registration root that was running ("a.b.C#method"), null outside roots
     */
    public record Event(String kind, String name, List<Object> args, CtElement site, List<String> conditions,
            String root) {
    }

    public final List<Registration> registrations = new ArrayList<>();
    public final Map<Object, Registration> byValue = new IdentityHashMap<>();
    public final Map<String, Registration> byKey = new LinkedHashMap<>();
    public final List<Message> messages = new ArrayList<>();
    public final List<ScreenBinding> screens = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();

    public void register(Registration r) {
        if (r.id() != null) {
            Registration prev = byKey.get(r.registry() + "|" + r.id());
            if (prev != null && prev.site() == r.site()) {
                // the same registration code ran twice (demand-driven + root order): keep the first
                if (r.value() != null && !(r.value() instanceof V.Unknown)) {
                    byValue.putIfAbsent(r.value(), prev);
                }
                return;
            }
        }
        registrations.add(r);
        if (r.value() != null && !(r.value() instanceof V.Unknown)) {
            byValue.putIfAbsent(r.value(), r);
        }
        if (r.id() != null) {
            byKey.putIfAbsent(r.registry() + "|" + r.id(), r);
        }
    }

    /** Replaces the value of a registration (lazy supplier resolved). */
    public Registration replaceValue(Registration old, Object value) {
        Registration r = new Registration(old.registry(), old.id(), old.idUnknown(), value, old.site(), old.conditions());
        int i = registrations.indexOf(old);
        if (i >= 0) {
            registrations.set(i, r);
        }
        if (old.id() != null && byKey.get(old.registry() + "|" + old.id()) == old) {
            byKey.put(old.registry() + "|" + old.id(), r);
        }
        if (value != null && !(value instanceof V.Unknown)) {
            byValue.putIfAbsent(value, r);
        }
        return r;
    }

    /** Runs a lazy supplier (once) and returns the value. */
    public Object resolve(Interpreter in, Registration r) {
        if (!(r.value() instanceof V.Lazy lazy)) {
            return r.value();
        }
        // guard against suppliers that (indirectly) need themselves
        Registration guard = replaceValue(r, new V.Unknown("registry supplier of " + r.id() + " depends on itself"));
        Object v = in.callFn(lazy.supplier(), java.util.List.of());
        replaceValue(guard, v);
        return v;
    }

    /** Resolves every pending supplier, in registration order. */
    public void resolveAll(Interpreter in) {
        for (int i = 0; i < registrations.size(); i++) {
            Registration r = registrations.get(i);
            if (r.value() instanceof V.Lazy) {
                resolve(in, r);
            }
        }
    }

    /** Registration of a value (identity), or null. */
    public Registration of(Object value) {
        return value == null ? null : byValue.get(value);
    }

    public Registration lookup(String registry, String id) {
        return byKey.get(registry + "|" + id);
    }

    public List<Registration> in(String registry) {
        return registrations.stream().filter(r -> r.registry().equals(registry)).toList();
    }
}
