// porter:adapter (generic) - 1.19.2 NonNullList: an ArrayList (1.7.10 empty item stacks are null, so nulls are
// not rejected).
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

public class NonNullList<E> extends ArrayList<E> {
    private final E defaultValue;
    /** withSize / of lists have a fixed size: clear() resets the slots (to null on 1.7.10, where EMPTY is null) */
    private boolean fixedSize;

    protected NonNullList(Collection<E> c, E def) {
        super(c);
        this.defaultValue = def;
    }

    public static <E> NonNullList<E> create() { return new NonNullList<>(new ArrayList<E>(), null); }

    public static <E> NonNullList<E> createWithCapacity(int n) { return new NonNullList<>(new ArrayList<E>(n), null); }

    public static <E> NonNullList<E> withSize(int size, E def) {
        ArrayList<E> l = new ArrayList<>(size);
        for (int i = 0; i < size; i++) l.add(def);
        NonNullList<E> n = new NonNullList<>(l, def);
        n.fixedSize = true;
        return n;
    }

    @SafeVarargs
    public static <E> NonNullList<E> of(E def, E... values) { return new NonNullList<>(Arrays.asList(values), def); }

    @Override
    public void clear() {
        if (defaultValue == null && !fixedSize) {
            super.clear();
        } else {
            for (int i = 0; i < size(); i++) set(i, defaultValue);
        }
    }
}
