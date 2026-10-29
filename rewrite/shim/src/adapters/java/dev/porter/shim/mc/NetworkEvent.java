// porter:adapter (generic) - 1.19.2 NetworkEvent.Context on a 1.7.10 MessageContext.
package dev.porter.shim.mc;

import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import java.util.concurrent.CompletableFuture;
import net.minecraft.entity.player.EntityPlayerMP;

public class NetworkEvent {
    private NetworkEvent() {}

    public static class Context {
        private final MessageContext ctx;
        private boolean handled;

        public Context(MessageContext ctx) { this.ctx = ctx; }

        /** the 1.7.10 context. */
        public MessageContext raw() { return ctx; }

        public Side side() { return ctx.side; }

        /** the sending player on the server, null on the client. */
        public EntityPlayerMP getSender() { return ctx.side == Side.SERVER ? ctx.getServerHandler().playerEntity : null; }

        public NetworkDirection getDirection() {
            return ctx.side == Side.SERVER ? NetworkDirection.PLAY_TO_SERVER : NetworkDirection.PLAY_TO_CLIENT;
        }

        /** runs the task on the main thread of the receiving side (server thread, or client thread). */
        public CompletableFuture<Void> enqueueWork(Runnable task) { return NetMainThread.enqueue(ctx.side, task); }

        public void setPacketHandled(boolean b) { handled = b; }

        public boolean getPacketHandled() { return handled; }
    }
}
