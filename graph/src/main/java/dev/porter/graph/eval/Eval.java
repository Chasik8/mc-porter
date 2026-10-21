package dev.porter.graph.eval;

import dev.porter.core.json.Json;
import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.model.Edge;
import dev.porter.graph.model.Sym;
import dev.porter.graph.model.SymbolRef;
import dev.porter.graph.query.QueryResult;
import dev.porter.graph.query.SymbolGraph;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Evaluation of a built graph (not part of the CLI surface; run with {@code java -cp <porter lib/*> dev.porter.graph.eval.Eval}).
 *
 * <pre>
 * golden   &lt;graph-dir&gt; &lt;golden.json&gt; [report.json]
 * coverage &lt;graph-dir&gt; &lt;from&gt; &lt;to&gt; &lt;mod.jar&gt; &lt;out-dir&gt; [focus-regex]
 * </pre>
 */
public final class Eval {
    private Eval() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 3 && args[0].equals("golden")) {
            Map<String, Object> r = golden(SymbolGraph.open(Path.of(args[1])), Path.of(args[2]));
            String s = Json.json().writeValueAsString(r).replace("\r\n", "\n");
            if (args.length > 3) {
                Files.writeString(Path.of(args[3]), s + "\n", StandardCharsets.UTF_8);
            }
            System.out.println(s);
        } else if (args.length >= 6 && args[0].equals("coverage")) {
            Map<String, Object> r = coverage(SymbolGraph.open(Path.of(args[1])), args[2], args[3], Path.of(args[4]),
                    Path.of(args[5]), args.length > 6 ? Pattern.compile(args[6]) : null);
            System.out.println(Json.json().writeValueAsString(r).replace("\r\n", "\n"));
        } else {
            System.err.println("usage: golden <graph> <golden.json> [out] | coverage <graph> <from> <to> <jar> <out-dir> [focus]");
            System.exit(2);
        }
    }

    /**
     * Golden check: an entry is correct when the query's results contain the expected symbol (or, for to=null, the
     * status is removed). precision = correct / answered, answered = query returned a result or "removed".
     */
    public static Map<String, Object> golden(SymbolGraph g, Path goldenFile) throws IOException {
        JsonNode doc = Json.json().readTree(Files.readString(goldenFile));
        int total = 0;
        int answered = 0;
        int correct = 0;
        Map<String, int[]> byCat = new TreeMap<>();
        List<Map<String, Object>> wrong = new ArrayList<>();
        for (JsonNode e : doc.get("entries")) {
            total++;
            SymbolRef from = ref(e.get("from"));
            SymbolRef expect = e.get("to") == null || e.get("to").isNull() ? null : ref(e.get("to"));
            QueryResult q = g.query(from, "1.7.10");
            boolean ans = q.missing() == null && (!q.results().isEmpty() || q.status() == Edge.Status.REMOVED);
            boolean ok;
            if (expect == null) {
                ok = q.missing() == null && q.results().isEmpty() && q.status() == Edge.Status.REMOVED;
            } else {
                ok = q.results().stream().anyMatch(r -> sameSymbol(r, expect));
            }
            int[] c = byCat.computeIfAbsent(e.get("category").asString(), k -> new int[3]);
            c[0]++;
            if (ans) {
                answered++;
                c[1]++;
            }
            if (ok) {
                correct++;
                c[2]++;
            } else {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("id", e.get("id").asString());
                w.put("from", from.display());
                w.put("expected", expect == null ? "removed" : expect.display());
                w.put("got", q.results().stream().map(SymbolRef::display).toList());
                w.put("status", q.missing() != null ? "missing: " + q.missing().reason() : q.status().json);
                w.put("chain", e.path("chain").asString());
                wrong.add(w);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("answered", answered);
        out.put("correct", correct);
        out.put("precision", answered == 0 ? 0 : round((double) correct / answered));
        out.put("recall", round((double) correct / total));
        Map<String, Object> cats = new TreeMap<>();
        byCat.forEach((k, v) -> cats.put(k, Map.of("total", v[0], "answered", v[1], "correct", v[2])));
        out.put("byCategory", cats);
        out.put("wrong", wrong);
        return out;
    }

    private static boolean sameSymbol(SymbolRef a, SymbolRef b) {
        if (a.kind() != b.kind() || !a.version().equals(b.version()) || !a.name().equals(b.name())) {
            return false;
        }
        if (a.kind() == Sym.Kind.CLASS) {
            return true;
        }
        return a.owner().equals(b.owner()) && (a.kind() == Sym.Kind.FIELD || b.desc() == null || b.desc().equals(a.desc()));
    }

    /** Coverage of the Minecraft symbols a mod jar uses: how many reach the target version. */
    public static Map<String, Object> coverage(SymbolGraph g, String from, String to, Path jar, Path outDir, Pattern focus)
            throws IOException {
        UsedSymbols.Result used = new UsedSymbols(g, from).scan(jar);
        Files.createDirectories(outDir);
        String tag = jar.getFileName().toString().replaceAll("\\.jar$", "") + "__" + from + "__" + to;
        Map<String, int[]> counts = new TreeMap<>();
        try (Writer w = Files.newBufferedWriter(outDir.resolve(tag + ".queries.jsonl"), StandardCharsets.UTF_8)) {
            for (UsedSymbols.Use u : used.uses().values()) {
                QueryResult q = g.query(u.ref(), to);
                String outcome = q.missing() != null ? "missing" : !q.results().isEmpty() ? "mapped" : "removed";
                boolean inFocus = focus != null && u.usedBy().stream().anyMatch(c -> focus.matcher(c).find());
                for (String bucket : inFocus ? List.of("all", "focus") : List.of("all")) {
                    String k = bucket + ":" + u.ref().kind().json;
                    int[] c = counts.computeIfAbsent(k, x -> new int[4]);
                    c[0]++;
                    c[outcome.equals("mapped") ? 1 : outcome.equals("removed") ? 2 : 3]++;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("symbol", u.ref().display());
                line.put("override", u.override());
                line.put("focus", inFocus);
                line.put("outcome", outcome);
                line.put("status", q.status().json);
                line.put("confidence", q.confidence());
                line.put("results", q.results().stream().map(SymbolRef::display).toList());
                line.put("usedBy", u.usedBy().size());
                w.write(GraphFiles.COMPACT.writeValueAsString(line));
                w.write('\n');
            }
        }
        Files.write(outDir.resolve(tag + ".unresolved.txt"), used.unresolved(), StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jar", jar.getFileName().toString());
        out.put("from", from);
        out.put("to", to);
        out.put("unresolvedReferences", used.unresolved().size());
        Map<String, Object> c = new TreeMap<>();
        counts.forEach((k, v) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("used", v[0]);
            x.put("mapped", v[1]);
            x.put("removed", v[2]);
            x.put("missing", v[3]);
            x.put("mappedRatio", round((double) v[1] / v[0]));
            x.put("answeredRatio", round((double) (v[1] + v[2]) / v[0]));
            c.put(k, x);
        });
        out.put("counts", c);
        Files.writeString(outDir.resolve(tag + ".summary.json"), Json.json().writeValueAsString(out).replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8);
        return out;
    }

    private static SymbolRef ref(JsonNode n) {
        return new SymbolRef(n.get("version").asString(), Sym.Kind.of(n.get("kind").asString()),
                n.has("owner") ? n.get("owner").asString() : null, n.get("name").asString(),
                n.has("desc") ? n.get("desc").asString() : null);
    }

    private static double round(double d) {
        return Math.round(d * 10000) / 10000.0;
    }
}
