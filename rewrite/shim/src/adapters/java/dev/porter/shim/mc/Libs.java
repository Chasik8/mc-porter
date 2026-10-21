// porter:adapter (generic) - library members newer than the versions 1.7.10 ships (commons-lang3 3.3.2).
package dev.porter.shim.mc;

import org.apache.commons.lang3.mutable.MutableInt;

public final class Libs {
    private Libs() {}

    public static int decrementAndGet(MutableInt m) {
        m.decrement();
        return m.intValue();
    }

    public static int incrementAndGet(MutableInt m) {
        m.increment();
        return m.intValue();
    }
}
