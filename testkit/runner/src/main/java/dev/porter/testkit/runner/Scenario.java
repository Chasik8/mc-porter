package dev.porter.testkit.runner;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** A validated scenario document (contracts/scenario.schema.json) with helpers for key mapping and tolerances. */
public final class Scenario {
    private final JsonNode doc;
    private final Path file;

    public Scenario(JsonNode doc, Path file) {
        this.doc = doc;
        this.file = file;
    }

    /** Reads and validates every scenario document of a file. */
    public static List<Scenario> read(Path file) {
        List<Scenario> out = new ArrayList<>();
        for (JsonNode d : ContractIo.readValidated(file, Contract.SCENARIO)) {
            out.add(new Scenario(d, file));
        }
        return out;
    }

    public JsonNode doc() {
        return doc;
    }

    public Path file() {
        return file;
    }

    public String id() {
        return doc.path("id").asString();
    }

    public String world() {
        return doc.path("world").asString();
    }

    public String side() {
        return doc.path("side").asString("server");
    }

    /** "region" (may run simultaneously with other region scenarios in one world) or "global" (default: touches world-wide state). */
    public String isolation() {
        return doc.path("isolation").asString("global");
    }

    public long maxTicks() {
        return doc.has("maxTicks") ? doc.path("maxTicks").asLong() : -1;
    }

    public List<JsonNode> steps() {
        List<JsonNode> s = new ArrayList<>();
        doc.path("steps").forEach(s::add);
        return s;
    }

    /** Step type (the single key of a step object). */
    public static String type(JsonNode step) {
        return step.propertyNames().iterator().next();
    }

    /** Scenario tick at which each step starts (ticks are deterministic: only {@code tick} steps advance time). */
    public long[] stepTicks() {
        List<JsonNode> steps = steps();
        long[] t = new long[steps.size()];
        long now = 0;
        for (int i = 0; i < steps.size(); i++) {
            t[i] = now;
            if (type(steps.get(i)).equals("tick")) {
                now += steps.get(i).path("tick").asLong();
            }
        }
        return t;
    }

    public long totalTicks() {
        long now = 0;
        for (JsonNode s : steps()) {
            if (type(s).equals("tick")) {
                now += s.path("tick").asLong();
            }
        }
        return now;
    }

    /** keyMap[version]: version key -> canonical key. */
    public Map<String, String> keyMap(String version) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : doc.path("keyMap").path(version).properties()) {
            m.put(e.getKey(), e.getValue().asString());
        }
        return m;
    }

    /** canonical key -> version key (inverse keyMap; identity when unmapped). */
    public Map<String, String> inverseKeyMap(String version) {
        Map<String, String> inv = new HashMap<>();
        keyMap(version).forEach((k, v) -> inv.put(v, k));
        return inv;
    }

    public String toVersionKey(String canonical, String version) {
        return inverseKeyMap(version).getOrDefault(canonical, canonical);
    }

    public String toCanonicalKey(String versionKey, String version) {
        return keyMap(version).getOrDefault(versionKey, versionKey);
    }

    /** Tolerance for a canonical key: exact key, else the longest matching prefix, else '*', else exact equality. */
    public Tolerance tolerance(String canonicalKey) {
        JsonNode tol = doc.path("tolerance");
        JsonNode exact = tol.get(canonicalKey);
        if (exact != null) {
            return Tolerance.of(exact);
        }
        String best = null;
        for (String k : tol.propertyNames()) {
            if (!k.equals("*") && canonicalKey.startsWith(k) && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        if (best != null) {
            return Tolerance.of(tol.get(best));
        }
        JsonNode star = tol.get("*");
        return star != null ? Tolerance.of(star) : Tolerance.EXACT;
    }

    /** Numeric tolerance of one key. */
    public record Tolerance(double abs, double rel, int tickShift, boolean ignore) {
        public static final Tolerance EXACT = new Tolerance(0, 0, 0, false);

        static Tolerance of(JsonNode n) {
            return new Tolerance(n.path("abs").asDouble(0), n.path("rel").asDouble(0), n.path("tickShift").asInt(0),
                    n.path("ignore").asBoolean(false));
        }

        public boolean numbersMatch(double a, double b) {
            double d = Math.abs(a - b);
            return d <= abs || d <= rel * Math.max(Math.abs(a), Math.abs(b)) || a == b;
        }
    }
}
