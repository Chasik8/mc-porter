package dev.porter.loop.graph;

import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.model.SymbolRef;
import dev.porter.graph.query.QueryResult;
import dev.porter.graph.query.SymbolGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Symbol-graph status for the symbols of escalated errors (task context). Two sources:
 * <ul>
 *   <li>a graph output directory (with {@code manifest.json}): the graph module's {@link SymbolGraph} API, which
 *       resolves names in every namespace of the version and simple owner names;</li>
 *   <li>any other directory or file with {@code type: graph} edge documents: {@link GraphLookup} (streaming).</li>
 * </ul>
 */
public interface GraphSource {
    /** symbol -> JSON-able status ({@code status} key always present). */
    Map<String, Object> lookupAll(Collection<String> symbols);

    /** Opens the right source for {@code dirOrFile}, or null when no chain between the versions exists. */
    static GraphSource open(Path dirOrFile, String fromVersion, String toVersion) {
        if (Files.isDirectory(dirOrFile) && Files.isRegularFile(GraphFiles.manifestFile(dirOrFile))) {
            SymbolGraph g = SymbolGraph.open(dirOrFile);
            if (!g.versions().contains(fromVersion) || !g.versions().contains(toVersion)) {
                return null;
            }
            return symbols -> official(g, symbols, fromVersion, toVersion);
        }
        GraphLookup l = GraphLookup.open(dirOrFile, fromVersion, toVersion);
        if (l == null) {
            return null;
        }
        return symbols -> {
            Set<GraphLookup.Query> qs = new LinkedHashSet<>();
            for (String s : symbols) {
                qs.add(GraphLookup.Query.parse(s));
            }
            Map<String, GraphLookup.Status> r = l.lookup(qs);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String s : symbols) {
                GraphLookup.Query q = GraphLookup.Query.parse(s);
                GraphLookup.Status st = r.get(q.owner() == null ? q.name() : q.owner() + "#" + q.name());
                out.put(s, st == null ? Map.of("status", "not-queried") : st);
            }
            return out;
        };
    }

    private static Map<String, Object> official(SymbolGraph g, Collection<String> symbols, String from, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : symbols) {
            String spec = s.replaceAll("\\(.*$", ""); // javac reports argument types, not declared parameters
            List<SymbolRef> refs;
            try {
                refs = g.resolve(from, spec);
            } catch (RuntimeException e) {
                refs = List.of();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            if (refs.isEmpty()) {
                m.put("status", "not-in-graph");
                m.put("note", "no symbol " + spec + " in " + from);
                out.put(s, m);
                continue;
            }
            List<Object> results = new ArrayList<>();
            String worst = null;
            for (SymbolRef r : refs.subList(0, Math.min(3, refs.size()))) {
                QueryResult q = g.query(r, to);
                Map<String, Object> j = q.toJson();
                results.add(j);
                String st = String.valueOf(j.get("status"));
                if (worst == null || rank(st) > rank(worst)) {
                    worst = st;
                }
            }
            m.put("status", worst);
            m.put("matches", refs.size());
            m.put("queries", results);
            out.put(s, m);
        }
        return out;
    }

    private static int rank(String status) {
        return List.of("same", "renamed", "moved", "signature_changed", "split", "merged", "removed").indexOf(status);
    }
}
