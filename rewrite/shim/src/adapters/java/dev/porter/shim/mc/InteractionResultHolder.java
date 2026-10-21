// porter:adapter (generic) - 1.19.2 InteractionResultHolder<T>.
package dev.porter.shim.mc;

public class InteractionResultHolder<T> {
    private final InteractionResult result;
    private final T object;

    public InteractionResultHolder(InteractionResult result, T object) {
        this.result = result;
        this.object = object;
    }

    public InteractionResult getResult() { return result; }

    public T getObject() { return object; }

    public static <T> InteractionResultHolder<T> success(T o) { return new InteractionResultHolder<>(InteractionResult.SUCCESS, o); }

    public static <T> InteractionResultHolder<T> consume(T o) { return new InteractionResultHolder<>(InteractionResult.CONSUME, o); }

    public static <T> InteractionResultHolder<T> pass(T o) { return new InteractionResultHolder<>(InteractionResult.PASS, o); }

    public static <T> InteractionResultHolder<T> fail(T o) { return new InteractionResultHolder<>(InteractionResult.FAIL, o); }

    public static <T> InteractionResultHolder<T> sidedSuccess(T o, boolean client) { return client ? success(o) : consume(o); }
}
