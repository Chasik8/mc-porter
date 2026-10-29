// porter:adapter (generic) - id -> (class, encoder, decoder, handler) table of a 1.19.2 SimpleChannel. Dependency-free
// (the buffer type B and the context type C are parameters) so it is unit-tested without Minecraft.
package dev.porter.shim.mc.net;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class MessageTable<B, C> {
    private static final class Entry<B, C, M> {
        final int id;
        final Class<M> type;
        final BiConsumer<M, B> encoder;
        final Function<B, M> decoder;
        final BiConsumer<M, Supplier<C>> handler;

        Entry(int id, Class<M> type, BiConsumer<M, B> encoder, Function<B, M> decoder, BiConsumer<M, Supplier<C>> handler) {
            this.id = id;
            this.type = type;
            this.encoder = encoder;
            this.decoder = decoder;
            this.handler = handler;
        }
    }

    private final Map<Integer, Entry<B, C, Object>> byId = new HashMap<>();
    private final Map<Class<?>, Entry<B, C, Object>> byClass = new HashMap<>();

    /** 1.19.2 SimpleChannel.registerMessage(id, type, encoder, decoder, handler). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <M> void register(int id, Class<M> type, BiConsumer<M, B> encoder, Function<B, M> decoder,
            BiConsumer<M, Supplier<C>> handler) {
        if (byId.containsKey(id)) throw new IllegalArgumentException("message id " + id + " is already registered");
        if (byClass.containsKey(type)) throw new IllegalArgumentException("message class " + type.getName() + " is already registered");
        Entry e = new Entry(id, type, encoder, decoder, handler);
        byId.put(id, e);
        byClass.put(type, e);
    }

    public synchronized int size() { return byId.size(); }

    private synchronized Entry<B, C, Object> of(Object msg) {
        Entry<B, C, Object> e = byClass.get(msg.getClass());
        if (e != null) return e;
        // a subclass of a registered message class is sent as that class
        for (Entry<B, C, Object> c : byClass.values()) {
            if (c.type.isInstance(msg)) return c;
        }
        throw new IllegalArgumentException("unregistered message class " + msg.getClass().getName());
    }

    public int idOf(Object msg) { return of(msg).id; }

    public void encode(Object msg, B buf) { of(msg).encoder.accept(msg, buf); }

    private synchronized Entry<B, C, Object> entry(int id) {
        Entry<B, C, Object> e = byId.get(id);
        if (e == null) throw new IllegalArgumentException("unknown message id " + id);
        return e;
    }

    /** the decoded message; null when the decoder returned null (a decoder that logs and gives up). */
    public Object decode(int id, B buf) { return entry(id).decoder.apply(buf); }

    /** runs the handler registered for {@code id}. */
    public void dispatch(int id, Object msg, Supplier<C> ctx) { entry(id).handler.accept(msg, ctx); }

    /** decode + dispatch; false when the message decoded to null (nothing is dispatched). */
    public boolean receive(int id, B buf, Supplier<C> ctx) {
        Object msg = decode(id, buf);
        if (msg == null) return false;
        dispatch(id, msg, ctx);
        return true;
    }
}
