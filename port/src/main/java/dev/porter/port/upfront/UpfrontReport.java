package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one metric of {@code porter upfront}: how many of the symbols of the WP1 inventory (every distinct 1.19.2 platform
 * symbol the mod references, bytecode first) have a 1.7.10 answer, before upfront (hand rules and graph rules) and after it
 * (plus emitted rules, concepts, flattening, mirror/shim members, bodies). Every inventory symbol falls into exactly one
 * bucket, so the numbers add up. {@code porter coverage} counts only the symbols found in the sources; the same table is
 * therefore also reported for the source-origin subset, which is the coverage universe.
 */
public final class UpfrontReport {
    /** Buckets that count as resolved before upfront. */
    public static final List<String> BEFORE = List.of("hand rules", "graph rules");
    /** Buckets upfront adds. */
    public static final List<String> ADDED = List.of("emitted map rules", "concepts (expand rules)", "flattening (registry rules)",
            "mirror (shim types and members)", "shim delegates", "bodies DB or existing shim");
    /** Buckets that remain open after upfront. */
    public static final List<String> OPEN = List.of("todo (body needed)", "registry (no 1.7.10 counterpart)", "emitter skipped, no shim");

    private UpfrontReport() {
    }

    /** Bucket counts of the rows accepted by {@code in}, all buckets present, in report order. */
    public static Map<String, Integer> buckets(MatchTable table, Set<String> todoKeys, Set<String> skippedKeys, Predicate<MatchRow> in) {
        Map<String, Integer> b = new LinkedHashMap<>();
        BEFORE.forEach(k -> b.put(k, 0));
        ADDED.forEach(k -> b.put(k, 0));
        OPEN.forEach(k -> b.put(k, 0));
        for (MatchRow r : table.rows()) {
            if (!in.test(r)) {
                continue;
            }
            String key = r.key().toString();
            String bucket;
            if ("rule".equals(r.resolvedBy())) {
                bucket = "hand rules";
            } else if ("graph".equals(r.resolvedBy())) {
                bucket = "graph rules";
            } else if (todoKeys.contains(key)) {
                bucket = "todo (body needed)";
            } else if (skippedKeys.contains(key) && r.plan() == Plan.REGISTRY) {
                bucket = "registry (no 1.7.10 counterpart)";
            } else if (skippedKeys.contains(key) && (r.plan() == Plan.MAP || r.plan() == Plan.EXPAND)) {
                bucket = r.shim() != null && !r.shim().isBlank() ? "shim delegates" : "emitter skipped, no shim";
            } else {
                bucket = switch (r.plan()) {
                    case MAP -> "emitted map rules";
                    case EXPAND -> "concepts (expand rules)";
                    case REGISTRY -> "flattening (registry rules)";
                    case MIRROR -> "mirror (shim types and members)";
                    case HINT, TODO -> "bodies DB or existing shim";
                };
            }
            b.merge(bucket, 1, Integer::sum);
        }
        return b;
    }

    static int sum(Map<String, Integer> b, List<String> keys) {
        return keys.stream().mapToInt(b::get).sum();
    }

    /** The text report. {@code byItself} is the MatchEval result (may be null: no hand rules given). */
    public static List<String> render(String scope, Map<String, Integer> all, Map<String, Integer> src, MatchEval.Result byItself,
            Map<String, Integer> todoByPlan) {
        List<String> out = new ArrayList<>();
        int nAll = sum(all, BEFORE) + sum(all, ADDED) + sum(all, OPEN);
        int nSrc = sum(src, BEFORE) + sum(src, ADDED) + sum(src, OPEN);
        out.add(String.format(Locale.ROOT, "upfront report (%s): %d inventory symbols, %d of them found in the sources (the 'porter coverage' universe)", scope, nAll, nSrc));
        out.add(String.format(Locale.ROOT, "%-40s %16s %26s", "", "all inventory", "source-origin subset"));
        row(out, "before upfront (hand + graph rules)", sum(all, BEFORE), nAll, sum(src, BEFORE), nSrc);
        for (String k : BEFORE) {
            line(out, k, all.get(k), nAll, src.get(k), nSrc);
        }
        for (String k : ADDED) {
            line(out, "+ " + k, all.get(k), nAll, src.get(k), nSrc);
        }
        row(out, "after upfront (resolved)", sum(all, BEFORE) + sum(all, ADDED), nAll, sum(src, BEFORE) + sum(src, ADDED), nSrc);
        for (String k : OPEN) {
            line(out, "- " + k, all.get(k), nAll, src.get(k), nSrc);
        }
        row(out, "remaining open", sum(all, OPEN), nAll, sum(src, OPEN), nSrc);
        out.add("remaining TODO bodies by plan: " + todoByPlan);
        if (byItself != null) {
            out.add(String.format(Locale.ROOT, "hand rules upfront would produce by itself: %d of %d (%.0f%%) rules, %d of %d (%.0f%%) hand-resolved symbols",
                    byItself.rulesByItself(), byItself.handRules(), pct(byItself.rulesByItself(), byItself.handRules()), byItself.rowsByItself(),
                    byItself.handRows(), pct(byItself.rowsByItself(), byItself.handRows())));
            if (byItself.scopeRules() > 0) {
                out.add(String.format(Locale.ROOT, "of the %d profile hand rules: %d used by the inventory, %d (%.0f%% of all, %.0f%% of the used) produced by upfront alone",
                        byItself.scopeRules(), byItself.scopeUsed(), byItself.scopeByItself(), pct(byItself.scopeByItself(), byItself.scopeRules()),
                        pct(byItself.scopeByItself(), byItself.scopeUsed())));
            }
        }
        return out;
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    private static void row(List<String> out, String label, int a, int na, int b, int nb) {
        out.add(String.format(Locale.ROOT, "%-40s %8d %6.1f%% %14d %6.1f%%", label, a, pct(a, na), b, pct(b, nb)));
    }

    private static void line(List<String> out, String label, int a, int na, int b, int nb) {
        out.add(String.format(Locale.ROOT, "  %-38s %8d %6.1f%% %14d %6.1f%%", label, a, pct(a, na), b, pct(b, nb)));
    }

    /** Builds the report for a finished run directory; writes {@code upfront-report.txt} and {@code .json}. */
    public static List<String> write(String scope, MatchTable table, ApiInventory inv, Path todoTsv, Path skippedTsv, MatchEval.Result byItself,
            Path outDir) throws IOException {
        Set<String> todo = new HashSet<>();
        Map<String, Integer> todoByPlan = new LinkedHashMap<>();
        if (Files.exists(todoTsv)) {
            for (TodoEntry e : TodoEntry.read(todoTsv)) {
                todo.add(e.key().toString());
                todoByPlan.merge(e.plan().name(), 1, Integer::sum);
            }
        }
        Set<String> skipped = new HashSet<>();
        if (Files.exists(skippedTsv)) {
            for (String l : Files.readAllLines(skippedTsv, StandardCharsets.UTF_8)) {
                int t = l.indexOf('\t');
                if (t > 0) {
                    skipped.add(l.substring(0, t));
                }
            }
        }
        Set<String> source = new HashSet<>();
        for (ApiSymbol s : inv.symbols()) {
            if (s.origin().contains("source")) {
                source.add(s.key().toString());
            }
        }
        Map<String, Integer> all = buckets(table, todo, skipped, r -> true);
        Map<String, Integer> src = buckets(table, todo, skipped, r -> source.contains(r.key().toString()));
        List<String> lines = render(scope, all, src, byItself, todoByPlan);
        Files.writeString(outDir.resolve("upfront-report.txt"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scope", scope);
        json.put("inventory", all);
        json.put("sourceOrigin", src);
        json.put("todoByPlan", todoByPlan);
        if (byItself != null) {
            Map<String, Object> bi = new HashMap<>();
            bi.put("handRules", byItself.handRules());
            bi.put("rulesByItself", byItself.rulesByItself());
            bi.put("handRows", byItself.handRows());
            bi.put("rowsByItself", byItself.rowsByItself());
            json.put("handRulesByItself", new java.util.TreeMap<>(bi));
        }
        Files.writeString(outDir.resolve("upfront-report.json"), Json.json().writeValueAsString(json) + "\n", StandardCharsets.UTF_8);
        return lines;
    }
}
