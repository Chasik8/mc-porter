// porter:adapter (generic, no mod content) - 1.19.2 block-state Property on 1.7.10.
package dev.porter.shim.mc;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public abstract class Property<T extends Comparable<T>> {
    private final String name;
    private final Class<T> clazz;

    protected Property(String name, Class<T> clazz) {
        this.name = name;
        this.clazz = clazz;
    }

    public String getName() { return name; }

    public Class<T> getValueClass() { return clazz; }

    public abstract List<T> getPossibleValues();

    public abstract String getName(T value);

    public Optional<T> getValue(String s) {
        for (T v : getPossibleValues()) if (getName(v).equals(s)) return Optional.of(v);
        return Optional.empty();
    }

    /** index of a value in getPossibleValues() (meta encoding). */
    public int indexOf(Object v) { return getPossibleValues().indexOf(v); }

    public int count() { return getPossibleValues().size(); }

    @Override
    public String toString() { return getClass().getSimpleName() + "{name=" + name + ", values=" + getPossibleValues() + "}"; }

    public Collection<T> values() { return getPossibleValues(); }
}
