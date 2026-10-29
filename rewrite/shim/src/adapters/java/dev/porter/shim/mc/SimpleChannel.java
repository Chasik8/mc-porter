// porter:adapter (generic) - 1.19.2 SimpleChannel on ONE 1.7.10 SimpleNetworkWrapper. Every 1.19.2 message travels as
// a NetBridgeMessage (id + encoded bytes); the channel's MessageTable decodes it and dispatches by id.
package dev.porter.shim.mc;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import dev.porter.shim.mc.net.MessageTable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.PacketBuffer;

public final class SimpleChannel {
    final SimpleNetworkWrapper wrapper;
    final MessageTable<PacketBuffer, NetworkEvent.Context> table = new MessageTable<>();

    SimpleChannel(String name) {
        NetMainThread.ensureRegistered();
        wrapper = new SimpleNetworkWrapper(name);
        Handler h = new Handler();
        wrapper.registerMessage(h, NetBridgeMessage.class, 0, Side.SERVER);
        wrapper.registerMessage(h, NetBridgeMessage.class, 0, Side.CLIENT);
    }

    /** receives on either side: decode by id, then the 1.19.2 handler (which usually calls enqueueWork). */
    private final class Handler implements IMessageHandler<NetBridgeMessage, IMessage> {
        @Override
        public IMessage onMessage(NetBridgeMessage m, MessageContext ctx) {
            try {
                NetworkEvent.Context c = new NetworkEvent.Context(ctx);
                table.receive(m.id, new PacketBuffer(Unpooled.wrappedBuffer(m.data)), () -> c);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return null;
        }
    }

    /** 1.19.2 registerMessage(id, type, encoder, decoder, handler). */
    public <M> void registerMessage(int id, Class<M> type, BiConsumer<M, PacketBuffer> encoder, Function<PacketBuffer, M> decoder,
            BiConsumer<M, Supplier<NetworkEvent.Context>> handler) {
        table.register(id, type, encoder, decoder, handler);
    }

    /** 1.19.2 registerMessage(..., Optional&lt;NetworkDirection&gt;): the direction is not enforced. */
    public <M> void registerMessage(int id, Class<M> type, BiConsumer<M, PacketBuffer> encoder, Function<PacketBuffer, M> decoder,
            BiConsumer<M, Supplier<NetworkEvent.Context>> handler, Optional<NetworkDirection> direction) {
        table.register(id, type, encoder, decoder, handler);
    }

    NetBridgeMessage wrap(Object msg) {
        int id = table.idOf(msg);
        ByteBuf out = Unpooled.buffer();
        table.encode(msg, new PacketBuffer(out));
        byte[] data = new byte[out.readableBytes()];
        out.readBytes(data);
        return new NetBridgeMessage(id, data);
    }

    public void sendToServer(Object msg) { wrapper.sendToServer(wrap(msg)); }

    public void send(PacketDistributor.PacketTarget target, Object msg) { target.send(wrapper, wrap(msg)); }

    /** answers the sender of the message being handled (the player on the server, the server on the client). */
    public void reply(Object msg, NetworkEvent.Context ctx) {
        if (ctx.getSender() != null) wrapper.sendTo(wrap(msg), ctx.getSender());
        else wrapper.sendToServer(wrap(msg));
    }
}
