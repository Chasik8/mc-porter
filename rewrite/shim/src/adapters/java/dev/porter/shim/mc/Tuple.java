// porter:adapter (the mod-specific, PRIVATE) - generic pair for the 1.19.2 Tuple<A, B> (1.7.10 has no generic Tuple).
package dev.porter.shim.mc;

public class Tuple<A, B> {
    private final A a;
    private final B b;

    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public A getA() { return a; }

    public B getB() { return b; }

    public A getFirst() { return a; }

    public B getSecond() { return b; }
}
