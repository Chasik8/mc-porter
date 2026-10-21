// porter:adapter (generic) - 1.19.2 BooleanProperty.
package dev.porter.shim.mc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BooleanProperty extends Property<Boolean> {
    private static final List<Boolean> VALUES = Collections.unmodifiableList(Arrays.asList(Boolean.TRUE, Boolean.FALSE));

    private BooleanProperty(String name) { super(name, Boolean.class); }

    public static BooleanProperty create(String name) { return new BooleanProperty(name); }

    @Override public List<Boolean> getPossibleValues() { return VALUES; }

    @Override public String getName(Boolean v) { return v.toString(); }
}
