package dev.porter.loop.graph;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * Looks symbols up in the graph module's output (symbol-graph contract, {@code type: graph} documents with
 * {@code edges} between adjacent versions, e.g. {@code $PORTER_CACHE/graph/out}). Edge files are streamed
 * edge by edge (they are tens of MB), one pass per hop along the version chain; only the queried symbols are kept.
 *
 * <p>Queries are by class name, or by owner + member name (javac does not tell the descriptor; all overloads are
 * returned). The result is the per-symbol status: worst along the path, minimum confidence, final names.
 */
public final class GraphLookup {
    private static final List<String> ORDER = List.of("same", "renamed", "moved", "signature_changed", "split", "merged", "removed");

    /** Lookup result for one query. */
    public record Status(String query, String status, double confidence, List<String> path, List<String> results, String note) {
    }

    private record Hop(String from, String to, Path file) {
    }

    private final List<Hop> hops = new ArrayList<>();

    private GraphLookup() {
    }

    /**
     * Prepares a lookup over every {@code *.graph.json} edge document under {@code dir} (or the single file), chained
     * from {@code fromVersion} to {@code toVersion} (bare Minecraft versions, e.g. 1.19.2 -> 1.7.10).
     *
     * @return the lookup, or null when no chain between the versions exists
     */
    public static GraphLookup open(Path dirOrFile, String fromVersion, String toVersion) {
        Map<String, Hop> byFrom = new HashMap<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dirOrFile)) {
            try (Stream<Path> w = Files.list(dirOrFile)) {
                w.filter(p -> p.getFileName().toString().endsWith(".graph.json")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            files.add(dirOrFile);
        }
        Path manifest = Files.isDirectory(dirOrFile) ? dirOrFile.resolve("manifest.json") : null;
        if (manifest != null && Files.isRegularFile(manifest)) {
            // graph module output: pairs[{newer, older, file}] (edges go newer -> older)
            JsonNode m = Json.json().readTree(manifest.toFile());
            for (JsonNode pair : m.path("pairs")) {
                Path f = dirOrFile.resolve(pair.path("file").asString());
                if (Files.isRegularFile(f)) {
                    byFrom.putIfAbsent(pair.path("newer").asString(), new Hop(pair.path("newer").asString(), pair.path("older").asString(), f));
                }
            }
            files.clear();
        }
        for (Path f : files) {
            if (f.getFileName().toString().startsWith("symbols-")) {
                continue;
            }
            String[] pair = firstEdgeVersions(f);
            if (pair != null) {
                byFrom.putIfAbsent(pair[0], new Hop(pair[0], pair[1], f));
            }
        }
        GraphLookup g = new GraphLookup();
        String cur = fromVersion;
        while (!cur.equals(toVersion)) {
            Hop h = byFrom.get(cur);
            if (h == null || g.hops.size() > 16) {
                return null;
            }
            g.hops.add(h);
            cur = h.to();
        }
        return g;
    }

    public List<String> chain() {
        List<String> out = new ArrayList<>();
        for (Hop h : hops) {
            if (out.isEmpty()) {
                out.add(h.from());
            }
            out.add(h.to());
        }
        return out;
    }

    /** A query: class name (owner == null) or owner + member. Names are binary, in the from-version's canonical names. */
    public record Query(String owner, String name) {
        public static Query parse(String symbol) {
            String s = symbol.replaceAll("\\(.*$", "");
            int h = s.indexOf('#');
            return h < 0 ? new Query(null, s) : new Query(s.substring(0, h), s.substring(h + 1));
        }

        String label() {
            return owner == null ? name : owner + "#" + name;
        }
    }

    /** Runs all queries at once (one streaming pass per hop). */
    public Map<String, Status> lookup(Set<Query> queries) {
        // frontier: query label -> current refs (as Query in the current version), status/conf/path so far
        Map<String, List<Query>> frontier = new TreeMap<>();
        Map<String, String> status = new HashMap<>();
        Map<String, Double> conf = new HashMap<>();
        Map<String, List<String>> path = new HashMap<>();
        Map<String, String> note = new HashMap<>();
        for (Query q : queries) {
            frontier.put(q.label(), new ArrayList<>(List.of(q)));
            status.put(q.label(), "same");
            conf.put(q.label(), 1.0);
            path.put(q.label(), new ArrayList<>());
        }
        Set<String> found = new java.util.HashSet<>();
        for (Hop hop : hops) {
            Map<String, List<String>> wanted = new HashMap<>(); // ref key -> query labels
            for (Map.Entry<String, List<Query>> e : frontier.entrySet()) {
                for (Query q : e.getValue()) {
                    wanted.computeIfAbsent(q.label(), k -> new ArrayList<>()).add(e.getKey());
                }
            }
            Map<String, List<Query>> next = new TreeMap<>();
            Map<String, Boolean> matched = new HashMap<>();
            streamEdges(hop.file(), edge -> {
                JsonNode from = edge.get("from");
                String key = refKey(from);
                List<String> labels = wanted.get(key);
                if (labels == null) {
                    return;
                }
                String st = edge.path("status").asString();
                double c = edge.path("confidence").asDouble(1.0);
                JsonNode to = edge.get("to");
                for (String label : labels) {
                    matched.put(label, true);
                    if (ORDER.indexOf(st) > ORDER.indexOf(status.get(label))) {
                        status.put(label, st);
                    }
                    conf.put(label, Math.min(conf.get(label), c));
                    path.get(label).add(hop.from() + "->" + hop.to() + " " + st + " " + key + (to == null ? "" : " => " + refKey(to)));
                    if (to != null) {
                        Query nq = to.has("owner") ? new Query(to.get("owner").asString(), to.get("name").asString())
                                : new Query(null, to.get("name").asString());
                        List<Query> l = next.computeIfAbsent(label, k -> new ArrayList<>());
                        if (!l.contains(nq)) {
                            l.add(nq);
                        }
                    } else if (edge.has("note")) {
                        note.put(label, edge.get("note").asString());
                    }
                }
            });
            for (String label : frontier.keySet()) {
                if (!matched.containsKey(label) && !"removed".equals(status.get(label))) {
                    if (!found.contains(label)) {
                        note.putIfAbsent(label, "not in graph at " + hop.from());
                    } else {
                        note.putIfAbsent(label, "chain broken at " + hop.from());
                    }
                    status.put(label, "unknown");
                }
            }
            found.addAll(matched.keySet());
            frontier = next;
        }
        Map<String, Status> out = new LinkedHashMap<>();
        for (Query q : queries.stream().sorted((a, b) -> a.label().compareTo(b.label())).toList()) {
            String label = q.label();
            List<String> results = frontier.getOrDefault(label, List.of()).stream().map(Query::label).sorted().toList();
            out.put(label, new Status(label, status.get(label), conf.get(label), List.copyOf(path.get(label)), results, note.get(label)));
        }
        return out;
    }

    private static String refKey(JsonNode ref) {
        if (ref == null) {
            return "";
        }
        return ref.has("owner") ? ref.get("owner").asString() + "#" + ref.path("name").asString() : ref.path("name").asString();
    }

    private static String[] firstEdgeVersions(Path f) {
        String[][] out = new String[1][];
        try {
            streamEdges(f, new java.util.function.Consumer<>() {
                @Override
                public void accept(JsonNode edge) {
                    if (out[0] == null && edge.has("to")) {
                        out[0] = new String[] {edge.get("from").path("version").asString(), edge.get("to").path("version").asString()};
                        throw new StopStreaming();
                    }
                }
            });
        } catch (StopStreaming s) {
            // done
        }
        return out[0];
    }

    private static final class StopStreaming extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopStreaming() {
            super(null, null, false, false);
        }
    }

    private static void streamEdges(Path f, java.util.function.Consumer<JsonNode> sink) {
        try (JsonParser p = Json.json().createParser(f.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return;
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken t = p.nextToken();
                if ("edges".equals(field) && t == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode edge = p.readValueAsTree();
                        sink.accept(edge);
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
    }
}
