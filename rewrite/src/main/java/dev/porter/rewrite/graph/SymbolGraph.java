package dev.porter.rewrite.graph;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The rewriter's view of symbol-graph documents ({@code contracts/symbol-graph.schema.json}): symbols with their
 * names per namespace (used to alias SRG/obf names to canonical ones) and edges / query results (used to generate
 * mechanical rules). Documents are validated against the frozen contract on load.
 */
public final class SymbolGraph {

    /** symbolRef of the contract. {@code owner}/{@code desc} are null where the contract omits them. */
    public record Ref(String version, String kind, String owner, String name, String desc) {
        static Ref of(JsonNode n) {
            if (n == null || n.isMissingNode() || n.isNull()) {
                return null;
            }
            return new Ref(text(n, "version"), text(n, "kind"), text(n, "owner"), text(n, "name"), text(n, "desc"));
        }

        public String display() {
            return switch (kind) {
                case "class" -> name;
                case "field" -> owner + "#" + name;
                default -> owner + "#" + name + desc;
            };
        }
    }

    public record Symbol(Ref ref, Map<String, String> names, Integer access) {
    }

    public record Edge(Ref from, Ref to, String status, double confidence, List<String> evidence, String note) {
    }

    /** A chain query result: path edges, worst status, min confidence, target symbols. */
    public record QueryResult(Ref symbol, String to, List<Edge> path, String status, double confidence, List<Ref> results) {
    }

    /** version id -> canonical namespace. */
    private final Map<String, String> canonical = new TreeMap<>();
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<QueryResult> queries = new ArrayList<>();
    private String keepSymbolsOf;

    /** Loads every {@code *.graph.json} under the given files/directories (sorted, validated). */
    public static SymbolGraph load(List<Path> paths) {
        return load(paths, null);
    }

    /**
     * Like {@link #load(List)} but keeps symbols of one version only (the rewriter needs names of the source version;
     * full graphs carry hundreds of thousands of symbols per version). Edges are always kept.
     */
    public static SymbolGraph load(List<Path> paths, String keepSymbolsOf) {
        SymbolGraph g = new SymbolGraph();
        g.keepSymbolsOf = keepSymbolsOf;
        for (Path p : paths) {
            List<Path> files;
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    files = s.filter(f -> f.getFileName().toString().endsWith(".graph.json")).sorted().toList();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                files = List.of(p);
            }
            for (Path f : files) {
                for (JsonNode doc : ContractIo.readValidated(f, Contract.SYMBOL_GRAPH)) {
                    g.add(doc);
                }
            }
        }
        return g;
    }

    public static SymbolGraph empty() {
        return new SymbolGraph();
    }

    /** Adds one already validated document. */
    public void add(JsonNode doc) {
        if ("graph".equals(text(doc, "type"))) {
            for (JsonNode v : doc.path("versions")) {
                canonical.put(text(v, "id"), text(v, "canonical"));
            }
            for (JsonNode s : doc.path("symbols")) {
                if (keepSymbolsOf != null && !keepSymbolsOf.equals(s.path("ref").path("version").asString())) {
                    continue;
                }
                Map<String, String> names = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : s.path("names").properties()) {
                    names.put(e.getKey(), e.getValue().asString());
                }
                symbols.add(new Symbol(Ref.of(s.path("ref")), names, s.has("access") ? s.get("access").asInt() : null));
            }
            for (JsonNode e : doc.path("edges")) {
                edges.add(edge(e));
            }
        } else {
            List<Edge> path = new ArrayList<>();
            for (JsonNode e : doc.path("path")) {
                path.add(edge(e));
            }
            List<Ref> results = new ArrayList<>();
            for (JsonNode r : doc.path("results")) {
                results.add(Ref.of(r));
            }
            JsonNode q = doc.path("query");
            queries.add(new QueryResult(Ref.of(q.path("symbol")), text(q, "to"), path, text(doc, "status"),
                    doc.path("confidence").asDouble(), results));
        }
    }

    private static Edge edge(JsonNode e) {
        List<String> ev = new ArrayList<>();
        for (JsonNode x : e.path("evidence")) {
            ev.add(x.asString());
        }
        return new Edge(Ref.of(e.path("from")), Ref.of(e.path("to")), text(e, "status"), e.path("confidence").asDouble(), ev,
                text(e, "note"));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    public Map<String, String> canonicalNamespaces() {
        return canonical;
    }

    public List<Symbol> symbols() {
        return symbols;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<QueryResult> queries() {
        return queries;
    }
}
