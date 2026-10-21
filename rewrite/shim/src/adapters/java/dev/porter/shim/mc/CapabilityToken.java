// porter:adapter (generic) - Forge 1.19.2 CapabilityToken<T> (always used as an anonymous subclass).
package dev.porter.shim.mc;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class CapabilityToken<T> {
    protected CapabilityToken() {}

    public String getType() {
        Type t = getClass().getGenericSuperclass();
        if (t instanceof ParameterizedType) {
            Type a = ((ParameterizedType) t).getActualTypeArguments()[0];
            return a instanceof Class ? ((Class<?>) a).getName() : a.toString();
        }
        return getClass().getName();
    }
}
