package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.RuleTestRunner;
import dev.porter.rewrite.graph.GraphRuleGenerator;
import dev.porter.rewrite.graph.SymbolGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mechanical rules from a (synthetic, contract-valid) symbol graph with adjacent-version edges. */
class GraphRuleGeneratorTest {
    static GraphRuleGenerator.Output generate() {
        SymbolGraph g = SymbolGraph.load(List.of(TestSupport.resource("graphs/chain-1.19.2__1.7.10.graph.json")));
        return new GraphRuleGenerator("1.19.2", "1.7.10", TestSupport.FROM, TestSupport.TO).generate(g);
    }

    @Test
    void mechanicalRulesFromRenamedAndMovedChains(@TempDir Path tmp) {
        GraphRuleGenerator.Output out = generate();
        Map<String, String> byMatch = new TreeMap<>();
        for (Rule r : out.rules()) {
            Rule.Match m = r.match();
            String key = m.type() != null ? "type " + m.type() : m.call() != null ? "call " + m.call()
                    : m.field() != null ? "field " + m.field() : m.override() != null ? "override " + m.override() : "new " + m.newCall();
            byMatch.put(key, r.replace());
            assertEquals("graph", r.provenance().source());
            assertEquals(Rule.Kind.MECHANICAL, r.kind());
        }
        assertEquals("net.minecraft.entity.Entity", byMatch.get("type net.minecraft.world.entity.Entity"), byMatch.toString());
        assertEquals("$r.getEntityId()", byMatch.get("call net.minecraft.world.entity.Entity#getId()"));
        assertEquals("getEntityId", byMatch.get("override net.minecraft.world.entity.Entity#getId()"));
        assertEquals("$r.ticksExisted", byMatch.get("field net.minecraft.world.entity.Entity#tickCount"));
        // static (access flags from the graph) and moved; the 1.7.10 edge is stored backwards in the document
        assertEquals("MathHelper.floor_double($a0)", byMatch.get("call net.minecraft.util.Mth#floor(double)"));
        assertEquals("removed", out.diagnostics().get("net.minecraft.world.level.block.state.BlockState"));
        Path f = tmp.resolve("gen.rule.yaml");
        RuleIo.write(f, out.rules()); // validates against contracts/rule.schema.json
        assertEquals(out.rules().size(), RuleIo.read(f).size());
    }

    @Test
    void generatedRulesPassTheirOwnTests() {
        List<RuleTestRunner.Failure> f = new RuleTestRunner(NameTable.empty(), TestSupport.facts(), Rewriter.Options.defaults())
                .run(generate().rules());
        assertTrue(f.isEmpty(), () -> String.join("\n", f.stream().map(RuleTestRunner.Failure::message).toList()));
    }

    @Test
    void removedSymbolsExplainTheirTodo() {
        GraphRuleGenerator.Output gen = generate();
        Rewriter rw = new Rewriter(gen.rules(), NameTable.empty(), TestSupport.facts(), gen.diagnostics(), Rewriter.Options.defaults());
        String out = rw.rewrite(List.of(new Rewriter.SourceFile("p/E.java", """
                package p;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.level.block.state.BlockState;
                class E { int f(Entity e, BlockState s) { return e.getId() + e.tickCount; } }
                """, true))).files().get(0).text();
        assertTrue(out.contains("return e.getEntityId() + e.ticksExisted;"), out);
        assertTrue(out.contains("TODO(porter:removed) type net.minecraft.world.level.block.state.BlockState"), out);
    }

    @Test
    void deterministicOutput(@TempDir Path tmp) throws Exception {
        RuleIo.write(tmp.resolve("a.rule.yaml"), generate().rules());
        RuleIo.write(tmp.resolve("b.rule.yaml"), generate().rules());
        assertEquals(Files.readString(tmp.resolve("a.rule.yaml")), Files.readString(tmp.resolve("b.rule.yaml")));
    }
}
