package dev.porter.shim.gen.helpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Access to protected/private members of 1.7.10 classes (field and method names are tried in the given order). */
public final class DdReflect {
    private DdReflect() {
    }

    /** Value of the first existing field among names, searched up the class hierarchy; null when none exists. */
    public static Object field(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    // try the superclass
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** An int field (see field); def when it does not exist. */
    public static int intField(Object target, int def, String... names) {
        Object v = field(target, names);
        return v instanceof Integer ? ((Integer) v).intValue() : def;
    }

    /** Calls the method (any visibility, searched up the class hierarchy); null when it does not exist or fails. */
    public static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
