// porter:adapter (generic) - 1.19.2 EnumProperty.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public class EnumProperty<T extends Enum<T>> extends Property<T> {
    private final List<T> values;

    protected EnumProperty(String name, Class<T> clazz, Collection<T> values) {
        super(name, clazz);
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz) {
        return new EnumProperty<>(name, clazz, Arrays.asList(clazz.getEnumConstants()));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, Predicate<T> filter) {
        List<T> v = new ArrayList<>();
        for (T t : clazz.getEnumConstants()) if (filter.test(t)) v.add(t);
        return new EnumProperty<>(name, clazz, v);
    }

    @SafeVarargs
    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, T... values) {
        return new EnumProperty<>(name, clazz, Arrays.asList(values));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, Collection<T> values) {
        return new EnumProperty<>(name, clazz, values);
    }

    @Override public List<T> getPossibleValues() { return values; }

    @Override public String getName(T v) { return v.name().toLowerCase(java.util.Locale.ROOT); }
}
