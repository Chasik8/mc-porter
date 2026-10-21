package dev.porter.graph.query;

import dev.porter.core.json.Json;
import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.model.Edge;
import dev.porter.graph.model.Sym;
import dev.porter.graph.model.SymbolRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * Read side of a built graph directory (see {@link GraphFiles}). Thread-safe after loading; files are loaded lazily
 * (a symbols file when a version is first resolved against, an edges file when a query first crosses that pair).
 *
 * <pre>
 * SymbolGraph g = SymbolGraph.open(Path.of(System.getenv("PORTER_CACHE"), "graph/out"));
 * for (SymbolRef r : g.resolve("1.19.2", "net.minecraft.world.level.Level#getBlockEntity")) {
 *     QueryResult q = g.query(r, "1.7.10");
 * }
 * </pre>
 */
public final class SymbolGraph {
    /** A symbol as stored in a symbols file. */
    public record SymbolInfo(SymbolRef ref, Map<String, String> names, List<Sym.Param> params, int access) {
    }

    private final Path dir;
    /** Version chain, newest first. */
    private final List<String> chain = new ArrayList<>();
    private final Map<String, String> canonical = new HashMap<>();
    private final Map<String, List<SymbolInfo>> symbols = new HashMap<>();
    private final Map<String, Map<String, SymbolInfo>> symbolsByKey = new HashMap<>();
    /** "newer->older" -> from-key -> edges (forward), and older-key -> inverted edges (backward). */
    private final Map<String, Map<String, List<Edge>>> down = new HashMap<>();
    private final Map<String, Map<String, List<Edge>>> up = new HashMap<>();

    private SymbolGraph(Path dir) {
        this.dir = dir;
    }

    public static SymbolGraph open(Path dir) {
        SymbolGraph g = new SymbolGraph(dir);
        try {
            JsonNode m = Json.json().readTree(Files.readString(GraphFiles.manifestFile(dir)));
            for (JsonNode v : m.get("versions")) {
                g.chain.add(v.get("id").asString());
                g.canonical.put(v.get("id").asString(), v.get("canonical").asString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read graph manifest in " + dir, e);
        }
        return g;
    }

    public List<String> versions() {
        return List.copyOf(chain);
    }

    public static String key(SymbolRef r) {
        return r.version() + "|" + r.kind().json + "|" + (r.owner() == null ? "" : r.owner()) + "|" + r.name() + "|"
                + (r.desc() == null || r.kind() == Sym.Kind.FIELD ? "" : r.desc());
    }

    public synchronized List<SymbolInfo> symbols(String version) {
        List<SymbolInfo> list = symbols.get(version);
        if (list == null) {
            List<SymbolInfo> out = new ArrayList<>();
            Map<String, SymbolInfo> byKey = new HashMap<>();
            stream(GraphFiles.symbolsFile(dir, version), "symbols", n -> {
                SymbolInfo s = symbolInfo(n);
                out.add(s);
                byKey.put(key(s.ref()), s);
            });
            symbols.put(version, out);
            symbolsByKey.put(version, byKey);
            list = out;
        }
        return list;
    }

    public SymbolInfo info(SymbolRef r) {
        symbols(r.version());
        return symbolsByKey.get(r.version()).get(key(r));
    }

    private static SymbolInfo symbolInfo(JsonNode n) {
        Map<String, String> names = new LinkedHashMap<>();
        n.get("names").properties().forEach(e -> names.put(e.getKey(), e.getValue().asString()));
        List<Sym.Param> params = new ArrayList<>();
        if (n.has("params")) {
            for (JsonNode p : n.get("params")) {
                params.add(new Sym.Param(p.get("index").asInt(), p.get("name").asString(),
                        p.has("source") ? p.get("source").asString() : null));
            }
        }
        return new SymbolInfo(ref(n.get("ref")), names, params, n.path("access").asInt(0));
    }

    static SymbolRef ref(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        return new SymbolRef(n.get("version").asString(), Sym.Kind.of(n.get("kind").asString()),
                n.has("owner") ? n.get("owner").asString() : null, n.get("name").asString(),
                n.has("desc") ? n.get("desc").asString() : null);
    }

    static Edge edge(JsonNode n) {
        List<String> ev = new ArrayList<>();
        if (n.has("evidence")) {
            n.get("evidence").forEach(x -> ev.add(x.asString()));
        }
        return new Edge(ref(n.get("from")), ref(n.get("to")), Edge.Status.of(n.get("status").asString()),
                n.get("confidence").asDouble(), List.copyOf(ev), n.has("note") ? n.get("note").asString() : null);
    }

    private synchronized void loadPair(String newer, String older) {
        String k = newer + "->" + older;
        if (down.containsKey(k)) {
            return;
        }
        Map<String, List<Edge>> fwd = new HashMap<>();
        Map<String, List<Edge>> bwd = new HashMap<>();
        stream(GraphFiles.edgesFile(dir, newer, older), "edges", n -> {
            Edge e = edge(n);
            fwd.computeIfAbsent(key(e.from()), x -> new ArrayList<>(1)).add(e);
            if (e.to() != null) {
                bwd.computeIfAbsent(key(e.to()), x -> new ArrayList<>(1)).add(e.inverted());
            }
        });
        down.put(k, fwd);
        up.put(k, bwd);
    }

    /** Streams the elements of one top-level array of a (large) graph document. */
    private static void stream(Path file, String array, Consumer<JsonNode> sink) {
        try (InputStream in = Files.newInputStream(file); JsonParser p = GraphFiles.COMPACT.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException(file + ": not a JSON object");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                p.nextToken();
                if (name.equals(array)) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        sink.accept(GraphFiles.COMPACT.readTree(p));
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Edges leaving {@code r} towards the adjacent version {@code next}. */
    public List<Edge> step(SymbolRef r, String next) {
        int i = chain.indexOf(r.version());
        int j = chain.indexOf(next);
        if (i < 0 || j < 0 || Math.abs(i - j) != 1) {
            throw new IllegalArgumentException(r.version() + " and " + next + " are not adjacent in " + chain);
        }
        if (j > i) {
            loadPair(r.version(), next);
            return down.get(r.version() + "->" + next).getOrDefault(key(r), List.of());
        }
        loadPair(next, r.version());
        List<Edge> es = up.get(next + "->" + r.version()).getOrDefault(key(r), List.of());
        if (es.isEmpty() && info(r) != null) {
            return List.of(new Edge(r, null, Edge.Status.REMOVED, 0.7, List.of("no-match"),
                    "no counterpart found in " + next));
        }
        return es;
    }

    /** Follows the chain from {@code start.version()} to {@code to}, through every branch. */
    public QueryResult query(SymbolRef start, String to) {
        int i = chain.indexOf(start.version());
        int j = chain.indexOf(to);
        if (i < 0 || j < 0) {
            throw new IllegalArgumentException("unknown version; graph has " + chain);
        }
        if (info(start) == null) {
            return QueryResult.missing(start, to, start.version(), "symbol not found in the graph of " + start.version());
        }
        List<Edge> path = new ArrayList<>();
        Edge.Status status = Edge.Status.SAME;
        double conf = 1.0;
        Set<SymbolRef> frontier = new LinkedHashSet<>(List.of(start));
        int dir = Integer.compare(j, i);
        for (int k = i; k != j && !frontier.isEmpty(); k += dir) {
            String next = chain.get(k + dir);
            Set<SymbolRef> nf = new LinkedHashSet<>();
            for (SymbolRef s : frontier) {
                List<Edge> es = step(s, next);
                if (es.isEmpty()) {
                    return QueryResult.missing(start, to, s.version(), "no edge for " + s.display() + " towards " + next);
                }
                for (Edge e : es) {
                    path.add(e);
                    status = Edge.Status.worst(status, e.status());
                    conf = Math.min(conf, e.confidence());
                    if (e.to() != null) {
                        nf.add(e.to());
                    }
                }
            }
            frontier = nf;
        }
        if (i == j) {
            return new QueryResult(start, to, List.of(), Edge.Status.SAME, 1.0, List.of(start), null);
        }
        return new QueryResult(start, to, path, status, conf, List.copyOf(frontier), null);
    }

    private final Map<String, Map<String, List<String>>> supertypes = new HashMap<>();

    /**
     * Direct super types (super class first, then interfaces) of a class, canonical binary names; empty if unknown.
     * Read from {@code hierarchy-<v>.tsv} (not a contract).
     */
    public synchronized List<String> supertypes(String version, String className) {
        Map<String, List<String>> m = supertypes.get(version);
        if (m == null) {
            m = new HashMap<>();
            try {
                for (String line : Files.readAllLines(GraphFiles.hierarchyFile(dir, version))) {
                    String[] p = line.split("\t", -1);
                    List<String> l = new ArrayList<>();
                    if (p.length > 1 && !p[1].isEmpty()) {
                        l.add(p[1]);
                    }
                    if (p.length > 2 && !p[2].isEmpty()) {
                        l.addAll(List.of(p[2].split(",")));
                    }
                    m.put(p[0], List.copyOf(l));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            supertypes.put(version, m);
        }
        return m.getOrDefault(className, List.of());
    }

    /** Resolves a user symbol spec in one version; see {@link SymbolSpec}. */
    public List<SymbolRef> resolve(String version, String spec) {
        return SymbolSpec.parse(spec).resolve(symbols(version));
    }
}
