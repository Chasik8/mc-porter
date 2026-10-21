package dev.porter.extract.forge1192.interp;

import java.util.HashMap;
import java.util.Map;

/** Lexical scope of local variables; lambdas capture the scope they were created in. */
public final class Scope {
    private final Scope parent;
    private final Map<String, Object> vars = new HashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public void declare(String name, Object value) {
        vars.put(name, value);
    }

    public boolean has(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    public Object get(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return s.vars.get(name);
            }
        }
        return null;
    }

    /** Assigns an existing variable (searching outwards), or declares it here. */
    public void set(String name, Object value) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                s.vars.put(name, value);
                return;
            }
        }
        vars.put(name, value);
    }
}
