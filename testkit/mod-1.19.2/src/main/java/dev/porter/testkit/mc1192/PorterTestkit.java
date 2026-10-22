package dev.porter.testkit.mc1192;

import com.google.gson.JsonObject;
import dev.porter.testkit.mod.Executor;
import java.io.File;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * porter testkit mod for Forge 1.19.2. Inert unless {@code -Dporter.plan=<plan.json>} is given.
 *
 * <ul>
 *   <li>Dedicated server: runs the plan in a tight loop of {@link MinecraftServer#tickServer} calls right after
 *   start-up (no 50 ms tick pacing), writes {@code -Dporter.out} and stops the server.</li>
 *   <li>Client, plan side "client": {@link ClientDriver1192} creates a creative world, the plan runs at normal speed
 *   on the integrated server, GUI ops are executed by the client player, then the client quits.</li>
 * </ul>
 */
@Mod("portertestkit")
public final class PorterTestkit {
    static JsonObject plan;
    static volatile Executor clientExecutor;
    /** Client GUI driver (set on the client only, so this class never links client code on a dedicated server). */
    static volatile dev.porter.testkit.mod.GuiDriver guiDriver;
    private int playerTicks;

    public PorterTestkit() {
        MinecraftForge.EVENT_BUS.register(this);
        String planPath = System.getProperty("porter.plan");
        if (planPath != null && FMLEnvironment.dist == Dist.CLIENT) {
            try {
                plan = Executor.readPlan(new File(planPath));
            } catch (Exception e) {
                throw new IllegalStateException("[porter-testkit] cannot read plan " + planPath, e);
            }
            if ("client".equals(Executor.str(plan, "side", "server"))) {
                try {
                    // reflective: the class only exists in builds with the client toolchain (setup-client.sh)
                    java.lang.reflect.Method m = Class.forName("dev.porter.testkit.mc1192.ClientDriver1192").getDeclaredMethod("install");
                    m.setAccessible(true);
                    guiDriver = (dev.porter.testkit.mod.GuiDriver) m.invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("[porter-testkit] this build has no client driver (run setup-client.sh)", e);
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        String planPath = System.getProperty("porter.plan");
        if (planPath == null || !event.getServer().isDedicatedServer()) {
            return;
        }
        MinecraftServer server = event.getServer();
        File out = new File(System.getProperty("porter.out", "porter-raw.json"));
        List<JsonObject> plans;
        Platform1192 platform;
        try {
            plans = Executor.subPlans(Executor.readPlan(new File(planPath)));
            platform = new Platform1192(server);
        } catch (Throwable t) {
            System.err.println("[porter-testkit] cannot start plan " + planPath + ": " + t);
            t.printStackTrace();
            server.halt(false);
            return;
        }
        long wallLimit = Long.getLong("porter.wallSeconds", 600L) * 1000L;
        // a batch plan holds several scenarios: one launch; region scenarios run simultaneously, the rest one after another
        final Platform1192 pf = platform;
        final MinecraftServer srv = server;
        dev.porter.testkit.mod.BatchDriver.run(plans, platform, out, new dev.porter.testkit.mod.BatchDriver.Hooks() {
            public void prepare(JsonObject p) {
                pf.prepare(p);
            }

            public void tick() {
                tickOnce(srv, pf);
            }

            public void beforeRunTick() {
            }

            public void reset() {
                pf.reset();
            }

            public void settle() {
                pf.drainLight();
            }
        }, wallLimit, new File(out.getAbsoluteFile().getParentFile(), "crash.json"));
        server.halt(false);
    }

    private static void tickOnce(MinecraftServer server, Platform1192 platform) {
        server.tickServer(() -> false);
        while (server.pollTask()) {
            // drain main-thread tasks (chunk loading etc.)
        }
        platform.settleLight();
    }

    static dev.porter.testkit.mod.GuiDriver guiDriver() {
        if (guiDriver == null) {
            throw new IllegalStateException("GUI ops need a client run (side: client)");
        }
        return guiDriver;
    }

    /** Client mode: integrated server ticks drive the executor once the player has joined. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || plan == null || !"client".equals(Executor.str(plan, "side", "server"))) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isDedicatedServer() || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (clientExecutor == null) {
            if (playerTicks % 10 == 0) {
                Platform1192.holdPlayer(player, plan);
            }
            if (++playerTicks < 40) {
                return; // let the client settle (chunks, first frames)
            }
            Platform1192 platform = new Platform1192(server, player);
            Executor ex = new Executor(plan, platform, new File(System.getProperty("porter.out", "porter-raw.json")));
            platform.prepare(plan);
            clientExecutor = ex;
            ex.start();
            return;
        }
        if (!clientExecutor.isDone()) {
            try {
                clientExecutor.afterServerTick();
            } catch (Throwable t) {
                t.printStackTrace();
                clientExecutor.finish("error", "exception: " + t);
            }
        }
    }
}
