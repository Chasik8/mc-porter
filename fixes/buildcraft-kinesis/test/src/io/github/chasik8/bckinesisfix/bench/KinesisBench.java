package io.github.chasik8.bckinesisfix.bench;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only mod (not part of the fix jar). Inert unless -Dbckinesis.bench=1 is set on a dedicated server.
 * Builds: redstone block, creative engine (1280 RF/t), emerald kinesis pipe, two gold kinesis pipes, quarry.
 * Every tick it records how much RF the last pipe delivered into the quarry, then empties the quarry's battery so
 * the quarry behaves as a machine that always wants its full 1000 RF/t. Writes bench-result.txt and stops the server.
 * Everything Minecraft/BuildCraft is reached by reflection (SRG names), so this compiles against FML only.
 */
@Mod(modid = "bckinesisbench", name = "BC kinesis bench (test only)", version = "1.0.0", acceptableRemoteVersions = "*")
public class KinesisBench {

    private static final int START = 20;
    private static final int WARMUP = 40;
    private int ticks;
    private int measure;
    private Object world;
    private Object lastPipeTransport;
    private Object quarry;
    private Object engine;
    private final List<Integer> delivered = new ArrayList<Integer>();
    private final List<Integer> engineEnergy = new ArrayList<Integer>();
    private int x;
    private int y;
    private int z;

    @Mod.EventHandler
    public void started(FMLServerStartedEvent e) {
        if (!"1".equals(System.getProperty("bckinesis.bench"))) {
            return;
        }
        measure = Integer.getInteger("bckinesis.ticks", 400);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        ticks++;
        try {
            if (ticks == START) {
                build();
            } else if (ticks > START + WARMUP && delivered.size() < measure) {
                int[] out = (int[]) field(lastPipeTransport, "dbgEnergyOutput").get(lastPipeTransport);
                delivered.add(out[5]); // EAST, towards the quarry
                engineEnergy.add(field(engine, "energy").getInt(engine));
            }
            if (ticks > START) {
                Object battery = call(quarry, "getBattery");
                call(battery, "setEnergy", 0);
            }
            if (delivered.size() >= measure) {
                report(null);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            report(t);
        }
    }

    private void build() throws Exception {
        Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
        Object server = serverClass.getMethod("func_71276_C").invoke(null);
        world = ((Object[]) field(server, "field_71305_c").get(server))[0];
        Object info = call(world, "func_72912_H");
        x = (Integer) call(info, "func_76079_c") + 8;
        z = (Integer) call(info, "func_76074_e");
        y = 200;
        Class<?> gr = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
        Method findBlock = gr.getMethod("findBlock", String.class, String.class);
        Object redstone = findBlock.invoke(null, "minecraft", "redstone_block");
        Object engineBlock = staticField("buildcraft.BuildCraftCore", "engineBlock");
        Object pipeBlock = staticField("buildcraft.BuildCraftTransport", "genericPipeBlock");
        Object quarryBlock = staticField("buildcraft.BuildCraftBuilders", "quarryBlock");

        setBlock(x - 1, redstone, 0);
        setBlock(x, engineBlock, 3); // creative engine
        placePipe(x + 1, staticField("buildcraft.BuildCraftTransport", "pipePowerEmerald"), pipeBlock);
        placePipe(x + 2, staticField("buildcraft.BuildCraftTransport", "pipePowerGold"), pipeBlock);
        placePipe(x + 3, staticField("buildcraft.BuildCraftTransport", "pipePowerGold"), pipeBlock);
        setBlock(x + 4, quarryBlock, 4); // facing west: its mining area is on the far (+x) side

        engine = te(x);
        Class<?> mode = Class.forName("buildcraft.core.PowerMode");
        field(engine, "powerMode").set(engine, mode.getField("M128").get(null));
        call(engine, "onNeighborUpdate");
        call(engine, "switchOrientation", true);
        quarry = te(x + 4);
        Object pipe = field(te(x + 3), "pipe").get(te(x + 3));
        lastPipeTransport = field(pipe, "transport").get(pipe);
        System.out.println("[bckinesis-bench] built at " + x + "," + y + "," + z + " engine orientation "
                + field(engine, "orientation").get(engine) + " redstone " + field(engine, "isRedstonePowered").get(engine));
    }

    private void report(Throwable error) {
        try {
            long sum = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            int zero = 0;
            for (int v : delivered) {
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
                if (v == 0) {
                    zero++;
                }
            }
            boolean patched = false;
            try {
                Class.forName("io.github.chasik8.bckinesisfix.KinesisFixContainer");
                patched = true;
            } catch (ClassNotFoundException ignored) {
            }
            StringBuilder sb = new StringBuilder();
            sb.append("fix jar loaded: ").append(patched).append('\n');
            sb.append("ticks measured: ").append(delivered.size()).append('\n');
            sb.append("delivered RF/t into the quarry: avg ")
                    .append(delivered.isEmpty() ? 0 : String.format(java.util.Locale.ROOT, "%.1f", sum / (double) delivered.size()))
                    .append(", min ").append(delivered.isEmpty() ? 0 : min).append(", max ").append(max)
                    .append(", ticks with 0: ").append(zero).append('\n');
            sb.append("first 20 ticks: ").append(delivered.subList(0, Math.min(20, delivered.size()))).append('\n');
            sb.append("engine buffer (last 10 ticks): ")
                    .append(engineEnergy.subList(Math.max(0, engineEnergy.size() - 10), engineEnergy.size())).append('\n');
            sb.append("blocks still in place: ").append(stillThere()).append('\n');
            if (error != null) {
                sb.append("ERROR: ").append(error).append('\n');
            }
            System.out.print("[bckinesis-bench]\n" + sb);
            Writer w = new OutputStreamWriter(new FileOutputStream(new File("bench-result.txt")), "UTF-8");
            w.write(sb.toString());
            w.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        delivered.clear();
        measure = Integer.MAX_VALUE;
        try {
            Object server = Class.forName("net.minecraft.server.MinecraftServer").getMethod("func_71276_C").invoke(null);
            call(server, "func_71263_m");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private boolean stillThere() {
        try {
            for (int dx = 0; dx <= 4; dx++) {
                if (te(x + dx) == null) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void setBlock(int bx, Object block, int meta) throws Exception {
        Method m = find(world.getClass(), "func_147465_d", 6);
        m.invoke(world, bx, y, z, block, meta, 3);
    }

    private void placePipe(int bx, Object item, Object pipeBlock) throws Exception {
        Class<?> bgp = Class.forName("buildcraft.transport.BlockGenericPipe");
        Object pipe = find(bgp, "createPipe", 1).invoke(null, item);
        Class<?> dir = Class.forName("net.minecraftforge.common.util.ForgeDirection");
        Object unknown = dir.getField("UNKNOWN").get(null);
        find(bgp, "placePipe", 9).invoke(null, pipe, world, bx, y, z, pipeBlock, 0, null, unknown);
    }

    private Object te(int bx) throws Exception {
        return find(world.getClass(), "func_147438_o", 3).invoke(world, bx, y, z);
    }

    private static Object staticField(String cls, String name) throws Exception {
        return Class.forName(cls).getField(name).get(null);
    }

    private static Field field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method find(Class<?> cls, String name, int params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("no method " + name + "/" + params + " in " + cls);
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        return find(o.getClass(), name, args.length).invoke(o, args);
    }
}
