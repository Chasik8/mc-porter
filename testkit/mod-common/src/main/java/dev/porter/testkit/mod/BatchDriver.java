package dev.porter.testkit.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the plans of one launch (shared by both test mods, Java 8). Plans marked {@code "region": n} (scenario
 * {@code isolation: region}) run SIMULTANEOUSLY in one world: scenario n is shifted by n*{@link #SPACING} blocks along
 * x (every {@code at} position and every sample-key position), all are prepared and started together and the world
 * ticks once for the longest of them; each scenario keeps its own tick counter and sampling schedule. All other plans
 * run one after another with a world reset between them (the old behaviour).
 *
 * <p>Failures are attributed per scenario: an exception inside a scenario's op/sample fails that scenario only. If the
 * world tick itself throws, the scenario whose region (position in the exception text) or the only running one is blamed
 * (CRASHED-THE-SERVER), the others are left without result and {@code crash.json} names the culprit; the runner then
 * re-runs the unfinished scenarios sequentially.
 */
public final class BatchDriver {
    /**
     * Distance between two regions (8 chunks): chunks, light, fluids and entities cannot reach each other, and every region
     * still lies inside the ticking range of the spawn chunks (a far force-loaded chunk of a 1.19.2 server does not tick).
     */
    public static final int SPACING = 128;
    /** Regions per world: a 3x3 grid. */
    public static final int MAX_REGIONS = 9;
    private static final int[][] GRID = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    public interface Hooks {
        void prepare(JsonObject plan);

        /** One plain server tick. */
        void tick();

        /** Before each tick of a running scenario (keeps entities/tile entities ticking). */
        void beforeRunTick();

        void reset();

        /** After a reset or a warm-up tick (e.g. let the light engine finish). */
        void settle();
    }

    private BatchDriver() {
    }

    public static void run(List<JsonObject> plans, Executor.Platform platform, File defOut, Hooks h, long wallLimit, File crashFile) {
        List<JsonObject> region = new ArrayList<JsonObject>();
        List<JsonObject> serial = new ArrayList<JsonObject>();
        for (JsonObject p : plans) {
            (p.has("region") && p.get("region").getAsInt() >= 0 ? region : serial).add(p);
        }
        if (region.size() < 2) { // nothing to overlap
            serial.addAll(0, region);
            region.clear();
        }
        boolean first = true;
        if (!region.isEmpty()) {
            if (!runRegions(region, platform, defOut, h, wallLimit, crashFile)) {
                return; // the server died: the runner re-runs the unfinished scenarios
            }
            first = false;
        }
        for (JsonObject sub : serial) {
            Executor ex = new Executor(sub, platform, Executor.outFor(sub, defOut));
            long t0 = System.currentTimeMillis();
            try {
                if (!first) {
                    h.reset();
                    h.tick();
                    h.tick();
                    h.reset();
                    h.settle();
                }
                first = false;
                h.prepare(sub);
                h.tick(); // warm-up tick: same start state with and without a reset before
                h.settle();
                ex.start();
                while (!ex.isDone()) {
                    h.beforeRunTick();
                    h.tick();
                    ex.afterServerTick();
                    if (System.currentTimeMillis() - t0 > wallLimit) {
                        ex.finish("timeout", "wall-clock limit " + (wallLimit / 1000) + " s exceeded at tick " + ex.tick());
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                ex.finish("error", "CRASHED-THE-SERVER: " + t);
                writeCrash(crashFile, sub.get("scenario").getAsString(), t, "serial", new ArrayList<String>());
                return; // the rest is re-run by the runner in a fresh launch
            }
        }
    }

    /** @return false if the server died */
    private static boolean runRegions(List<JsonObject> plans, Executor.Platform platform, File defOut, Hooks h, long wallLimit,
            File crashFile) {
        List<Executor> exs = new ArrayList<Executor>();
        List<int[]> dxs = new ArrayList<int[]>();
        for (JsonObject p : plans) {
            int[] g = GRID[p.get("region").getAsInt() % MAX_REGIONS];
            int dx = g[0] * SPACING;
            int dz = g[1] * SPACING;
            dxs.add(new int[] {dx, dz});
            exs.add(new Executor(Executor.shiftPlan(p, dx, dz), platform, Executor.outFor(p, defOut), dx, dz));
        }
        long t0 = System.currentTimeMillis();
        try {
            for (Executor e : exs) {
                h.prepare(e.plan());
            }
            // far regions become ticking a few ticks after they are force-loaded (ticket propagation)
            for (int i = 0; i < 4; i++) {
                h.tick();
            }
            h.settle();
            for (Executor e : exs) {
                e.start();
            }
            while (true) {
                boolean running = false;
                for (Executor e : exs) {
                    running |= !e.isDone();
                }
                if (!running) {
                    return true;
                }
                h.beforeRunTick();
                h.tick();
                injectCrash(exs, dxs);
                for (Executor e : exs) {
                    if (e.isDone()) {
                        continue;
                    }
                    try {
                        e.afterServerTick();
                    } catch (Throwable t) { // this scenario only
                        t.printStackTrace();
                        e.finish("error", "exception: " + t);
                    }
                    if (System.currentTimeMillis() - t0 > wallLimit) {
                        e.finish("timeout", "wall-clock limit " + (wallLimit / 1000) + " s exceeded at tick " + e.tick());
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            int culprit = attribute(t, dxs, exs);
            List<String> unfinished = new ArrayList<String>();
            for (int i = 0; i < exs.size(); i++) {
                Executor e = exs.get(i);
                String id = e.plan().get("scenario").getAsString();
                if (i == culprit) {
                    e.finish("error", "CRASHED-THE-SERVER: " + t);
                } else if (!e.isDone()) {
                    unfinished.add(id); // no result file: the runner re-runs it
                }
            }
            writeCrash(crashFile, culprit >= 0 ? exs.get(culprit).plan().get("scenario").getAsString() : null, t, "region", unfinished);
            return false;
        }
    }

    /** Test hook (-Dporter.test.crash=<scenario id>): the world tick "throws" inside that scenario's region after 5 ticks. */
    private static void injectCrash(List<Executor> exs, List<int[]> dxs) {
        String id = System.getProperty("porter.test.crash");
        if (id == null) {
            return;
        }
        for (int i = 0; i < exs.size(); i++) {
            Executor e = exs.get(i);
            if (e.plan().get("scenario").getAsString().equals(id) && !e.isDone() && e.tick() >= 5) {
                throw new IllegalStateException("Ticking block entity at (" + (dxs.get(i)[0] + 2) + ", 64, " + dxs.get(i)[1] + "): injected test crash");
            }
        }
    }

    private static final Pattern TRIPLE = Pattern.compile("(-?\\d+)\\D{1,4}?(-?\\d+)\\D{1,4}?(-?\\d+)");

    /** Index of the scenario a world-tick exception belongs to (a position in its text inside a region), else the only one still running, else -1. */
    static int attribute(Throwable t, List<int[]> dxs, List<Executor> exs) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 20000; c = c.getCause()) {
            sb.append(c).append('\n');
            StringWriter w = new StringWriter();
            c.printStackTrace(new PrintWriter(w));
            sb.append(w);
            if (c.getCause() == c) {
                break;
            }
        }
        Matcher m = TRIPLE.matcher(sb);
        while (m.find()) {
            long x = Long.parseLong(m.group(1));
            long z = Long.parseLong(m.group(3));
            for (int i = 0; i < dxs.size(); i++) {
                int dx = dxs.get(i)[0];
                int dz = dxs.get(i)[1];
                if (x >= dx - SPACING / 2 && x < dx + SPACING / 2 && z >= dz - SPACING / 2 && z < dz + SPACING / 2 && !exs.get(i).isDone()) {
                    return i;
                }
            }
        }
        int only = -1;
        for (int i = 0; i < exs.size(); i++) {
            if (!exs.get(i).isDone()) {
                if (only >= 0) {
                    return -1;
                }
                only = i;
            }
        }
        return only;
    }

    private static void writeCrash(File f, String culprit, Throwable t, String mode, List<String> unfinished) {
        if (f == null) {
            return;
        }
        try {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode);
            if (culprit != null) {
                o.addProperty("culprit", culprit);
            }
            o.addProperty("exception", String.valueOf(t));
            JsonArray a = new JsonArray();
            for (String s : unfinished) {
                a.add(new JsonPrimitive(s));
            }
            o.add("unfinished", a);
            java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
            try {
                w.write(o.toString());
            } finally {
                w.close();
            }
        } catch (Exception e) {
            System.err.println("[porter-testkit] cannot write " + f + ": " + e);
        }
    }
}
