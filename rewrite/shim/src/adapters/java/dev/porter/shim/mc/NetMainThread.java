// porter:adapter (generic) - 1.19.2 Context.enqueueWork on 1.7.10: tasks queued by the network thread run on the
// server thread (end of a server tick) or the client thread (end of a client tick), per receiving side.
package dev.porter.shim.mc;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class NetMainThread {
    private static final ConcurrentLinkedQueue<Runnable> SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> CLIENT = new ConcurrentLinkedQueue<>();
    private static boolean registered;

    private NetMainThread() {}

    /** registers the tick drain once (called when the first channel is created). */
    static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance().bus().register(new NetMainThread());
    }

    static CompletableFuture<Void> enqueue(Side side, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        (side == Side.SERVER ? SERVER : CLIENT).add(() -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                t.printStackTrace();
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    private static void drain(ConcurrentLinkedQueue<Runnable> q) {
        Runnable r;
        while ((r = q.poll()) != null) r.run();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) drain(SERVER);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) drain(CLIENT);
    }
}
