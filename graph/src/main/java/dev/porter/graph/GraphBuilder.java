package dev.porter.graph;

import dev.porter.core.json.Json;
import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.load.Fetcher;
import dev.porter.graph.load.Sources;
import dev.porter.graph.load.VersionLoader;
import dev.porter.graph.match.AnchorMatcher;
import dev.porter.graph.match.EdgeBuilder;
import dev.porter.graph.match.MatcherBridge;
import dev.porter.graph.match.PairResult;
import dev.porter.graph.model.Edge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** {@code porter graph build}: load versions, match adjacent pairs, write the graph directory. */
public final class GraphBuilder {
    public record Options(Path cacheDir, Path outDir, List<String> versions, boolean offline, boolean matcher) {
    }

    private GraphBuilder() {
    }

    /** Builds the graph and returns build statistics (also written into the manifest). */
    public static Map<String, Object> build(Options o, Consumer<String> log) throws IOException {
        return build(o, Sources.bundled(), log);
    }

    /** Same with an explicit input manifest (tests use synthetic versions). */
    public static Map<String, Object> build(Options o, Sources sources, Consumer<String> log) throws IOException {
        List<Sources.Version> chain = new ArrayList<>();
        for (Sources.Version v : sources.versions()) {
            if (o.versions().contains(v.id())) {
                chain.add(v);
            }
        }
        for (String id : o.versions()) {
            if (sources.version(id).isEmpty()) {
                throw new IllegalArgumentException("unsupported version " + id + "; known: "
                        + sources.versions().stream().map(Sources.Version::id).toList());
            }
        }
        // The selection must be a contiguous slice of the chain: edges only exist between adjacent versions.
        List<String> all = sources.versions().stream().map(Sources.Version::id).toList();
        for (int i = 1; i < chain.size(); i++) {
            if (all.indexOf(chain.get(i).id()) != all.indexOf(chain.get(i - 1).id()) + 1) {
                throw new IllegalArgumentException("versions must be adjacent in the chain " + all + ": "
                        + chain.get(i - 1).id() + " and " + chain.get(i).id() + " are not");
            }
        }
        Files.createDirectories(o.outDir());
        Fetcher fetcher = new Fetcher(o.cacheDir(), o.offline(), log);
        VersionLoader loader = new VersionLoader(fetcher, log);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("graphFormat", 1);
        manifest.put("matcher", o.matcher() ? "fabricmc-matcher-0.1.0+anchors" : "anchors-only");
        List<Map<String, Object>> versionsOut = new ArrayList<>();
        List<Map<String, Object>> pairsOut = new ArrayList<>();
        Map<String, Long> timings = new LinkedHashMap<>();

        VersionLoader.Loaded prev = null;
        for (Sources.Version v : chain) {
            long t0 = System.nanoTime();
            VersionLoader.Loaded cur = loader.load(v, o.outDir().resolve("mappings").resolve(v.id() + ".tiny"));
            GraphFiles.writeSymbols(GraphFiles.symbolsFile(o.outDir(), v.id()), cur.data());
            GraphFiles.writeHierarchy(GraphFiles.hierarchyFile(o.outDir(), v.id()), cur.data());
            timings.put("load " + v.id(), ms(t0));
            Map<String, Object> vj = new LinkedHashMap<>();
            vj.put("id", v.id());
            vj.put("canonical", v.canonical());
            vj.put("namespaces", cur.data().namespaces());
            vj.put("symbols", cur.data().symbols().size());
            vj.put("file", GraphFiles.symbolsFile(o.outDir(), v.id()).getFileName().toString());
            versionsOut.add(vj);
            if (prev != null) {
                t0 = System.nanoTime();
                PairResult r = new PairResult(prev.data(), cur.data());
                AnchorMatcher am = new AnchorMatcher(r);
                am.run();
                int anchors = r.size();
                log.accept(prev.data().id() + " -> " + v.id() + ": " + anchors + " anchor matches (" + am.mode() + ")");
                Map<String, Object> pj = new LinkedHashMap<>();
                pj.put("newer", prev.data().id());
                pj.put("older", v.id());
                pj.put("anchorMode", am.mode().name().toLowerCase(java.util.Locale.ROOT));
                pj.put("anchorMatches", anchors);
                if (o.matcher()) {
                    MatcherBridge.Stats st = MatcherBridge.run(r, prev.jar(), cur.jar(), log);
                    log.accept("  matcher: +" + st.newClasses() + " classes, +" + st.newMembers() + " members, "
                            + st.bytecodeConfirmed() + " anchors confirmed, " + st.bytecodeDisputed() + " disputed");
                    pj.put("matcher", st);
                }
                List<Edge> edges = EdgeBuilder.edges(r);
                Path ef = GraphFiles.edgesFile(o.outDir(), prev.data().id(), v.id());
                GraphFiles.writeEdges(ef, prev.data(), cur.data(), edges);
                Map<String, Integer> byStatus = new TreeMap<>();
                for (Edge e : edges) {
                    byStatus.merge(e.from().kind().json + ":" + e.status().json, 1, Integer::sum);
                }
                pj.put("edges", edges.size());
                pj.put("byStatus", byStatus);
                pj.put("byEvidence", new TreeMap<>(r.stats));
                pj.put("file", ef.getFileName().toString());
                pairsOut.add(pj);
                timings.put("match " + prev.data().id() + "->" + v.id(), ms(t0));
            }
            prev = cur;
        }
        manifest.put("versions", versionsOut);
        manifest.put("pairs", pairsOut);
        Files.writeString(GraphFiles.manifestFile(o.outDir()),
                Json.json().writeValueAsString(manifest).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        Map<String, Object> stats = new LinkedHashMap<>(manifest);
        stats.put("timingsMs", timings);
        return stats;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
