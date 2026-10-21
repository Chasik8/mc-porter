package dev.porter.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.loop.graph.GraphSource;
import dev.porter.loop.rules.RewriterRuleEngine;
import dev.porter.loop.rules.TextRuleEngine;
import dev.porter.loop.run.JavacCompileRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class LoopEngineTest {
    @TempDir
    Path tmp;

    static Path demo() {
        return Path.of(System.getProperty("porter.repoRoot", "..")).resolve("loop/src/test/resources/demo").toAbsolutePath();
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : w.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }

    private LoopEngine.Config config(Path project, Path work, dev.porter.loop.rules.RuleEngine rules, int maxIter, Path proposed) {
        Path candDir = work.resolve("candidates");
        return new LoopEngine.Config(project, new JavacCompileRunner(List.of(project.resolve("src/main/java")), List.of(), work.resolve("classes"), List.of()),
                rules, maxIter, work, candDir.resolve("loop.candidates.jsonl"), List.of(candDir, demo().resolve("candidates")), proposed,
                () -> GraphSource.open(demo().resolve("graph"), "1.19.2", "1.7.10"), "forge-1.19.2", "forge-1.7.10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoProjectEndToEnd() throws Exception {
        Path project = tmp.resolve("demo-project");
        copyTree(demo().resolve("project"), project);
        Path work = tmp.resolve("work");
        Path proposed = tmp.resolve("proposed");
        TextRuleEngine rules = TextRuleEngine.load(List.of(demo().resolve("rules")), "forge-1.19.2", "forge-1.7.10", true);
        assertEquals(7, rules.ruleCount());
        Map<String, Object> report = new LoopEngine(config(project, work, rules, 10, proposed)).run();

        String machine = Files.readString(project.resolve("src/main/java/com/example/demo/Machine.java"));
        assertTrue(machine.contains("import net.minecraft.nbt.NBTTagCompound;"), machine);
        assertTrue(machine.contains("public void updateEntity()"), machine);
        assertTrue(machine.contains("worldObj.isRemote"), machine);
        assertTrue(machine.contains("tag.setInteger(\"energy\", energy);"), machine);
        assertTrue(machine.contains("energy = tag.getInteger(\"energy\");"), machine);
        assertTrue(machine.contains("Mth.clamp"), "no rule for Mth: must stay for the agent");
        assertTrue(Files.exists(work.resolve("backup/src/main/java/com/example/demo/Machine.java")));

        assertEquals("needs-agent", report.get("status"), Json.json().writeValueAsString(report));
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertEquals(5, totals.get("fixesApplied"));
        assertEquals(5, totals.get("fixesVerified"));
        assertEquals(0, totals.get("fixesPersisted"));

        // Verified fixes were logged as (audit) candidates.
        List<Candidate> logged = CandidateIo.read(work.resolve("candidates/loop.candidates.jsonl"));
        assertEquals(5, logged.size());
        assertTrue(logged.stream().allMatch(c -> c.author().startsWith("rule:") && c.ruleId() != null));
        assertTrue(logged.stream().anyMatch(c -> "type net.minecraft.nbt.CompoundTag -> net.minecraft.nbt.NBTTagCompound".equals(c.normalized())),
                logged.toString());

        // The Mth error became a task with graph status and a similar solved candidate.
        List<Path> tasks;
        try (Stream<Path> w = Files.list(work.resolve("tasks"))) {
            tasks = w.sorted().toList();
        }
        assertFalse(tasks.isEmpty());
        boolean sawGraph = false;
        for (Path t : tasks) {
            JsonNode n = Json.json().readTree(t.toFile());
            assertEquals("no-rule", n.get("reason").asString(), n.toString());
            if (n.path("graph").path("status").asString().equals("moved")) {
                sawGraph = true;
                assertEquals("net.minecraft.util.MathHelper", n.path("graph").path("results").get(0).asString());
                assertEquals("c-demo-3", n.path("similar").get(0).path("id").asString());
            }
        }
        assertTrue(sawGraph, "graph status attached");

        // Drafts: relative(...) group (count 3) and getX() group (count 3); validated rule files.
        List<Path> drafts;
        try (Stream<Path> w = Files.list(proposed)) {
            drafts = w.sorted().toList();
        }
        assertEquals(2, drafts.size(), drafts.toString());
        for (Path d : drafts) {
            assertTrue(ContractValidator.get().validateFile(d, null).valid(), d.toString());
            Rule r = RuleIo.read(d).get(0);
            assertTrue(r.id().contains("-draft-"));
            assertTrue(r.tests().size() >= 1);
        }

        // Determinism: same input, same report (rerun on a fresh copy).
        Path project2 = tmp.resolve("demo-project2");
        copyTree(demo().resolve("project"), project2.getParent().resolve("demo-project2"));
        Map<String, Object> report2 = new LoopEngine(config(project2, tmp.resolve("work2"), rules, 10, tmp.resolve("proposed2"))).run();
        report.remove("project");
        report2.remove("project");
        assertEquals(Json.json().writeValueAsString(report), Json.json().writeValueAsString(report2));
    }

    @Test
    void demoProjectWithRewriterEngine() throws Exception {
        Path project = tmp.resolve("demo-rw");
        copyTree(demo().resolve("project"), project);
        Path work = tmp.resolve("work-rw");
        TextRuleEngine text = TextRuleEngine.load(List.of(demo().resolve("rules")), "forge-1.19.2", "forge-1.7.10", false);
        RewriterRuleEngine rw = new RewriterRuleEngine(text.rules(), "forge-1.19.2", false, text);
        Map<String, Object> report = new LoopEngine(config(project, work, rw, 10, tmp.resolve("proposed-rw"))).run();
        String machine = Files.readString(project.resolve("src/main/java/com/example/demo/Machine.java"));
        assertEquals("needs-agent", report.get("status"), Json.json().writeValueAsString(report));
        assertTrue(machine.contains("import net.minecraft.nbt.NBTTagCompound;"), machine);
        assertTrue(machine.contains("public void updateEntity()"), machine);
        assertTrue(machine.contains("worldObj.isRemote"), machine);
        assertTrue(machine.contains("tag.setInteger(\"energy\", energy);"), machine);
        assertTrue(machine.contains("energy = tag.getInteger(\"energy\");"), machine);
        assertFalse(machine.contains("TODO(porter:"), machine);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertEquals(0, totals.get("fixesPersisted"));
    }

    @Test
    void sameErrorAfterFixIsEscalated() throws Exception {
        Path project = tmp.resolve("p");
        Path src = project.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), """
                package com.example;
                class A {
                    int f(Holder h) { return h.size(); }
                }
                class Holder { int count() { return 0; } }
                """, StandardCharsets.UTF_8);
        // A wrong rule: rewrites size() to size(0) - still a missing 'size'.
        Path rules = tmp.resolve("bad.rule.yaml");
        Files.writeString(rules, """
                schemaVersion: 1
                id: r-1.19.2-1.7.10-bad-size
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  call: "com.example.Holder#size()"
                  bind: { recv: "$h" }
                replace: "$h.size(0)"
                confidence: 0.1
                provenance: { source: manual, ref: "test" }
                tests:
                  - before: "h.size()"
                    after: "h.size(0)"
                """, StandardCharsets.UTF_8);
        TextRuleEngine engine = TextRuleEngine.load(List.of(rules), "forge-1.19.2", "forge-1.7.10", true);
        Path work = tmp.resolve("w");
        Map<String, Object> report = new LoopEngine(new LoopEngine.Config(project,
                new JavacCompileRunner(List.of(project.resolve("src/main/java")), List.of(), work.resolve("classes"), List.of()),
                engine, 10, work, work.resolve("candidates/loop.candidates.jsonl"), List.of(work.resolve("candidates")), null, null,
                "forge-1.19.2", "forge-1.7.10")).run();
        assertEquals("needs-agent", report.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertEquals(1, totals.get("fixesPersisted"));
        assertEquals(2, report.get("compiles"));
        JsonNode task;
        try (Stream<Path> w = Files.list(work.resolve("tasks"))) {
            task = Json.json().readTree(w.findFirst().orElseThrow().toFile());
        }
        assertTrue(task.get("reason").asString().startsWith("same-error-after-fix:r-1.19.2-1.7.10-bad-size"), task.toString());
        assertFalse(Files.exists(work.resolve("candidates/loop.candidates.jsonl")), "a fix that did not help is not a candidate");
    }

    @Test
    void compilesCleanly() throws Exception {
        Path project = tmp.resolve("ok");
        Path src = project.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "package com.example;\nclass A { }\n", StandardCharsets.UTF_8);
        Path work = tmp.resolve("w");
        Map<String, Object> report = new LoopEngine(new LoopEngine.Config(project,
                new JavacCompileRunner(List.of(project.resolve("src/main/java")), List.of(), work.resolve("classes"), List.of()),
                new TextRuleEngine(List.of(), true), 10, work, null, List.of(), null, null, "forge-1.19.2", "forge-1.7.10")).run();
        assertEquals("compiled", report.get("status"));
        assertEquals(1, report.get("compiles"));
    }
}
