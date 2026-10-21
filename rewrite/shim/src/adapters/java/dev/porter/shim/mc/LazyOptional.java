// porter:adapter (generic) - Forge 1.19.2 LazyOptional.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class LazyOptional<T> {
    private static final LazyOptional<Void> EMPTY = new LazyOptional<>(null);
    private Supplier<? extends T> supplier;
    private T resolved;
    private boolean isValid = true;
    private final List<Consumer<LazyOptional<T>>> listeners = new ArrayList<>();

    private LazyOptional(Supplier<? extends T> s) {
        this.supplier = s;
        if (s == null) isValid = false;
    }

    public static <T> LazyOptional<T> of(Supplier<? extends T> s) { return s == null ? LazyOptional.<T>empty() : new LazyOptional<T>(s); }

    @SuppressWarnings("unchecked")
    public static <T> LazyOptional<T> empty() { return (LazyOptional<T>) EMPTY; }

    @SuppressWarnings("unchecked")
    public <X> LazyOptional<X> cast() { return (LazyOptional<X>) this; }

    private T get() {
        if (!isValid) return null;
        if (resolved == null && supplier != null) resolved = supplier.get();
        return resolved;
    }

    public boolean isPresent() { return isValid && get() != null; }

    public void ifPresent(Consumer<? super T> c) {
        T v = get();
        if (isValid && v != null) c.accept(v);
    }

    public <U> LazyOptional<U> lazyMap(Function<? super T, ? extends U> f) {
        return isPresent() ? LazyOptional.<U>of(() -> f.apply(get())) : LazyOptional.<U>empty();
    }

    public <U> Optional<U> map(Function<? super T, ? extends U> f) {
        return isPresent() ? Optional.ofNullable(f.apply(get())) : Optional.<U>empty();
    }

    public Optional<T> filter(Predicate<? super T> p) {
        T v = get();
        return isPresent() && p.test(v) ? Optional.of(v) : Optional.<T>empty();
    }

    public Optional<T> resolve() { return isPresent() ? Optional.of(get()) : Optional.<T>empty(); }

    public T orElse(T other) {
        T v = get();
        return v != null ? v : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T v = get();
        return v != null ? v : other == null ? null : other.get();
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> e) throws X {
        T v = get();
        if (v != null) return v;
        throw e.get();
    }

    public T orElseThrow() {
        T v = get();
        if (v != null) return v;
        throw new java.util.NoSuchElementException("No value present");
    }

    public void addListener(Consumer<LazyOptional<T>> l) {
        if (isPresent()) listeners.add(l);
        else l.accept(this);
    }

    public void invalidate() {
        if (this.isValid && this != EMPTY) {
            this.isValid = false;
            for (Consumer<LazyOptional<T>> l : new ArrayList<>(listeners)) l.accept(this);
            listeners.clear();
        }
    }
}
