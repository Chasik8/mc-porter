package dev.porter.extract.forge1192.interp;

import java.util.List;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;

/**
 * Java implementations of non-source (vanilla, Forge, JDK) API for the {@link Interpreter}. Providers are asked in
 * order; each returns {@link #NOT_HANDLED} for what it does not model. Names are official (Mojang) names: SRG names
 * of the source are translated before the call.
 */
public interface Builtins {
    Object NOT_HANDLED = new Object() {
        @Override
        public String toString() {
            return "NOT_HANDLED";
        }
    };

    /**
     * @param owner    static owner type of the call (qualified, binary nested names), may be null
     * @param name     official method name
     * @param target   receiver value, null for static calls
     * @param args     evaluated arguments
     * @param site     the call site (for provenance), may be null for calls made by builtins
     */
    record Call(String owner, String name, Object target, List<Object> args, CtInvocation<?> site) {
        public Object arg(int i) {
            return i < args.size() ? args.get(i) : null;
        }

        public int argc() {
            return args.size();
        }

        public boolean is(String ownerSuffix, String method) {
            return name.equals(method) && owner != null && (owner.equals(ownerSuffix) || owner.endsWith("." + ownerSuffix)
                    || owner.endsWith("$" + ownerSuffix));
        }
    }

    default Object invoke(Interpreter in, Call call) {
        return NOT_HANDLED;
    }

    /** {@code new type(args)} for a non-source type. */
    default Object construct(Interpreter in, String type, List<Object> args, CtElement site) {
        return NOT_HANDLED;
    }

    /** Static field of a non-source type (official field name). */
    default Object getStatic(Interpreter in, String owner, String field) {
        return NOT_HANDLED;
    }

    /** True if a call to owner#name (official) records facts; such call sites are tracked for coverage. */
    default boolean isIntercept(String owner, String name) {
        return false;
    }

    /**
     * True if calls to this <em>source</em> method must go to {@link #invoke} instead of being interpreted (packs
     * that record a mod helper's arguments, e.g. a tag helper, rather than executing its body).
     */
    default boolean preempts(String owner, String name) {
        return false;
    }

    /** Simple owner match helper: exact qualified name or simple-name suffix. */
    static boolean ownerIs(String owner, String qualified) {
        if (owner == null) {
            return false;
        }
        if (owner.equals(qualified)) {
            return true;
        }
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        return owner.equals(simple) || owner.endsWith("." + simple);
    }
}
