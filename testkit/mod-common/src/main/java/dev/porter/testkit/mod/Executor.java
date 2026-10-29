package dev.porter.testkit.mod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-independent plan executor shared by the 1.7.10 and 1.19.2 test mods (Java 8, Gson only).
 *
 * <p>The porter runner compiles a scenario into a flat <em>plan</em> (testkit/README.md, "plan format"): ids are
 * already resolved to the registry names of this version, multiblocks are expanded into {@code place} ops and assert
 * keys are sampled explicitly. The executor runs the ops, counts scenario ticks and records raw samples; assertions,
 * key mapping and trace building happen in the runner.
 *
 * <p>Driving: call {@link #start()} once, then {@link #afterServerTick()} after every server tick until
 * {@link #isDone()}. Ops between two {@code tick} ops run immediately in the same server tick.
 */
public final class Executor {
    /** Version-specific world access. */
    public interface Platform {
        String version();

        void place(JsonObject op) throws Exception;

        /** @return mB actually filled */
        int fill(JsonObject op) throws Exception;

        /** @return items actually inserted */
        int insert(JsonObject op) throws Exception;

        /** @return the energy the tile accepted (units of its own system: EU, RF); the step insert_energy */
        default double energy(JsonObject op) throws Exception {
            throw new UnsupportedOperationException("insert_energy is not supported on " + version());
        }

        /** @return a short description of the result of the step invoke_tile (a method call on the block entity) */
        default String invoke(JsonObject op) throws Exception {
            throw new UnsupportedOperationException("invoke_tile is not supported on " + version());
        }

        /** @return a short result description of the step break_block (player or world break, drops spawned) */
        default String breakBlock(JsonObject op) throws Exception {
            throw new UnsupportedOperationException("break_block is not supported on " + version());
        }

        /** @return a short result description of the step explode */
        default String explode(JsonObject op) throws Exception {
            throw new UnsupportedOperationException("explode is not supported on " + version());
        }

        /** @return a short result description for the log */
        String interact(JsonObject op) throws Exception;

        /**
         * GUI ops (open_gui, gui_input, close_gui; client runs only): attempt 0 submits the op, later attempts poll.
         * Returns the dump (raw "gui" entry without "step"), or null while the op is still running.
         */
        JsonObject gui(JsonObject op, int attempt) throws Exception;

        JsonElement probe(Probe p) throws Exception;

        /**
         * Batch runs: puts the world back to its start state between two scenarios (blocks of the chunks the scenarios
         * touched cleared through normal block removal, entities removed, fake player emptied). Called twice, with two
         * server ticks in between (block removal spills items, tile entities are dropped on the next tick).
         */
        void reset() throws Exception;

        /** Called before a set of keys is sampled (e.g. to let an asynchronous light engine finish). */
        default void beforeSample(JsonArray keys) throws Exception {
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final JsonObject plan;
    private final Platform platform;
    private final JsonArray ops;
    private final File out;
    private final List<long[]> samplerMeta = new ArrayList<long[]>(); // {startTick, every}
    private final List<JsonArray> samplerKeys = new ArrayList<JsonArray>();
    private final JsonArray samples = new JsonArray();
    private final JsonArray gui = new JsonArray();
    private final JsonArray log = new JsonArray();
    private final long maxTicks;
    private final long startedAt = System.currentTimeMillis();
    private final int dx;
    private final int dz;
    private int index;
    private long tick;
    private long remaining;
    private int guiAttempts;
    private boolean done;
    private String status = "completed";
    private String message;

    public Executor(JsonObject plan, Platform platform, File out) {
        this(plan, platform, out, 0, 0);
    }

    /** dx: region shift of a simultaneous run; the plan's ops are already shifted, sample keys are shifted on use. */
    public Executor(JsonObject plan, Platform platform, File out, int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
        this.plan = plan;
        this.platform = platform;
        this.ops = plan.getAsJsonArray("ops");
        this.out = out;
        this.maxTicks = plan.has("maxTicks") ? plan.get("maxTicks").getAsLong() : Long.MAX_VALUE;
    }

    /** Copy of a plan with every op position (at) moved by dx/dz. */
    public static JsonObject shiftPlan(JsonObject plan, int dx, int dz) {
        JsonObject c = new JsonParser().parse(plan.toString()).getAsJsonObject();
        for (JsonElement e : c.getAsJsonArray("ops")) {
            JsonObject op = e.getAsJsonObject();
            if (op.has("at") && op.get("at").isJsonArray()) {
                JsonArray a = op.getAsJsonArray("at");
                JsonArray n = new JsonArray();
                n.add(new JsonPrimitive(Integer.valueOf(a.get(0).getAsInt() + dx)));
                n.add(a.get(1));
                n.add(new JsonPrimitive(Integer.valueOf(a.get(2).getAsInt() + dz)));
                op.add("at", n);
            }
        }
        return c;
    }

    /** Sample key with its [x,y,z] target moved by dx/dz. */
    public static String shiftKey(String key, int dx, int dz) {
        int open = key.indexOf(":[");
        if (open < 0) {
            return key;
        }
        int close = key.indexOf(']', open);
        String[] p = key.substring(open + 2, close).split(",");
        return key.substring(0, open + 2) + (Integer.parseInt(p[0].trim()) + dx) + "," + p[1].trim() + "," + (Integer.parseInt(p[2].trim()) + dz) + key.substring(close);
    }

    public static JsonObject readPlan(File file) throws Exception {
        Reader r = new InputStreamReader(new FileInputStream(file), UTF8);
        try {
            return new JsonParser().parse(r).getAsJsonObject();
        } finally {
            r.close();
        }
    }

    public JsonObject plan() {
        return plan;
    }

    /** A batch plan ({@code "plans": [...]}) holds several single plans run one after another in one launch. */
    public static boolean isBatch(JsonObject plan) {
        return plan.has("plans");
    }

    /** The single plans of a plan file: the batch members (each with its own {@code "out"} file), or the plan itself. */
    public static List<JsonObject> subPlans(JsonObject plan) {
        List<JsonObject> l = new ArrayList<JsonObject>();
        if (isBatch(plan)) {
            for (JsonElement e : plan.getAsJsonArray("plans")) {
                l.add(e.getAsJsonObject());
            }
        } else {
            l.add(plan);
        }
        return l;
    }

    /** Result file of one single plan: its own {@code "out"} (batch member), else the default. */
    public static File outFor(JsonObject sub, File def) {
        return sub.has("out") ? new File(sub.get("out").getAsString()) : def;
    }

    public boolean isDone() {
        return done;
    }

    public long tick() {
        return tick;
    }

    public void start() {
        advance();
    }

    /** Call after every server tick. */
    public void afterServerTick() {
        if (done) {
            return;
        }
        if (remaining > 0) {
            tick++;
            remaining--;
            periodic();
            if (tick > maxTicks) {
                finish("timeout", "maxTicks " + maxTicks + " exceeded");
                return;
            }
            if (remaining == 0) {
                index++;
                advance();
            }
            return;
        }
        // waiting for a GUI (client) — retry the pending op
        advance();
    }

    private void periodic() {
        for (int i = 0; i < samplerMeta.size(); i++) {
            long[] m = samplerMeta.get(i);
            if ((tick - m[0]) % m[1] == 0) {
                sampleAll(samplerKeys.get(i), currentStep());
            }
        }
    }

    private void advance() {
        while (!done && index < ops.size()) {
            JsonObject op = ops.get(index).getAsJsonObject();
            String kind = op.get("op").getAsString();
            try {
                if (kind.equals("tick")) {
                    remaining = op.get("n").getAsLong();
                    if (remaining > 0) {
                        return;
                    }
                } else if (kind.equals("place")) {
                    platform.place(op);
                } else if (kind.equals("fill")) {
                    int n = platform.fill(op);
                    note(op, "filled " + n + " mB");
                } else if (kind.equals("insert")) {
                    int n = platform.insert(op);
                    note(op, "inserted " + n);
                    if (n == 0) {
                        warn(op, "nothing inserted");
                    }
                } else if (kind.equals("energy")) {
                    double n = platform.energy(op);
                    note(op, "accepted " + n + " energy");
                    if (n <= 0) {
                        warn(op, "no energy accepted");
                    }
                } else if (kind.equals("invoke")) {
                    note(op, platform.invoke(op));
                } else if (kind.equals("interact")) {
                    note(op, platform.interact(op));
                } else if (kind.equals("break")) {
                    note(op, platform.breakBlock(op));
                } else if (kind.equals("explode")) {
                    note(op, platform.explode(op));
                } else if (kind.equals("open_gui") || kind.equals("gui_input") || kind.equals("close_gui")) {
                    JsonObject dump = platform.gui(op, guiAttempts++);
                    if (dump == null) {
                        if (guiAttempts > 400) {
                            throw new IllegalStateException(kind + " did not finish within 400 ticks");
                        }
                        return; // retry next tick
                    }
                    guiAttempts = 0;
                    if (dump.has("error")) {
                        warn(op, "GUI " + dump.get("error").getAsString());
                    }
                    if (dump.has("capture") || dump.has("size")) {
                        JsonObject g = new JsonObject();
                        g.addProperty("step", op.get("step").getAsInt());
                        if (op.has("menu")) {
                            g.add("menu", op.get("menu"));
                        }
                        for (java.util.Map.Entry<String, JsonElement> e : dump.entrySet()) {
                            g.add(e.getKey(), e.getValue());
                        }
                        gui.add(g);
                    }
                } else if (kind.equals("sample")) {
                    JsonArray keys = op.getAsJsonArray("keys");
                    sampleAll(keys, op.get("step").getAsInt());
                    if (op.has("every")) {
                        samplerMeta.add(new long[] {tick, op.get("every").getAsLong()});
                        samplerKeys.add(keys);
                    }
                } else if (kind.equals("log")) {
                    note(op, op.get("text").getAsString());
                } else {
                    throw new IllegalArgumentException("unknown op " + kind);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                finish("error", "step " + (op.has("step") ? op.get("step").getAsInt() : -1) + " (" + kind + "): " + t);
                return;
            }
            index++;
        }
        if (!done && index >= ops.size()) {
            finish("completed", null);
        }
    }

    /** Scenario step of the op being executed (periodic samples belong to the running tick step). */
    private int currentStep() {
        if (index < ops.size()) {
            JsonObject op = ops.get(index).getAsJsonObject();
            return op.has("step") ? op.get("step").getAsInt() : -1;
        }
        return -1;
    }

    private void sampleAll(JsonArray keys, int step) {
        try {
            platform.beforeSample(keys);
        } catch (Throwable t) {
            JsonObject l = new JsonObject();
            l.addProperty("tick", tick);
            l.addProperty("level", "warn");
            l.addProperty("text", "beforeSample: " + t);
            log.add(l);
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i).getAsString();
            JsonElement v;
            try {
                v = platform.probe(Probe.parse(dx == 0 && dz == 0 ? key : shiftKey(key, dx, dz)));
            } catch (Throwable t) {
                v = JsonNull.INSTANCE;
                JsonObject l = new JsonObject();
                l.addProperty("tick", tick);
                l.addProperty("level", "warn");
                l.addProperty("text", "probe " + key + ": " + t);
                log.add(l);
            }
            JsonObject s = new JsonObject();
            s.addProperty("tick", tick);
            s.addProperty("key", key);
            s.addProperty("step", step);
            s.add("value", v == null ? JsonNull.INSTANCE : v);
            samples.add(s);
        }
    }

    private void note(JsonObject op, String text) {
        JsonObject l = new JsonObject();
        l.addProperty("tick", tick);
        if (op.has("step")) {
            l.addProperty("step", op.get("step").getAsInt());
        }
        l.addProperty("op", op.get("op").getAsString());
        l.addProperty("text", text);
        log.add(l);
    }

    private void warn(JsonObject op, String text) {
        note(op, "WARN " + text);
    }

    /** Ends the run (also used by watchdogs) and writes the raw result file. */
    public synchronized void finish(String status, String message) {
        if (done) {
            return;
        }
        done = true;
        this.status = status;
        this.message = message;
        write();
    }

    private void write() {
        JsonObject o = new JsonObject();
        o.addProperty("format", "porter-testkit-raw/1");
        o.add("scenario", plan.get("scenario"));
        o.addProperty("version", platform.version());
        o.addProperty("status", status);
        if (message != null) {
            o.addProperty("message", message);
        }
        o.addProperty("ticks", tick);
        o.addProperty("wallMillis", System.currentTimeMillis() - startedAt);
        o.add("samples", samples);
        o.add("gui", gui);
        o.add("log", log);
        try {
            File tmp = new File(out.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            try {
                new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o, w);
            } finally {
                w.close();
            }
            if (out.exists() && !out.delete()) {
                throw new IllegalStateException("cannot replace " + out);
            }
            if (!tmp.renameTo(out)) {
                throw new IllegalStateException("cannot rename " + tmp);
            }
        } catch (Exception e) {
            System.err.println("[porter-testkit] cannot write " + out + ": " + e);
        }
        System.out.println("[porter-testkit] scenario " + plan.get("scenario") + " " + status
                + (message == null ? "" : ": " + message) + " after " + tick + " ticks -> " + out);
    }

    public String status() {
        return status;
    }

    // ------------------------------------------------------------------ helpers for platforms

    public static int[] pos(JsonObject op, String field) {
        JsonArray a = op.getAsJsonArray(field);
        return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    public static String str(JsonObject o, String field, String def) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : def;
    }

    public static int integer(JsonObject o, String field, int def) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsInt() : def;
    }

    public static JsonPrimitive num(long v) {
        return new JsonPrimitive(v);
    }

    /** Navigates a JSON value along probe path segments (object keys or array indices). */
    public static JsonElement path(JsonElement v, String[] path) {
        JsonElement cur = v;
        for (String seg : path) {
            if (cur == null || cur.isJsonNull()) {
                return JsonNull.INSTANCE;
            }
            if (cur.isJsonObject()) {
                cur = cur.getAsJsonObject().get(seg);
            } else if (cur.isJsonArray()) {
                int i = Integer.parseInt(seg);
                JsonArray a = cur.getAsJsonArray();
                cur = i >= 0 && i < a.size() ? a.get(i) : JsonNull.INSTANCE;
            } else {
                return JsonNull.INSTANCE;
            }
        }
        return cur == null ? JsonNull.INSTANCE : cur;
    }

    /** Typed NBT literal for JSON strings like "5b", "3s", "7L", "1.5f", "2.0d"; null if not typed. */
    public static Object typedNumber(String s) {
        if (s.length() < 2) {
            return null;
        }
        char t = s.charAt(s.length() - 1);
        String n = s.substring(0, s.length() - 1);
        try {
            switch (t) {
                case 'b':
                case 'B':
                    return Byte.valueOf(Byte.parseByte(n));
                case 's':
                case 'S':
                    return Short.valueOf(Short.parseShort(n));
                case 'L':
                case 'l':
                    return Long.valueOf(Long.parseLong(n));
                case 'f':
                case 'F':
                    return Float.valueOf(Float.parseFloat(n));
                case 'd':
                case 'D':
                    return Double.valueOf(Double.parseDouble(n));
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reflection probe helpers shared by both versions. */
    public static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + target.getClass().getName());
    }

    public static Object call(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m.invoke(target);
                }
            }
            c = c.getSuperclass();
        }
        // public interface default methods are not declared by any class of the chain
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                m.setAccessible(true);
                return m.invoke(target);
            }
        }
        throw new NoSuchMethodException(name + "() in " + target.getClass().getName());
    }

    /** Plain Java value (number, string, boolean, enum, array, collection) to JSON; others via toString. */
    public static JsonElement plain(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof Number) {
            Number n = (Number) v;
            if (v instanceof Float || v instanceof Double) {
                double d = n.doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    return new JsonPrimitive(Long.valueOf((long) d));
                }
                return new JsonPrimitive(Double.valueOf(d));
            }
            return new JsonPrimitive(Long.valueOf(n.longValue()));
        }
        if (v instanceof Boolean) {
            return new JsonPrimitive((Boolean) v);
        }
        if (v instanceof String) {
            return new JsonPrimitive((String) v);
        }
        if (v instanceof Character) {
            return new JsonPrimitive(String.valueOf(v));
        }
        if (v instanceof Enum) {
            return new JsonPrimitive(((Enum<?>) v).name().toLowerCase(java.util.Locale.ROOT));
        }
        if (v.getClass().isArray()) {
            JsonArray a = new JsonArray();
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                a.add(plain(java.lang.reflect.Array.get(v, i)));
            }
            return a;
        }
        if (v instanceof Iterable) {
            JsonArray a = new JsonArray();
            for (Object o : (Iterable<?>) v) {
                a.add(plain(o));
            }
            return a;
        }
        return new JsonPrimitive(v.toString());
    }
}
