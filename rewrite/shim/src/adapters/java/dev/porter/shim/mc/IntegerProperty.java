// porter:adapter (generic) - 1.19.2 IntegerProperty.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IntegerProperty extends Property<Integer> {
    private final List<Integer> values;
    public final int min, max;

    private IntegerProperty(String name, int min, int max) {
        super(name, Integer.class);
        this.min = min;
        this.max = max;
        List<Integer> v = new ArrayList<>();
        for (int i = min; i <= max; i++) v.add(i);
        values = Collections.unmodifiableList(v);
    }

    public static IntegerProperty create(String name, int min, int max) { return new IntegerProperty(name, min, max); }

    @Override public List<Integer> getPossibleValues() { return values; }

    @Override public String getName(Integer v) { return v.toString(); }
}
