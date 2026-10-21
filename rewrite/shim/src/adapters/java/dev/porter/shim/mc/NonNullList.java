// porter:adapter (generic) - 1.19.2 NonNullList: an ArrayList (1.7.10 empty item stacks are null, so nulls are
// not rejected).
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

public class NonNullList<E> extends ArrayList<E> {
    private final E defaultValue;

    protected NonNullList(Collection<E> c, E def) {
        super(c);
        this.defaultValue = def;
    }

    public static <E> NonNullList<E> create() { return new NonNullList<>(new ArrayList<E>(), null); }

    public static <E> NonNullList<E> createWithCapacity(int n) { return new NonNullList<>(new ArrayList<E>(n), null); }

    public static <E> NonNullList<E> withSize(int size, E def) {
        ArrayList<E> l = new ArrayList<>(size);
        for (int i = 0; i < size; i++) l.add(def);
        return new NonNullList<>(l, def);
    }

    @SafeVarargs
    public static <E> NonNullList<E> of(E def, E... values) { return new NonNullList<>(Arrays.asList(values), def); }

    @Override
    public void clear() {
        if (defaultValue == null) {
            super.clear();
        } else {
            for (int i = 0; i < size(); i++) set(i, defaultValue);
        }
    }
}
