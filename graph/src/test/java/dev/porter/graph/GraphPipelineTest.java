package dev.porter.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.json.Json;
import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.load.Sources;
import dev.porter.graph.model.Edge;
import dev.porter.graph.model.SymbolRef;
import dev.porter.graph.query.QueryResult;
import dev.porter.graph.query.SymbolGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphPipelineTest {
    @TempDir
    Path tmp;

    private Path build(boolean matcher, String outName) throws Exception {
        Path cache = tmp.resolve("cache");
        Sources s = SyntheticWorld.create(cache);
        Path out = tmp.resolve(outName);
        GraphBuilder.build(new GraphBuilder.Options(cache, out, List.of("3.0", "2.0", "1.0"), true, matcher), s, m -> { });
        return out;
    }

    @Test
    void graphFilesAreContractValid() throws Exception {
        Path out = build(false, "out");
        for (String f : List.of("symbols-3.0.graph.json", "symbols-2.0.graph.json", "symbols-1.0.graph.json",
                "edges-3.0__2.0.graph.json", "edges-2.0__1.0.graph.json")) {
            var r = ContractValidator.get().validateFile(out.resolve(f), Contract.SYMBOL_GRAPH);
            assertTrue(r.valid(), f + ": " + r.issues());
        }
        assertTrue(Files.isRegularFile(out.resolve("mappings/2.0.tiny")));
    }

    @Test
    void chainQueryThroughStitchPoint() throws Exception {
        SymbolGraph g = SymbolGraph.open(build(false, "out"));
        List<SymbolRef> refs = g.resolve("3.0", "com.example.Level#getThing(int)");
        assertEquals(1, refs.size());
        QueryResult q = g.query(refs.get(0), "1.0");
        assertEquals(List.of(new SymbolRef("1.0", dev.porter.graph.model.Sym.Kind.METHOD, "com.example.World", "getThingOld",
                "(I)Lcom/example/Thing;")), q.results());
        assertEquals(Edge.Status.RENAMED, q.status());
        assertEquals(2, q.path().size());
        assertTrue(ContractValidator.get().validate(Contract.SYMBOL_GRAPH, Json.json().valueToTree(q.toJson())).isEmpty());

        // SRG id changed, MCP name kept -> matched by the name fallback
        QueryResult tick = g.query(g.resolve("3.0", "com.example.Thing#tick").get(0), "1.0");
        assertEquals("update", tick.results().get(0).name());

        // class only in the newest version
        QueryResult gone = g.query(g.resolve("3.0", "NewStuff").get(0), "1.0");
        assertEquals(Edge.Status.REMOVED, gone.status());
        assertTrue(gone.results().isEmpty());

        // upward query: inverted edges
        QueryResult up = g.query(g.resolve("1.0", "com.example.World#getThingOld").get(0), "3.0");
        assertEquals("getThing", up.results().get(0).name());
        assertEquals("com.example.Level", up.results().get(0).owner());

        // names of every namespace resolve (srg id in 2.0)
        assertEquals(refs, g.resolve("2.0", "com.example.Level#func_100_a").stream().map(r -> g.query(r, "3.0").results().get(0)).toList());
        assertNotNull(g.info(refs.get(0)));
    }

    @Test
    void nonAdjacentVersionsAreRejectedBeforeLoading() throws Exception {
        Path cache = tmp.resolve("cache");
        Sources s = SyntheticWorld.create(cache);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> GraphBuilder.build(
                new GraphBuilder.Options(cache, tmp.resolve("x"), List.of("3.0", "1.0"), true, false), s, m -> { }));
    }

    @Test
    void unknownSymbolGivesMissing() throws Exception {
        SymbolGraph g = SymbolGraph.open(build(false, "out"));
        QueryResult q = g.query(new SymbolRef("3.0", dev.porter.graph.model.Sym.Kind.CLASS, null, "com.example.Nope", null), "1.0");
        assertNotNull(q.missing());
        assertTrue(ContractValidator.get().validate(Contract.SYMBOL_GRAPH, Json.json().valueToTree(q.toJson())).isEmpty());
    }

    @Test
    void buildIsDeterministicAndMatcherRunsHeadless() throws Exception {
        Path a = build(true, "a");
        Path b = build(true, "b");
        for (String f : List.of("symbols-3.0.graph.json", "edges-3.0__2.0.graph.json", "edges-2.0__1.0.graph.json",
                "manifest.json", "mappings/1.0.tiny")) {
            String x = Files.readString(a.resolve(f));
            String y = Files.readString(b.resolve(f));
            int i = 0;
            while (i < Math.min(x.length(), y.length()) && x.charAt(i) == y.charAt(i)) {
                i++;
            }
            int from = Math.max(0, i - 300);
            assertEquals(x, y, f + " differs at " + i + ": " + x.substring(from, Math.min(x.length(), i + 200))
                    + " ||| " + y.substring(from, Math.min(y.length(), i + 200)));
        }
        assertTrue(Files.readString(GraphFiles.manifestFile(a)).contains("fabricmc-matcher"));
    }
}
