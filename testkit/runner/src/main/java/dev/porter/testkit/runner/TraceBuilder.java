package dev.porter.testkit.runner;

import dev.porter.core.json.Json;
import dev.porter.testkit.runner.expr.Expr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the raw result of a test-mod run into a contract trace: normalised and sorted samples, evaluated assertions,
 * GUI dumps checked against the IR {@code menu} node, screenshots copied to {@code reports/screens/}.
 */
public final class TraceBuilder {
    private final Scenario scenario;
    private final String version;
    private final IdMap ids;
    private final IrIndex ir;
    private ObjectNode subject;
    private Path screensDir;
    private Path repoRoot;

    public TraceBuilder(Scenario scenario, String version, IdMap ids, IrIndex ir) {
        this.scenario = scenario;
        this.version = version;
        this.ids = ids;
        this.ir = ir;
    }

    public TraceBuilder subject(ObjectNode subject) {
        this.subject = subject;
        return this;
    }

    /** Where screenshots are copied, and the root that trace screenshot paths are relative to. */
    public TraceBuilder screens(Path screensDir, Path repoRoot) {
        this.screensDir = screensDir;
        this.repoRoot = repoRoot;
        return this;
    }

    /** Builds a trace for a run that produced no raw result (crash, hang, timeout). */
    public ObjectNode failedRun(String status, String message) {
        ObjectNode t = header();
        t.put("status", status);
        t.put("message", message);
        t.put("ticks", 0);
        t.putArray("samples");
        return t;
    }

    private ObjectNode header() {
        ObjectNode t = Json.json().createObjectNode();
        t.put("schemaVersion", 1);
        t.put("scenario", scenario.id());
        t.put("version", version);
        if (subject != null) {
            t.set("subject", subject);
        }
        return t;
    }

    public ObjectNode build(JsonNode raw) {
        ObjectNode t = header();
        String rawStatus = raw.path("status").asString("error");
        long ticks = raw.path("ticks").asLong(0);

        // ---- samples: normalise ids, keep every sample (several per tick are possible: before/after an interact),
        // drop exact repeats of the same (tick, key, value), sort stably by (tick, key)
        List<Entry> entries = new ArrayList<>();
        int seq = 0;
        for (JsonNode s : raw.path("samples")) {
            String key = s.path("key").asString();
            entries.add(new Entry(s.path("tick").asLong(), s.path("step").asInt(-1), seq++, key, normalise(key, s.path("value"))));
        }
        entries.sort(Comparator.comparingLong(Entry::tick).thenComparing(Entry::key).thenComparingInt(Entry::seq));
        ArrayNode samples = t.putArray("samples");
        Map<String, List<Entry>> series = new LinkedHashMap<>();
        Entry prev = null;
        for (Entry e : entries) {
            if (prev != null && prev.tick() == e.tick() && prev.key().equals(e.key()) && prev.value().equals(e.value())) {
                continue;
            }
            prev = e;
            ObjectNode s = samples.addObject();
            s.put("tick", e.tick());
            s.put("key", e.key());
            s.set("value", e.value());
            series.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e);
        }

        // ---- assertions
        ArrayNode assertions = Json.json().createArrayNode();
        List<JsonNode> steps = scenario.steps();
        long[] stepTicks = scenario.stepTicks();
        boolean allPassed = true;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (!Scenario.type(steps.get(i)).equals("assert")) {
                continue;
            }
            JsonNode a = steps.get(i).path("assert");
            String expr = a.path("expr").asString();
            ObjectNode r = assertions.addObject();
            r.put("step", i);
            r.put("tick", stepTicks[i]);
            r.put("expr", expr);
            boolean passed;
            String msg = null;
            if (!rawStatus.equals("completed") && stepTicks[i] >= ticks) {
                passed = false;
                msg = "not reached (run " + rawStatus + " at tick " + ticks + ")";
            } else {
                try {
                    Context ctx = new Context(series, stepTicks[i], i);
                    passed = Expr.test(Expr.parse(expr), ctx);
                    if (!passed) {
                        msg = describe(Expr.parse(expr), ctx);
                    }
                } catch (Expr.ExprException ex) {
                    passed = false;
                    msg = ex.getMessage();
                }
            }
            if (!passed && a.has("message")) {
                msg = a.path("message").asString() + (msg == null ? "" : " (" + msg + ")");
            }
            r.put("passed", passed);
            if (msg != null) {
                r.put("message", msg);
            }
            if (!passed) {
                allPassed = false;
                failures.add("step " + i + ": " + expr + (msg == null ? "" : " — " + msg));
            }
        }

        // ---- GUI dumps
        ArrayNode gui = Json.json().createArrayNode();
        java.util.Set<Integer> guiSteps = new java.util.HashSet<>();
        for (JsonNode g : raw.path("gui")) {
            int step = g.path("step").asInt();
            guiSteps.add(step);
            if (g.has("error")) {
                allPassed = false;
                failures.add("step " + step + " GUI " + g.path("capture").asString("") + ": " + g.path("error").asString());
                continue;
            }
            if (!g.has("size") || !g.has("slots")) {
                continue; // check mode "absent": no screen opened (the GUI report evaluates it)
            }
            ObjectNode o = gui.addObject();
            o.put("step", step);
            if (g.has("menu")) {
                o.put("menu", g.path("menu").asString());
            }
            o.set("size", g.path("size").deepCopy());
            ArrayNode slots = o.putArray("slots");
            for (JsonNode s : g.path("slots")) {
                slots.add(s.deepCopy());
            }
            if (g.has("bars")) {
                o.set("bars", g.path("bars").deepCopy());
            }
            String shot = copyScreenshot(g.path("screenshotFile").asString(null), step);
            if (shot != null) {
                o.put("screenshot", shot);
            }
            if (g.has("menu")) {
                JsonNode menu = ir.menu(g.path("menu").asString());
                ObjectNode r = assertions.addObject();
                r.put("step", step);
                r.put("tick", stepTicks[step]);
                r.put("expr", "gui == ir:" + g.path("menu").asString());
                if (menu == null) {
                    r.put("passed", false);
                    r.put("message", "no IR menu node " + g.path("menu").asString() + " (pass --ir)");
                    allPassed = false;
                    failures.add("step " + step + ": no IR menu node");
                } else {
                    List<String> diffs = GuiCheck.compare(menu, g);
                    r.put("passed", diffs.isEmpty());
                    if (!diffs.isEmpty()) {
                        r.put("message", String.join("; ", diffs));
                        allPassed = false;
                        failures.add("step " + step + " GUI: " + String.join("; ", diffs));
                    }
                }
            }
        }
        // ensure every open_gui step produced a dump
        for (int i = 0; i < steps.size(); i++) {
            if (Scenario.type(steps.get(i)).equals("open_gui") && rawStatus.equals("completed")) {
                final int step = i;
                if (!guiSteps.contains(step)) {
                    allPassed = false;
                    failures.add("step " + i + ": open_gui produced no dump");
                }
            }
        }

        String status;
        String message = null;
        switch (rawStatus) {
            case "completed" -> {
                status = allPassed ? "passed" : "failed";
                if (!allPassed) {
                    message = String.join(" | ", failures);
                }
            }
            case "timeout" -> {
                status = "timeout";
                message = raw.path("message").asString("timeout");
            }
            default -> {
                status = "error";
                message = raw.path("message").asString("error");
            }
        }
        // keep a stable field order: status before samples
        ObjectNode out = header();
        out.put("status", status);
        if (message != null) {
            out.put("message", message);
        }
        out.put("ticks", ticks);
        out.set("samples", samples);
        if (!assertions.isEmpty()) {
            out.set("assertions", assertions);
        }
        if (!gui.isEmpty()) {
            out.set("gui", gui);
        }
        return out;
    }

    private String copyScreenshot(String file, int step) {
        if (file == null || screensDir == null) {
            return null;
        }
        Path src = Path.of(file);
        if (!Files.isRegularFile(src)) {
            return null;
        }
        try {
            Files.createDirectories(screensDir);
            Path dst = screensDir.resolve(scenario.id() + "-" + version + (step > 0 ? "-step" + step : "") + ".png");
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Path root = repoRoot != null ? repoRoot.toAbsolutePath().normalize() : null;
            Path abs = dst.toAbsolutePath().normalize();
            String rel = root != null && abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
            return rel.replace('\\', '/');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maps registry names of this version back to IR ids inside sampled values. */
    JsonNode normalise(String key, JsonNode v) {
        if (v == null || v.isMissingNode()) {
            return Json.json().nullNode();
        }
        String probe = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        if (probe.equals("block") && v.isString()) {
            String back = ids.reverseBlock(v.asString(), 0);
            return back == null ? v : Json.json().getNodeFactory().stringNode(back);
        }
        return walk(v);
    }

    private JsonNode walk(JsonNode v) {
        if (v.isArray()) {
            ArrayNode a = Json.json().createArrayNode();
            for (JsonNode e : v) {
                a.add(walk(e));
            }
            return a;
        }
        if (!v.isObject()) {
            return v;
        }
        ObjectNode o = Json.json().createObjectNode();
        for (Map.Entry<String, JsonNode> e : v.properties()) {
            o.set(e.getKey(), walk(e.getValue()));
        }
        if (o.path("item").isString() && o.path("count").isNumber()) {
            int damage = o.path("damage").asInt(0);
            String back = ids.reverseItem(o.path("item").asString(), damage);
            if (back != null) {
                o.put("item", back);
                if (o.has("damage") && ids.item(back).has("meta")) {
                    o.remove("damage");
                }
            }
        }
        if (o.path("fluid").isString() && o.has("amount")) {
            String f = o.path("fluid").asString();
            String back = ids.reverseFluid(f);
            if (back != null) {
                o.put("fluid", back);
            } else if (version.equals("forge-1.7.10") && !f.contains(":")) {
                o.put("fluid", "minecraft:" + f);
            }
        }
        return o;
    }

    private static String describe(Expr.Node n, Context ctx) {
        List<String> parts = new ArrayList<>();
        for (String k : Expr.allKeys(n)) {
            try {
                JsonNode v = ctx.latest(ctx.resolve(k));
                parts.add(k + " = " + (v == null ? "(not sampled)" : v.toString()));
            } catch (Expr.ExprException e) {
                parts.add(k + ": " + e.getMessage());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** One raw sample: scenario tick, scenario step that took it, arrival order. */
    record Entry(long tick, int step, int seq, String key, JsonNode value) {
    }

    /** Evaluation context over this trace; keys in expressions are canonical and mapped to this version's keys. */
    final class Context implements Expr.Context {
        private final Map<String, List<Entry>> series;
        private final long now;
        private final int nowStep;

        Context(Map<String, List<Entry>> series, long now, int nowStep) {
            this.series = series;
            this.now = now;
            this.nowStep = nowStep;
        }

        /** Latest sample taken before the assert step (earlier tick, or same tick and this or an earlier step). */
        @Override
        public JsonNode latest(String key) {
            JsonNode last = null;
            for (Entry e : series.getOrDefault(key, List.of())) {
                if (e.tick() < now || (e.tick() == now && e.step() <= nowStep)) {
                    last = e.value();
                }
            }
            return last;
        }

        @Override
        public List<Expr.TimedValue> series(String key) {
            List<Expr.TimedValue> out = new ArrayList<>();
            for (Entry e : series.getOrDefault(key, List.of())) {
                out.add(new Expr.TimedValue(e.tick(), e.value()));
            }
            return out;
        }

        @Override
        public String resolve(String keyOrProbe) {
            String versionKey = scenario.toVersionKey(keyOrProbe, version);
            if (series.containsKey(versionKey)) {
                return versionKey;
            }
            if (!keyOrProbe.contains(":")) {
                List<String> matches = series.keySet().stream()
                        .filter(k -> scenario.toCanonicalKey(k, version).startsWith(keyOrProbe + ":")).sorted(Comparator.naturalOrder()).toList();
                if (matches.size() == 1) {
                    return matches.get(0);
                }
                if (matches.size() > 1) {
                    throw new Expr.ExprException("'" + keyOrProbe + "' is ambiguous: " + matches);
                }
                throw new Expr.ExprException("no sampled key for probe '" + keyOrProbe + "'");
            }
            return versionKey;
        }

        @Override
        public long now() {
            return now;
        }
    }
}
