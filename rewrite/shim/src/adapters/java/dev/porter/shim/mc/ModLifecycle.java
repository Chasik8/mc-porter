// porter:adapter (generic) - runs a ported Forge 1.19.2 mod inside the 1.7.10 FML lifecycle. The 1.7.10 @Mod class
// (wiring) calls construct() in preInit (the 1.19.2 mod constructor: registers mod-bus listeners, deferred
// registers, configs), init()/postInit() (FMLCommonSetupEvent, client/server setup, IMC, FMLLoadCompleteEvent) and
// the server hooks (Forge-bus ServerStarting/Started/Stopped events, RegisterCommandsEvent -> 1.7.10 commands).
// Static @SubscribeEvent methods of @Mod.EventBusSubscriber classes are registered on the matching bus (1.7.10's
// EventBus only takes instances): Forge bus, FML bus (cpw gameevent events: ticks, player events), or mod bus.
package dev.porter.shim.mc;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.discovery.ASMDataTable;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.porter.shim.mc.cmd.CommandDispatcher;
import dev.porter.shim.mc.cmd.CommandSourceStack;
import dev.porter.shim.mc.cmd.PortedCommand;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ModLifecycle {
    static final Logger LOG = LogManager.getLogger("porter-lifecycle");
    private static final Map<String, ModEventBus> BUSES = new LinkedHashMap<>();
    static String current;
    static File configDir = new File("config");

    private ModLifecycle() {}

    /** the mod bus of a mod id (FMLJavaModLoadingContext.get().getModEventBus() while that mod is constructed) */
    public static synchronized ModEventBus bus(String modid) {
        ModEventBus b = BUSES.get(modid);
        if (b == null) {
            b = new ModEventBus(modid);
            BUSES.put(modid, b);
        }
        return b;
    }

    /** preInit: register static subscribers, then run the 1.19.2 mod constructor (deferred registers register now). */
    public static <T> T construct(String modid, FMLPreInitializationEvent e, Supplier<T> constructor) {
        current = modid;
        configDir = e.getModConfigurationDirectory();
        ForgeEvents.init();
        try {
            registerSubscribers(modid, e.getAsmData());
            T mod = constructor.get();
            bus(modid).post(new ModEvents.FMLConstructModEvent());
            return mod;
        } finally {
            current = null;
        }
    }

    public static void init(String modid, FMLInitializationEvent e) {
        ModEventBus b = bus(modid);
        b.post(new ModEvents.FMLCommonSetupEvent());
        if (e.getSide().isClient()) b.post(new ModEvents.FMLClientSetupEvent());
        else b.post(new ModEvents.FMLDedicatedServerSetupEvent());
        b.post(new ModEvents.InterModEnqueueEvent());
    }

    public static void postInit(String modid, FMLPostInitializationEvent e) {
        ModEventBus b = bus(modid);
        b.post(new ModEvents.InterModProcessEvent());
        b.post(new ModEvents.FMLLoadCompleteEvent());
    }

    public static void serverStarting(FMLServerStartingEvent e) {
        MinecraftServer s = e.getServer();
        MinecraftForge.EVENT_BUS.post(new ModEvents.ServerAboutToStartEvent(s));
        MinecraftForge.EVENT_BUS.post(new ModEvents.ServerStartingEvent(s));
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        MinecraftForge.EVENT_BUS.post(new ModEvents.RegisterCommandsEvent(d));
        for (PortedCommand c : PortedCommand.of(d)) e.registerServerCommand(c);
    }

    public static void serverStarted(FMLServerStartedEvent e) {
        MinecraftForge.EVENT_BUS.post(new ModEvents.ServerStartedEvent(MinecraftServer.getServer()));
    }

    public static void serverStopping(FMLServerStoppingEvent e) {
        MinecraftForge.EVENT_BUS.post(new ModEvents.ServerStoppingEvent(MinecraftServer.getServer()));
    }

    public static void serverStopped(FMLServerStoppedEvent e) {
        MinecraftForge.EVENT_BUS.post(new ModEvents.ServerStoppedEvent(MinecraftServer.getServer()));
    }

    // ------------------------------------------------------------------ @Mod.EventBusSubscriber

    static void registerSubscribers(String modid, ASMDataTable asm) {
        if (asm == null) return;
        for (ASMDataTable.ASMData d : asm.getAll(Mod.EventBusSubscriber.class.getName())) {
            Class<?> c;
            try {
                c = Class.forName(d.getClassName(), true, ModLifecycle.class.getClassLoader());
            } catch (Throwable t) {
                LOG.warn("EventBusSubscriber {} not loadable: {}", d.getClassName(), t.toString());
                continue;
            }
            Mod.EventBusSubscriber a = c.getAnnotation(Mod.EventBusSubscriber.class);
            if (a == null || !a.modid().isEmpty() && !a.modid().equals(modid)) continue;
            if (!sideMatches(a.value())) continue;
            if (a.bus() == Mod.EventBusSubscriber.Bus.MOD) bus(modid).register(c);
            else registerStatic(c);
        }
    }

    static boolean sideMatches(cpw.mods.fml.relauncher.Side[] sides) {
        cpw.mods.fml.relauncher.Side s = FMLCommonHandler.instance().getSide();
        for (cpw.mods.fml.relauncher.Side x : sides) if (x == s) return true;
        return false;
    }

    /** static @SubscribeEvent methods on the Forge bus or (cpw gameevent events) on the FML bus */
    public static void registerStatic(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            SubscribeEvent sub = m.getAnnotation(SubscribeEvent.class);
            if (sub == null || !Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 1) continue;
            Class<?> ev = m.getParameterTypes()[0];
            if (!Event.class.isAssignableFrom(ev)) {
                LOG.warn("{}.{}: {} is not a 1.7.10 event, not registered", c.getName(), m.getName(), ev.getName());
                continue;
            }
            EventBus bus = ev.getName().startsWith("cpw.mods.fml.common.gameevent.") ? FMLCommonHandler.instance().bus() : MinecraftForge.EVENT_BUS;
            register(bus, ev, m, sub);
        }
    }

    private static void register(EventBus bus, Class<?> eventType, final Method m, final SubscribeEvent sub) {
        try {
            Field id = EventBus.class.getDeclaredField("busID");
            id.setAccessible(true);
            int busID = id.getInt(bus);
            java.lang.reflect.Constructor<?> ctr = eventType.getConstructor();
            ctr.setAccessible(true);
            Event event = (Event) ctr.newInstance();
            m.setAccessible(true);
            event.getListenerList().register(busID, sub.priority(), new IEventListener() {
                @Override
                public void invoke(Event e) {
                    if (!sub.receiveCanceled() && e.isCancelable() && e.isCanceled()) return;
                    if (!m.getParameterTypes()[0].isInstance(e)) return;
                    try {
                        m.invoke(null, e);
                    } catch (java.lang.reflect.InvocationTargetException x) {
                        Throwable t = x.getCause();
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        if (t instanceof Error) throw (Error) t;
                        throw new RuntimeException(t);
                    } catch (IllegalAccessException x) {
                        throw new RuntimeException(x);
                    }
                }
            });
        } catch (Exception x) {
            LOG.error("cannot register static subscriber {}.{}: {}", m.getDeclaringClass().getName(), m.getName(), x.toString());
        }
    }

    // ------------------------------------------------------------------ the mod bus

    public static final class ModEventBus implements IEventBus {
        private final String modid;
        private final List<Listener> listeners = new ArrayList<>();

        ModEventBus(String modid) { this.modid = modid; }

        public String modid() { return modid; }

        private static final class Listener {
            final EventPriority priority;
            final Consumer<Object> consumer;
            final Class<?> type; // null: unknown (lambda), dispatched by trying

            Listener(EventPriority p, Consumer<Object> c, Class<?> t) { priority = p; consumer = c; type = t; }
        }

        @Override
        public <T> void addListener(Consumer<T> l) { addListener(EventPriority.NORMAL, l); }

        @Override
        public <T> void addListener(EventPriority p, boolean receiveCancelled, Consumer<T> l) { addListener(p, l); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void addListener(EventPriority p, Consumer<T> l) {
            listeners.add(new Listener(p, (Consumer<Object>) l, null));
        }

        @Override
        public void register(Object target) {
            Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
            final Object inst = target instanceof Class ? null : target;
            for (final Method m : c.getDeclaredMethods()) {
                SubscribeEvent sub = m.getAnnotation(SubscribeEvent.class);
                if (sub == null || m.getParameterTypes().length != 1 || (inst == null) != Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                listeners.add(new Listener(sub.priority(), ev -> {
                    try {
                        m.invoke(inst, ev);
                    } catch (Exception x) {
                        throw new RuntimeException(x);
                    }
                }, m.getParameterTypes()[0]));
            }
        }

        /**
         * Delivers a lifecycle event to every listener that takes it. Listeners added as lambdas / method references
         * have no runtime event type: they are called and a ClassCastException thrown by the lambda's own cast
         * (the synthetic lambda frame, hidden on Java 8, sits right on this method) means "not my event".
         */
        @Override
        public boolean post(Object event) {
            List<Listener> sorted = new ArrayList<>(listeners);
            sorted.sort(Comparator.comparingInt(l -> l.priority.ordinal()));
            for (Listener l : sorted) {
                if (l.type != null) {
                    if (l.type.isInstance(event)) l.consumer.accept(event);
                    continue;
                }
                try {
                    l.consumer.accept(event);
                } catch (ClassCastException x) {
                    // the cast of the lambda's own parameter: thrown by the (hidden) lambda frame, so the top visible
                    // frame is this method; a ClassCastException from the listener body has the body's frames on top
                    StackTraceElement[] st = x.getStackTrace();
                    boolean boundary = st.length > 0 && (st[0].getClassName().contains("$$Lambda$")
                        || st[0].getClassName().equals(ModEventBus.class.getName()) && st[0].getMethodName().equals("post"));
                    if (!boundary) throw x;
                }
            }
            return false;
        }
    }
}
