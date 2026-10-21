package dev.porter.testkit.runner;

import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compares two traces of the same scenario (usually two platform versions) sample by sample after mapping their keys
 * to canonical keys (scenario keyMap), with the scenario's tolerances: numbers within abs/rel (also inside objects and
 * arrays), other values equal, samples matched within ±tickShift ticks, ignored keys skipped.
 */
public final class TraceDiff {
    private TraceDiff() {
    }

    /** Result: per-key statistics and mismatches (report JSON, not a frozen contract). */
    public record Result(boolean equal, ObjectNode report) {
    }

    public static Result compare(Scenario sc, JsonNode a, JsonNode b) {
        String va = a.path("version").asString();
        String vb = b.path("version").asString();
        Map<String, TreeMap<Long, List<JsonNode>>> sa = canonical(sc, a, va);
        Map<String, TreeMap<Long, List<JsonNode>>> sb = canonical(sc, b, vb);
        TreeSet<String> keys = new TreeSet<>(sa.keySet());
        keys.addAll(sb.keySet());
        ObjectNode report = Json.json().createObjectNode();
        report.put("scenario", sc.id());
        report.put("a", va);
        report.put("b", vb);
        report.put("statusA", a.path("status").asString());
        report.put("statusB", b.path("status").asString());
        ArrayNode perKey = report.putArray("keys");
        int total = 0;
        int matched = 0;
        int ignored = 0;
        for (String key : keys) {
            Scenario.Tolerance tol = sc.tolerance(key);
            ObjectNode k = perKey.addObject();
            k.put("key", key);
            if (tol.ignore()) {
                k.put("ignored", true);
                ignored++;
                continue;
            }
            TreeMap<Long, List<JsonNode>> ta = sa.getOrDefault(key, new TreeMap<>());
            TreeMap<Long, List<JsonNode>> tb = sb.getOrDefault(key, new TreeMap<>());
            ArrayNode mism = Json.json().createArrayNode();
            int kt = 0;
            int km = 0;
            for (Map.Entry<Long, List<JsonNode>> e : ta.entrySet()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    kt++;
                    JsonNode other = find(tb, e.getKey(), i, e.getValue().get(i), tol);
                    if (other != null) {
                        km++;
                    } else {
                        ObjectNode m = mism.addObject();
                        m.put("tick", e.getKey());
                        m.set("a", e.getValue().get(i));
                        List<JsonNode> near = tb.get(e.getKey());
                        m.set("b", near == null || near.size() <= i ? Json.json().getNodeFactory().stringNode("<no sample>") : near.get(i));
                    }
                }
            }
            for (Map.Entry<Long, List<JsonNode>> e : tb.entrySet()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    if (!hasTick(ta, e.getKey(), i, tol.tickShift())) {
                        kt++;
                        ObjectNode m = mism.addObject();
                        m.put("tick", e.getKey());
                        m.set("a", Json.json().getNodeFactory().stringNode("<no sample>"));
                        m.set("b", e.getValue().get(i));
                    }
                }
            }
            total += kt;
            matched += km;
            k.put("samples", kt);
            k.put("matched", km);
            if (!mism.isEmpty()) {
                ArrayNode first = k.putArray("mismatches");
                Iterator<JsonNode> it = mism.iterator();
                for (int i = 0; i < 10 && it.hasNext(); i++) {
                    first.add(it.next());
                }
            }
        }
        // assertion outcomes must agree too
        List<String> assertDiffs = new ArrayList<>();
        Map<Integer, Boolean> aa = asserts(a);
        Map<Integer, Boolean> ab = asserts(b);
        for (Integer step : new TreeSet<>(aa.keySet())) {
            if (!aa.get(step).equals(ab.get(step))) {
                assertDiffs.add("step " + step + ": " + aa.get(step) + " vs " + ab.get(step));
            }
        }
        ArrayNode ad = report.putArray("assertionDiffs");
        assertDiffs.forEach(ad::add);
        report.put("samples", total);
        report.put("matched", matched);
        report.put("ignoredKeys", ignored);
        boolean equal = matched == total && assertDiffs.isEmpty();
        report.put("equal", equal);
        return new Result(equal, report);
    }

    private static Map<Integer, Boolean> asserts(JsonNode t) {
        Map<Integer, Boolean> m = new TreeMap<>();
        for (JsonNode a : t.path("assertions")) {
            m.put(a.path("step").asInt(), a.path("passed").asBoolean());
        }
        return m;
    }

    /** canonical key -> tick -> values in sampling order (several per tick when sampled before/after an action). */
    private static Map<String, TreeMap<Long, List<JsonNode>>> canonical(Scenario sc, JsonNode trace, String version) {
        Map<String, TreeMap<Long, List<JsonNode>>> m = new TreeMap<>();
        for (JsonNode s : trace.path("samples")) {
            String key = sc.toCanonicalKey(s.path("key").asString(), version);
            m.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(s.path("tick").asLong(), k -> new ArrayList<>()).add(s.path("value"));
        }
        return m;
    }

    private static JsonNode find(TreeMap<Long, List<JsonNode>> series, long tick, int idx, JsonNode value, Scenario.Tolerance tol) {
        for (long t = tick - tol.tickShift(); t <= tick + tol.tickShift(); t++) {
            List<JsonNode> vs = series.get(t);
            JsonNode v = vs == null || vs.size() <= idx ? null : vs.get(idx);
            if (v != null && matches(value, v, tol)) {
                return v;
            }
        }
        return null;
    }

    private static boolean hasTick(TreeMap<Long, List<JsonNode>> series, long tick, int idx, int shift) {
        for (List<JsonNode> vs : series.subMap(tick - shift, true, tick + shift, true).values()) {
            if (vs.size() > idx) {
                return true;
            }
        }
        return false;
    }

    /** Structural equality with numeric tolerance. */
    public static boolean matches(JsonNode a, JsonNode b, Scenario.Tolerance tol) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return tol.numbersMatch(a.asDouble(), b.asDouble());
        }
        if (a.isNull() || b.isNull()) {
            return a.isNull() && b.isNull();
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!matches(a.get(i), b.get(i), tol)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                if (!matches(e.getValue(), b.get(e.getKey()), tol)) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
