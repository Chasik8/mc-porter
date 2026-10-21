package dev.porter.testkit.mc1710;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import dev.porter.testkit.mod.Executor;
import java.io.File;
import net.minecraft.server.MinecraftServer;

/**
 * porter testkit mod for Forge 1.7.10 (10.13.4.1614). Inert unless {@code -Dporter.plan=<plan.json>} is given.
 *
 * <ul>
 *   <li>Dedicated server, plan side "server": runs the whole plan in a tight {@link MinecraftServer#tick()} loop from
 *   FMLServerStartedEvent (before the paced main loop starts), writes {@code -Dporter.out} and stops the server.</li>
 *   <li>Client, plan side "client": {@link ClientDriver} creates a void creative world, the plan runs at normal speed
 *   on the integrated server, {@code open_gui} dumps the real GuiContainer and takes a screenshot, then the client
 *   quits.</li>
 * </ul>
 */
@Mod(modid = "portertestkit", name = "porter testkit", version = "0.1.0", acceptableRemoteVersions = "*")
public final class PorterTestkit {
    @Mod.Instance("portertestkit")
    public static PorterTestkit instance;
    static JsonObject plan;
    static File out;
    static volatile Executor clientExecutor;
    private static int playerTicks;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        net.minecraftforge.common.ForgeChunkManager.setForcedChunkLoadingCallback(this, new net.minecraftforge.common.ForgeChunkManager.LoadingCallback() {
            @Override
            public void ticketsLoaded(java.util.List<net.minecraftforge.common.ForgeChunkManager.Ticket> tickets, net.minecraft.world.World world) {
                // scenario worlds are throw-away: nothing to restore
            }
        });
        String planPath = System.getProperty("porter.plan");
        if (planPath == null) {
            return;
        }
        try {
            plan = Executor.readPlan(new File(planPath));
        } catch (Exception e) {
            throw new IllegalStateException("[porter-testkit] cannot read plan " + planPath, e);
        }
        out = new File(System.getProperty("porter.out", "porter-raw.json"));
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT && "client".equals(Executor.str(plan, "side", "server"))) {
            ClientDriver.install();
            FMLCommonHandler.instance().bus().register(new PorterTestkit());
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (plan == null) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isDedicatedServer()) {
            return; // client mode: driven by onServerTick once the player is in the world
        }
        Platform1710 platform = new Platform1710(server.worldServerForDimension(0), null);
        long wallLimit = Long.getLong("porter.wallSeconds", 600L) * 1000L;
        boolean first = true;
        // a batch plan holds several scenarios: one server launch, the world is reset between them
        for (JsonObject sub : Executor.subPlans(plan)) {
            Executor ex = new Executor(sub, platform, Executor.outFor(sub, out));
            long t0 = System.currentTimeMillis();
            try {
                if (!first) {
                    platform.reset();
                    server.tick();
                    server.tick();
                    platform.reset();
                }
                first = false;
                platform.prepare(sub);
                server.tick(); // warm-up tick, as on 1.19.2 (same start state with and without a reset before)
                ex.start();
                while (!ex.isDone()) {
                    // WorldServer stops ticking entities/tile entities 1200 ticks after the last player left
                    for (net.minecraft.world.WorldServer w : server.worldServers) {
                        w.resetUpdateEntityTick();
                    }
                    server.tick();
                    ex.afterServerTick();
                    if (System.currentTimeMillis() - t0 > wallLimit) {
                        ex.finish("timeout", "wall-clock limit " + (wallLimit / 1000) + " s exceeded at tick " + ex.tick());
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                ex.finish("error", "exception: " + t);
            }
        }
        server.initiateShutdown();
    }

    /** Client mode: integrated server ticks drive the executor once the player has joined. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.isDedicatedServer() || server.getConfigurationManager() == null
                || server.getConfigurationManager().playerEntityList.isEmpty()) {
            return;
        }
        if (clientExecutor == null) {
            if (playerTicks % 10 == 0) {
                Platform1710.holdPlayer((net.minecraft.entity.player.EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0), plan);
            }
            if (++playerTicks < 40) {
                return; // let the client settle (chunks, first frames)
            }
            Platform1710 platform = new Platform1710(server.worldServerForDimension(0),
                    (net.minecraft.entity.player.EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0));
            Executor ex = new Executor(plan, platform, out);
            platform.prepare(plan);
            clientExecutor = ex;
            ex.start();
            return;
        }
        clientExecutor.afterServerTick();
    }
}
