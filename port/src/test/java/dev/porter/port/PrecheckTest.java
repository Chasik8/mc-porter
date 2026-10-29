package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.RuleIo;
import dev.porter.port.javac.CastFix;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrecheckTest {
    private static String rule(String id, String call, String replace, String extra) {
        return "schemaVersion: 1\nid: " + id + "\ndescription: d\nfrom: forge-1.19.2\nto: forge-1.7.10\nkind: mechanical\n"
                + "match:\n  call: \"" + call + "\"\n  bind: { recv: \"$s\" }\nreplace: \"" + replace + "\"\n" + extra + "confidence: 0.9\n"
                + "provenance: { source: manual, ref: t }\n"
                + "tests:\n  - context: \"\"\n    before: \"a.b();\"\n    after: \"a.c();\"\n";
    }

    private List<String> problems(Path dir) {
        return RuleChecks.load(List.of(dir), "forge-1.19.2", "forge-1.7.10").problems();
    }

    @Test
    void cleanRulesPass(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        Files.writeString(tmp.resolve("b.rule.yaml"), rule("r-b", "x.Foo#baz()", "$s.d()", ""));
        assertEquals(List.of(), problems(tmp));
    }

    @Test
    void duplicateIdsAreReported(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        Files.writeString(tmp.resolve("b.rule.yaml"), rule("r-a", "x.Foo#baz()", "$s.d()", ""));
        List<String> p = problems(tmp);
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).startsWith("rules:duplicate-id: r-a"), p.get(0));
    }

    @Test
    void schemaErrorIsReportedAndOtherFilesStillLoad(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bad.rule.yaml"), "schemaVersion: 1\nid: r-bad\n");
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        RuleChecks.Result r = RuleChecks.load(List.of(tmp), "forge-1.19.2", "forge-1.7.10");
        assertEquals(1, r.rules().size());
        assertTrue(r.problems().get(0).startsWith("rules:schema: "), r.problems().toString());
    }

    @Test
    void samePriorityDifferentReplacementConflicts(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        Files.writeString(tmp.resolve("b.rule.yaml"), rule("r-b", "x.Foo#bar()", "$s.d()", ""));
        List<String> p = problems(tmp);
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).startsWith("rules:conflict: r-a vs r-b"), p.get(0));
    }

    @Test
    void priorityPreconditionsOrSameReplacementResolveIt(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        Files.writeString(tmp.resolve("b.rule.yaml"), rule("r-b", "x.Foo#bar()", "$s.d()", "priority: 5\n"));
        Files.writeString(tmp.resolve("c.rule.yaml"), rule("r-c", "x.Foo#bar()", "$s.d()", "preconditions: [\"type:$s:x.Foo\"]\n"));
        Files.writeString(tmp.resolve("d.rule.yaml"), rule("r-d", "y.Foo#bar()", "$s.c()", ""));
        assertEquals(List.of(), problems(tmp));
    }

    @Test
    void objectOwnerCompetesWithEveryOwner(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "java.lang.Object#bar()", "$s.c()", ""));
        Files.writeString(tmp.resolve("b.rule.yaml"), rule("r-b", "x.Foo#bar()", "$s.d()", ""));
        assertEquals(1, problems(tmp).size());
    }

    @Test
    void needlesNameTheSymbolARuleSelects(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.rule.yaml"), rule("r-a", "x.Foo#bar()", "$s.c()", ""));
        assertEquals(List.of("bar"), Precheck.needles(RuleIo.read(tmp.resolve("a.rule.yaml")).get(0)));
    }

    @Test
    void importedTypesJoinThePrunedSourceRoot(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path pruned = tmp.resolve("pruned");
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(pruned.resolve("a"));
        Files.writeString(src.resolve("a/Hub.java"), "package a;\nimport a.Config;\npublic class Hub { Config c; }\n");
        Files.writeString(src.resolve("a/Config.java"), "package a;\nimport a.Deep;\npublic class Config { Deep d; }\n");
        Files.writeString(src.resolve("a/Deep.java"), "package a;\nimport a.Deeper;\npublic class Deep { Deeper d; }\n");
        Files.writeString(src.resolve("a/Deeper.java"), "package a;\npublic class Deeper {}\n");
        Files.writeString(src.resolve("a/Other.java"), "package a;\npublic class Other {}\n");
        Files.copy(src.resolve("a/Hub.java"), pruned.resolve("a/Hub.java"));
        Precheck.addImported(src, pruned, 2);
        assertTrue(Files.exists(pruned.resolve("a/Config.java")));
        assertTrue(Files.exists(pruned.resolve("a/Deep.java")));
        assertTrue(!Files.exists(pruned.resolve("a/Deeper.java")), "two rounds only");
        assertTrue(!Files.exists(pruned.resolve("a/Other.java")));
    }

    @Test
    void castFixNeverEditsAFileOutsideItsRoot(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Path outside = tmp.resolve("ctx/A.java");
        Files.createDirectories(root);
        Files.createDirectories(outside.getParent());
        String src = "class A { void f() { int x = (String) y; } }\n";
        Files.writeString(outside, src);
        String log = outside + ":1: error: incompatible types: Object cannot be converted to String\n  x\n  ^\n";
        assertEquals(0, CastFix.apply(log, root));
        assertEquals(src, Files.readString(outside));
    }
}
