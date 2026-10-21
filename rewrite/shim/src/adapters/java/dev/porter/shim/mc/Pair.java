// porter:adapter (generic) - com.mojang.datafixers.util.Pair (not shipped with 1.7.10).
package dev.porter.shim.mc;

import java.util.Objects;

public final class Pair<F, S> {
    private final F first;
    private final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <F, S> Pair<F, S> of(F first, S second) { return new Pair<>(first, second); }

    public F getFirst() { return first; }

    public S getSecond() { return second; }

    public Pair<S, F> swap() { return of(second, first); }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pair && Objects.equals(first, ((Pair<?, ?>) o).first) && Objects.equals(second, ((Pair<?, ?>) o).second);
    }

    @Override
    public int hashCode() { return Objects.hash(first, second); }

    @Override
    public String toString() { return "(" + first + ", " + second + ")"; }
}
