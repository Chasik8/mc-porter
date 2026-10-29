package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RFC 006: pattern and declaration rules, $target marks. Synthetic code only. */
class PatternRulesTest {

    static Rule rule(String id, Rule.Match match, String replace, List<String> pre, Integer priority, Rule.Edit edit) {
        return new Rule(1, id, null, TestSupport.FROM, TestSupport.TO, Rule.Kind.IDIOM, match, replace, null, pre, null, priority,
                0.9, new Rule.Provenance("manual", null, null), List.of(new Rule.Test(null, null, "x", "x")), edit);
    }

    static Rule.Match pattern(String p) {
        return new Rule.Match(null, null, null, null, null, p, null, null);
    }

    static final String CODE = """
            package p;

            import net.minecraft.nbt.CompoundTag;

            public class A {
                int read(CompoundTag tag) {
                    int a = tag.m_128451_("energy");
                    int b = tag.m_128451_("other");
                    return a + b;
                }
            }
            """;

    @Test
    void literalValuesAndSrgAliasesInPatterns() {
        Rule r = rule("r-test-energy", pattern("$t.getInt(\"energy\")"), "$t.getInteger(\"power\")", null, null, null);
        String out = TestSupport.rewrite(List.of(r), TestSupport.srgNames(), CODE).text();
        assertTrue(out.contains("int a = tag.getInteger(\"power\");"), out);
        assertTrue(out.contains("int b = tag.m_128451_(\"other\");"), out);
    }

    static Rule getInt(String id, String recv, String key, String replace) {
        return new Rule(1, id, null, TestSupport.FROM, TestSupport.TO, Rule.Kind.MECHANICAL,
                new Rule.Match("net.minecraft.nbt.CompoundTag#getInt(String)", null, null, null, null, null,
                        new Rule.Bind(recv, List.of(key), null, null)),
                replace, null, null, null, 5, 0.9, new Rule.Provenance("manual", null, null), List.of(new Rule.Test(null, null, "x", "x")));
    }

    /** Two packs with the same rewrite under different placeholder names agree; different rewrites stay ambiguous. */
    @Test
    void rulesThatWriteTheSameCodeAreNotAmbiguous() {
        Rewriter.FileResult same = TestSupport.rewrite(List.of(getInt("r-a", "$t", "$k", "$t.getInteger($k)"),
                getInt("r-b", "$tag", "$a0", "$tag.getInteger($a0)")), TestSupport.srgNames(), CODE);
        assertTrue(same.text().contains("int a = tag.getInteger(\"energy\");"), same.text());
        assertFalse(same.text().contains("ambiguous"), same.text());
        Rewriter.FileResult diff = TestSupport.rewrite(List.of(getInt("r-a", "$t", "$k", "$t.getInteger($k)"),
                getInt("r-b", "$t", "$k", "$t.getShort($k)")), TestSupport.srgNames(), CODE);
        assertTrue(diff.text().contains("TODO(porter:ambiguous)"), diff.text());
    }

    @Test
    void anExpressionPatternOwnsItsNodeAgainstACallRule() {
        Rule p = rule("r-test-energy", pattern("$t.getInt(\"energy\")"), "$t.getInteger(\"power\")", null, null, null);
        Rule call = new Rule(1, "r-test-getint", null, TestSupport.FROM, TestSupport.TO, Rule.Kind.MECHANICAL,
                new Rule.Match("net.minecraft.nbt.CompoundTag#getInt(String)", null, null, null, null, null,
                        new Rule.Bind("$t", List.of("$k"), null, null)),
                "$t.getInteger($k)", null, null, null, 5, 0.9, new Rule.Provenance("manual", null, null),
                List.of(new Rule.Test(null, null, "x", "x")));
        Rewriter.FileResult f = TestSupport.rewrite(List.of(p, call), TestSupport.srgNames(), CODE);
        assertTrue(f.text().contains("int a = tag.getInteger(\"power\");"), f.text());
        assertTrue(f.text().contains("int b = tag.getInteger(\"other\");"), f.text());
        assertFalse(f.text().contains("conflict"), f.text());
    }

    @Test
    void statementPatternsRemoveWholeLines() {
        Rule r = rule("r-test-drop", pattern("$t.putInt(\"debug\", $v);"), "", null, null, null);
        String code = """
                package p;

                class B {
                    void save(Object tag) {
                        tag.putInt("debug", 1);
                        tag.putInt("kept", 2);
                    }
                }
                """;
        String out = TestSupport.rewrite(List.of(r), NameTable.empty(), code).text();
        assertEquals(code.replace("        tag.putInt(\"debug\", 1);\n", ""), out);
    }

    @Test
    void unparseablePatternsAreReportedNotApplied() {
        Rule r = rule("r-test-bad", pattern("if (("), "x", null, null, null);
        Rewriter rw = new Rewriter(List.of(r), NameTable.empty(), TestSupport.facts(), Map.of(), Rewriter.Options.defaults());
        assertEquals(List.of("r-test-bad"), rw.book().unsupported().stream().map(x -> x.id()).toList());
    }

    @Test
    void targetNamesSurviveRelocation() {
        Rule r = rule("r-test-host", new Rule.Match(null, null, null, null, null, null, null, "old.api.Tile"), null, null, null,
                new Rule.Edit(List.of("$target(old.api.Tile)"), null, null, null, null, null));
        String code = """
                package old.api;

                public interface Tile
                extends Location {
                }
                """;
        Rewriter rw = new Rewriter(List.of(r), NameTable.empty(), TestSupport.facts(), Map.of(),
                Rewriter.Options.defaults().withKeepTargetMarks(true));
        Rewriter.Result res = rw.rewrite(List.of(new Rewriter.SourceFile("old/api/Tile.java", code, true),
                new Rewriter.SourceFile("old/api/Location.java", "package old.api;\npublic interface Location {}\n", true)));
        Map<String, String> table = PostProcess.relocationTable(res.files(), List.of(PostProcess.Relocation.parse("old.=ported.")));
        Rewriter.FileResult f = PostProcess.stripMarks(PostProcess.relocate(res.files().get(1), table));
        assertTrue(f.text().contains("package ported.api;"), f.text());
        assertTrue(f.text().contains("extends Location, old.api.Tile {"), f.text());
        // without relocation the mark is stripped by the rewriter itself
        Rewriter plain = new Rewriter(List.of(r), NameTable.empty(), TestSupport.facts(), Map.of(), Rewriter.Options.defaults());
        String out = plain.rewrite(List.of(new Rewriter.SourceFile("old/api/Tile.java", code, true))).files().get(0).text();
        assertTrue(out.contains("extends Location, old.api.Tile {") && out.indexOf('') < 0, out);
    }

    static final String CASTS = """
            package p;

            public class B {
                Object block;
                Object state;
                void run(Object state, Object block) {
                    Pickup bucket = (Pickup) block;
                    Pumps.pickupBlock(((Pickup) state.getBlock()), 1, 2);
                    int n = ((Pickup) state.getBlock()).count();
                    Other o = (Other) block;
                }
            }
            """;

    @Test
    void castPatternsConsumeTheCastAndBindTypes() {
        Rule decl = rule("r-test-cast-decl", pattern("Pickup $v = (Pickup) $e;"), "Object $v = $e;", null, null, null);
        Rule arg = rule("r-test-cast-arg", pattern("Pumps.pickupBlock((Pickup) $e, $a, $b)"), "Pumps.pickupBlock($e, $a, $b)", null, null, null);
        Rule recv = rule("r-test-cast-recv", pattern("((Pickup) $e).count()"), "Counts.of($e)", null, null, null);
        String out = TestSupport.rewrite(List.of(decl, arg, recv), TestSupport.srgNames(), CASTS).text();
        assertTrue(out.contains("Object bucket = block;"), out);
        assertTrue(out.contains("Pumps.pickupBlock(state.getBlock(), 1, 2);"), out);
        assertTrue(out.contains("int n = Counts.of(state.getBlock());"), out);
        assertTrue(out.contains("Other o = (Other) block;"), out);
    }

    @Test
    void typeMetavariablesMatchAnyTypeConsistently() {
        Rule r = rule("r-test-cast-meta", pattern("$T $v = ($T) $e;"), "Object $v = $e; // was $T", null, null, null);
        String out = TestSupport.rewrite(List.of(r), TestSupport.srgNames(), CASTS).text();
        assertTrue(out.contains("Object bucket = block; // was Pickup"), out);
        assertTrue(out.contains("Object o = block; // was Other"), out);
        String mixed = CASTS.replace("Other o = (Other) block;", "Other o = (Pickup) block;");
        assertTrue(TestSupport.rewrite(List.of(r), TestSupport.srgNames(), mixed).text().contains("Other o = (Pickup) block;"));
    }

    @Test
    void aCastlessPatternStillIgnoresTargetCasts() {
        Rule r = rule("r-test-nocast", pattern("Pumps.pickupBlock($e, $a, $b)"), "Pumps.take($e, $a, $b)", null, null, null);
        String out = TestSupport.rewrite(List.of(r), TestSupport.srgNames(), CASTS).text();
        assertTrue(out.contains("Pumps.take(((Pickup) state.getBlock()), 1, 2);") || out.contains("Pumps.take((Pickup) state.getBlock(), 1, 2);"), out);
    }
}
