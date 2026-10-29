package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpfrontMatchTest {
    static final Path FX = Path.of("src/test/resources/upfront/match");
    static final String H = "a.src.Holder";
    static final String B = "net.minecraft.dst.Box";

    static ApiSymbol sym(SymbolKey k, String declaredIn, int access) {
        return new ApiSymbol(k, 1, Set.of("bytecode"), access, declaredIn, List.of(), null);
    }

    static ApiSymbol m(String owner, String name, String desc) {
        return sym(SymbolKey.method(owner, name, desc), owner, 1);
    }

    static Rule rule(String id, Rule.Match match, String replace) {
        return new Rule(1, id, "", "forge-1.19.2", "forge-1.7.10", Rule.Kind.MECHANICAL, match, replace, null, null, null, null, 0.9, null, List.of());
    }

    static Rule call(String id, String sel, String replace) {
        return rule(id, new Rule.Match(sel, null, null, null, null, null, null), replace);
    }

    Map<String, MatchRow> run(List<Rule> hand) {
        return runFull(hand).rows;
    }

    record Run(Map<String, MatchRow> rows, MatchTable table, Matcher matcher, IndexData idx) {
    }

    Run runFull(List<Rule> hand) {
        MatchTables t = MatchTables.load(FX.resolve("match-tables.yaml"));
        IndexData idx = IndexData.read(FX.resolve("index"));
        ConceptDescs c = ConceptDescs.load(FX.resolve("concepts.yaml"));
        List<ApiSymbol> s = new ArrayList<>();
        s.add(m(H, "getCount", "()I"));                       // lineage, renamed
        s.add(m(H, "put", "(La/src/Pos;)V"));                 // lineage, signature explained by a concept
        s.add(m(H, "fill", "(La/src/Pos;La/src/Mode;)V"));    // lineage, unexplained signature change -> HINT
        s.add(m(H, "hidden", "()V"));                         // lineage target is private
        s.add(m(H, "gone", "()V"));                           // lineage target missing in the target jar
        s.add(m(H, "lost", "()V"));                           // lineage broken
        s.add(m(H, "isFull", "()Z"));                         // getter -> field
        s.add(m(H, "grow", "()V"));                           // two candidates within the margin
        s.add(m("a.src.Sub", "fill", "(La/src/Pos;La/src/Mode;)V")); // declared on a supertype's lineage
        s.add(sym(SymbolKey.field("a.src.Items", "STONE", "La/src/Item;"), "a.src.Items", 25)); // registry owner
        s.add(m("a.src.Mirrored", "above", "()V"));           // mirror owner
        s.add(m("a.src.Vec", "isFull", "()Z"));               // concept owner
        s.add(sym(SymbolKey.type(H), H, -1));
        s.add(sym(SymbolKey.type("a.src.Same"), "a.src.Same", -1));
        s.add(sym(SymbolKey.type("c.old.Plain"), "c.old.Plain", -1));
        s.add(sym(SymbolKey.type("c.old.Zed"), "c.old.Zed", -1));
        s.add(sym(SymbolKey.type("a.src.Pos"), "a.src.Pos", -1));
        s.add(sym(SymbolKey.type("a.src.Mirrored"), "a.src.Mirrored", -1));
        Matcher mt = new Matcher(t, idx, c, hand, List.of());
        MatchTable table = mt.match(new ApiInventory(1, "forge-1.19.2", "t", null, s, null));
        return new Run(Matcher.byKey(table), table, mt, idx);
    }

    @Test
    void stagesFireOnce() {
        Map<String, MatchRow> r = run(List.of());
        MatchRow x = r.get("method:a.src.Holder#getCount()I");
        assertEquals(Plan.MAP, x.plan());
        assertEquals("lineage", x.method());
        assertEquals("size", x.target().name());
        assertEquals("none", x.resolvedBy());
        assertEquals("holders", x.area());

        x = r.get("method:a.src.Holder#put(La/src/Pos;)V");
        assertEquals(Plan.EXPAND, x.plan());
        assertEquals("lineage-concept", x.method());
        assertEquals("(III)V", x.target().desc());

        x = r.get("method:a.src.Holder#fill(La/src/Pos;La/src/Mode;)V");
        assertEquals(Plan.HINT, x.plan());
        assertEquals("lineage-sig", x.method());
        assertEquals("fill", x.target().name());

        x = r.get("method:a.src.Sub#fill(La/src/Pos;La/src/Mode;)V");
        assertEquals(Plan.HINT, x.plan(), "the lineage row of a source supertype is used");
    }

    @Test
    void rejectedAndFallbackCases() {
        Map<String, MatchRow> r = run(List.of());
        MatchRow x = r.get("method:a.src.Holder#hidden()V");
        assertEquals(Plan.TODO, x.plan(), "a private lineage target is never used");
        assertEquals("none:below-threshold", x.method());
        assertFalse(x.alternatives().stream().anyMatch(a -> a.name().equals("hidden")));

        x = r.get("method:a.src.Holder#gone()V");
        assertEquals(Plan.TODO, x.plan(), "lineage target missing in the target jar falls back to scoring");

        x = r.get("method:a.src.Holder#lost()V");
        assertEquals(Plan.TODO, x.plan());

        x = r.get("method:a.src.Holder#grow()V");
        assertEquals(Plan.TODO, x.plan(), "two candidates within the margin");
        assertEquals("none:below-threshold", x.method());
        assertTrue(x.alternatives().size() >= 2);
        assertEquals(List.of("expand", "extend"), x.alternatives().stream().map(a -> a.name()).limit(2).sorted().toList());
    }

    @Test
    void accessorRegistryMirrorConcept() {
        Map<String, MatchRow> r = run(List.of());
        MatchRow x = r.get("method:a.src.Holder#isFull()Z");
        assertEquals(Plan.MAP, x.plan());
        assertEquals("scored:lineage-class", x.method());
        assertEquals("full", x.target().name());
        assertEquals(0.95, x.confidence(), 1e-9);

        x = r.get("field:a.src.Items#STONE:La/src/Item;");
        assertEquals(Plan.REGISTRY, x.plan());
        assertEquals("registry", x.method());

        x = r.get("method:a.src.Mirrored#above()V");
        assertEquals(Plan.MIRROR, x.plan());
        assertEquals("dev.shim.Mirrored", x.shim());

        x = r.get("method:a.src.Vec#isFull()Z");
        assertEquals("scored:concept-owner", x.method());
    }

    @Test
    void typeStages() {
        Map<String, MatchRow> r = run(List.of());
        assertEquals("lineage", r.get("type:a.src.Holder").method());
        assertEquals(B, r.get("type:a.src.Holder").target().owner());
        assertEquals("same-fqn", r.get("type:a.src.Same").method());
        assertEquals("simple-name", r.get("type:c.old.Plain").method());
        assertEquals(0.5, r.get("type:c.old.Plain").confidence(), 1e-9);
        assertEquals(Plan.TODO, r.get("type:c.old.Zed").plan(), "ambiguous simple name");
        assertEquals(Plan.EXPAND, r.get("type:a.src.Pos").plan());
        assertEquals(Plan.MIRROR, r.get("type:a.src.Mirrored").plan());
        assertNotNull(r.get("type:a.src.Mirrored").shim());
    }

    @Test
    void evalWithNormalisedNamesAndKeptNames(@TempDir Path tmp) throws IOException {
        List<Rule> hand = List.of(
                call("r1", H + "#getCount()", "$r.size()"),
                call("r2", H + "#isFull()", "$r.field_2_b"),
                call("r3", H + "#fill(La/src/Pos;)", "$r.fill($a)"),
                call("r4", H + "#grow()", "$r.expand()"),
                rule("r5", new Rule.Match(null, null, null, H, null, null, null), B),
                call("r6", "a.src.Mirrored#above()", "dev.shim.Helper.above($r)"));
        Run run = runFull(hand);
        assertEquals("rule", run.rows().get("method:a.src.Holder#getCount()I").resolvedBy());
        assertEquals("r1", run.rows().get("method:a.src.Holder#getCount()I").ruleId());
        MatchEval.Result e = MatchEval.run(run.table(), run.matcher(), run.idx());
        assertEquals(1, e.types().gt());
        assertEquals(1.0, e.types().precision(), 1e-9);
        assertEquals(1.0, e.types().recall(), 1e-9);
        assertEquals(3, e.members().gt(), "getCount, isFull, grow; fill keeps its name, above is a shim");
        assertEquals(1, e.members().keptName());
        assertEquals(2, e.members().tp());
        assertEquals(0, e.members().fp());
        assertEquals(1, e.members().fn());
        assertEquals(2 / 3.0, e.members().recall(), 1e-9);
        assertTrue(e.unresolvedAuto() >= 1);

        Path tsv = tmp.resolve("t.tsv");
        Path json = tmp.resolve("t.json");
        run.table().write(tsv, json);
        MatchTable back = MatchTable.read(json);
        assertEquals(run.table().rows().size(), back.rows().size());
        assertTrue(java.nio.file.Files.readString(tsv).startsWith("key\tkind\tuses\tarea"));
        // deterministic: a second run writes identical bytes
        Path tsv2 = tmp.resolve("t2.tsv");
        runFull(hand).table().write(tsv2, tmp.resolve("t2.json"));
        assertEquals(java.nio.file.Files.readString(tsv), java.nio.file.Files.readString(tsv2));
    }
}
