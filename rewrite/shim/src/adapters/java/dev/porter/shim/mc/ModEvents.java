// porter:adapter (generic) - Forge 1.19.2 lifecycle events (mod bus) and server events (Forge bus) that 1.7.10
// delivers through @Mod.EventHandler methods instead. ModLifecycle posts them: lifecycle events on the mod bus,
// server events on MinecraftForge.EVENT_BUS (they extend the cpw Event so @SubscribeEvent instance methods work).
package dev.porter.shim.mc;

import cpw.mods.fml.common.eventhandler.Event;
import dev.porter.shim.mc.cmd.CommandDispatcher;
import dev.porter.shim.mc.cmd.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

public final class ModEvents {
    private ModEvents() {}

    /** base of the mod-bus lifecycle events; enqueueWork runs the work immediately (1.7.10 is single-threaded here) */
    public abstract static class Lifecycle {
        public void enqueueWork(Runnable r) { r.run(); }

        public <T> java.util.concurrent.CompletableFuture<T> enqueueWork(java.util.function.Supplier<T> s) {
            return java.util.concurrent.CompletableFuture.completedFuture(s.get());
        }
    }

    public static class FMLConstructModEvent extends Lifecycle {}

    public static class FMLCommonSetupEvent extends Lifecycle {}

    public static class FMLClientSetupEvent extends Lifecycle {}

    public static class FMLDedicatedServerSetupEvent extends Lifecycle {}

    public static class InterModEnqueueEvent extends Lifecycle {}

    public static class InterModProcessEvent extends Lifecycle {}

    public static class FMLLoadCompleteEvent extends Lifecycle {}

    /** Forge-bus server events */
    public abstract static class ServerLifecycleEvent extends Event {
        private final MinecraftServer server;

        protected ServerLifecycleEvent(MinecraftServer server) { this.server = server; }

        public MinecraftServer getServer() { return server; }
    }

    public static class ServerAboutToStartEvent extends ServerLifecycleEvent {
        public ServerAboutToStartEvent(MinecraftServer s) { super(s); }
    }

    public static class ServerStartingEvent extends ServerLifecycleEvent {
        public ServerStartingEvent(MinecraftServer s) { super(s); }
    }

    public static class ServerStartedEvent extends ServerLifecycleEvent {
        public ServerStartedEvent(MinecraftServer s) { super(s); }
    }

    public static class ServerStoppingEvent extends ServerLifecycleEvent {
        public ServerStoppingEvent(MinecraftServer s) { super(s); }
    }

    public static class ServerStoppedEvent extends ServerLifecycleEvent {
        public ServerStoppedEvent(MinecraftServer s) { super(s); }
    }

    public static class RegisterCommandsEvent extends Event {
        private final CommandDispatcher<CommandSourceStack> dispatcher;

        public RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> d) { this.dispatcher = d; }

        public CommandDispatcher<CommandSourceStack> getDispatcher() { return dispatcher; }
    }
}
