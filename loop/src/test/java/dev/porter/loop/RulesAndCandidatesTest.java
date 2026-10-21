package dev.porter.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.rule.Rule;
import dev.porter.loop.candidates.CandidateAggregator;
import dev.porter.loop.candidates.HandportImporter;
import dev.porter.loop.candidates.Normalizer;
import dev.porter.loop.graph.GraphLookup;
import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.ErrorClass;
import dev.porter.loop.javac.JavacDiagnostic;
import dev.porter.loop.rules.FixAttempt;
import dev.porter.loop.rules.RuleEngine;
import dev.porter.loop.rules.TextRuleEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesAndCandidatesTest {
    @TempDir
    Path tmp;

    private static TextRuleEngine demoRules() {
        return TextRuleEngine.load(List.of(LoopEngineTest.demo().resolve("rules")), "forge-1.19.2", "forge-1.7.10", true);
    }

    private static ClassifiedError err(ErrorClass c, int line, String kind, String owner, String member, List<String> args, String symbol) {
        JavacDiagnostic d = new JavacDiagnostic("A.java", line, null, "error", "cannot find symbol", null, null, List.of());
        return new ClassifiedError(d, "A.java", c, "compiler.err.cant.resolve.location.method", kind, owner, member, args, symbol);
    }

    @Test
    void implicitReceiverStaysImplicit() {
        String text = "class A extends net.minecraft.world.World {\n    boolean c() { return isClientSide; }\n}\n";
        FixAttempt a = demoRules().fix(err(ErrorClass.MISSING_METHOD, 2, "field", "A", "isClientSide", null, "A#isClientSide"),
                new RuleEngine.SourceText("A.java", text));
        assertTrue(a.isApplied(), a.reason());
        assertEquals("    boolean c() { return isRemote; }", a.after());
    }

    @Test
    void notSuperKeepsSuperCalls() throws Exception {
        Path rules = tmp.resolve("r.rule.yaml");
        Files.writeString(rules, String.join("\n",
                "schemaVersion: 1", "id: r-test-getcapability", "from: forge-1.19.2", "to: forge-1.7.10", "kind: adapter",
                "match:", "  call: \"net.minecraft.world.item.ItemStack#getCapability(net.minecraftforge.common.capabilities.Capability)\"",
                "  bind: { recv: \"$o\", args: [\"$c\"] }", "replace: \"Caps.get($o, $c)\"", "preconditions: [\"not-super\"]",
                "confidence: 1.0", "provenance: { source: manual, ref: \"test\" }", "tests:",
                "  - context: \"ItemStack t; Capability c\"", "    before: \"Object x = t.getCapability(c);\"", "    after: \"Object x = Caps.get(t, c);\"", ""));
        TextRuleEngine r = TextRuleEngine.load(List.of(rules), "forge-1.19.2", "forge-1.7.10", true);
        String text = "class A {\n    Object s(ItemStack t, Object c) { return t.getCapability(c); }\n    Object u(Object c) { return super.getCapability(c); }\n}\n";
        FixAttempt a = r.fix(err(ErrorClass.MISSING_METHOD, 2, "method", "net.minecraft.world.item.ItemStack", "getCapability", List.of("Object"),
                "net.minecraft.world.item.ItemStack#getCapability(Object)"), new RuleEngine.SourceText("A.java", text));
        assertTrue(a.isApplied(), a.reason());
        assertEquals("    Object s(ItemStack t, Object c) { return Caps.get(t, c); }", a.after());
        FixAttempt b = r.fix(err(ErrorClass.MISSING_METHOD, 3, "method", "net.minecraft.world.item.ItemStack", "getCapability", List.of("Object"),
                "net.minecraft.world.item.ItemStack#getCapability(Object)"), new RuleEngine.SourceText("A.java", text));
        assertFalse(b.isApplied());
    }

    @Test
    void ownerMustMatch() {
        String text = "class A {\n    void s(Other tag) { tag.putInt(\"a\", 1); }\n}\n";
        FixAttempt a = demoRules().fix(err(ErrorClass.MISSING_METHOD, 2, "method", "com.example.Other", "putInt", List.of("String", "int"),
                "com.example.Other#putInt(String,int)"), new RuleEngine.SourceText("A.java", text));
        assertFalse(a.isApplied());
        assertTrue(a.reason().startsWith("not-applicable:owner-or-arity"), a.reason());
        FixAttempt b = demoRules().fix(err(ErrorClass.MISSING_METHOD, 2, "method", null, "putInt", null, null),
                new RuleEngine.SourceText("A.java", text));
        assertEquals("not-applicable:owner-unknown", b.reason());
        FixAttempt c = demoRules().fix(err(ErrorClass.JAVA_SYNTAX, 2, null, null, null, null, null), new RuleEngine.SourceText("A.java", text));
        assertEquals("no-rule", c.reason());
    }

    @Test
    void complexReceiverAndArity() {
        String text = "class A {\n    void s(NBTTagCompound t) { make().get(0).putInt(\"a\", f(1, 2)); t.putInt(\"x\"); }\n}\n";
        TextRuleEngine r = demoRules();
        FixAttempt a = r.fix(err(ErrorClass.MISSING_METHOD, 2, "method", "net.minecraft.nbt.NBTTagCompound", "putInt", List.of("String", "int"),
                "net.minecraft.nbt.NBTTagCompound#putInt(String,int)"), new RuleEngine.SourceText("A.java", text));
        // two putInt sites on the line and no column: ambiguous, nothing applied
        assertEquals("ambiguous:site", a.reason());
        String one = "class A {\n    void s() { make().get(0).putInt(\"a\", f(1, 2)); }\n}\n";
        FixAttempt b = r.fix(err(ErrorClass.MISSING_METHOD, 2, "method", "net.minecraft.nbt.NBTTagCompound", "putInt", List.of("String", "int"),
                "net.minecraft.nbt.NBTTagCompound#putInt(String,int)"), new RuleEngine.SourceText("A.java", one));
        assertTrue(b.isApplied(), b.reason());
        assertEquals("    void s() { make().get(0).setInteger(\"a\", f(1, 2)); }", b.after());
    }

    @Test
    void typeRuleKeepsStringsAndComments() {
        String text = "package p;\nimport net.minecraft.nbt.CompoundTag;\n// CompoundTag here\nclass A { CompoundTag t = null; String s = \"CompoundTag\"; }\n";
        FixAttempt a = demoRules().fix(err(ErrorClass.MISSING_CLASS, 2, "class", null, null, null, "net.minecraft.nbt.CompoundTag"),
                new RuleEngine.SourceText("A.java", text));
        assertTrue(a.isApplied(), a.reason());
        assertEquals("package p;\nimport net.minecraft.nbt.NBTTagCompound;\n// CompoundTag here\nclass A { NBTTagCompound t = null; String s = \"CompoundTag\"; }\n",
                a.newText());
        assertEquals("import net.minecraft.nbt.NBTTagCompound;", a.after());
    }

    /** RFC 006: a type rule with a position:* precondition is not a rename (neither shadows nor ties the real one). */
    @Test
    void positionalTypeRulesAreNotRenames() throws Exception {
        Path rules = tmp.resolve("p.rule.yaml");
        Files.writeString(rules, String.join("\n",
                "schemaVersion: 1", "id: r-test-root-extends", "from: forge-1.19.2", "to: forge-1.7.10", "kind: adapter",
                "match:", "  type: \"net.minecraft.nbt.CompoundTag\"", "replace: \"com.example.RootTag\"",
                "preconditions: [\"position:extends\"]", "priority: 10",
                "confidence: 1.0", "provenance: { source: manual, ref: \"test\" }", "tests:",
                "  - context: \"import net.minecraft.nbt.CompoundTag\"", "    before: \"class R extends CompoundTag { }\"",
                "    after: \"class R extends RootTag { }\"", ""));
        TextRuleEngine r = TextRuleEngine.load(List.of(LoopEngineTest.demo().resolve("rules"), rules), "forge-1.19.2", "forge-1.7.10", true);
        String text = "package p;\nimport net.minecraft.nbt.CompoundTag;\nclass A { CompoundTag t = null; }\n";
        FixAttempt a = r.fix(err(ErrorClass.MISSING_CLASS, 2, "class", null, null, null, "net.minecraft.nbt.CompoundTag"),
                new RuleEngine.SourceText("A.java", text));
        assertTrue(a.isApplied(), a.reason());
        assertEquals("import net.minecraft.nbt.NBTTagCompound;", a.after());
    }

    @Test
    void normalizer() {
        assertEquals("type net.minecraft.nbt.CompoundTag -> net.minecraft.nbt.NBTTagCompound",
                Normalizer.normalize("net.minecraft.nbt.CompoundTag", "import net.minecraft.nbt.CompoundTag;", "import net.minecraft.nbt.NBTTagCompound;"));
        String a = Normalizer.normalize("X#drain(int,FluidAction)", "tank.drain(amount, IFluidHandler.FluidAction.EXECUTE);", "tank.drain(amount, true)");
        String b = Normalizer.normalize("X#drain(int,FluidAction)", "out.drain( 100 ,IFluidHandler.FluidAction.EXECUTE)", "out.drain(100, true);");
        assertEquals("X#drain(int,FluidAction) :: $v0.drain($v1, IFluidHandler.FluidAction.EXECUTE) -> $v0.drain($v1, true)", a);
        assertEquals("X#drain(int,FluidAction) :: $v0.drain($l0, IFluidHandler.FluidAction.EXECUTE) -> $v0.drain($l0, true)", b);
        // package prefixes are not locals
        assertEquals("new net.minecraft.Foo($v0) -> $v0", Normalizer.normalize(null, "new net.minecraft.Foo(x)", "x"));
    }

    @Test
    void aggregationDraftsCallRuleWithBinds() throws Exception {
        Path log = tmp.resolve("a.candidates.jsonl");
        for (int i = 0; i < 3; i++) {
            String v = "t" + i;
            CandidateIo.append(log, new Candidate(1, "c-t" + i, "forge-1.19.2", "forge-1.7.10", "cannot find symbol", "missing_method",
                    "A.java", 3 + i, v + ".putInt(k, 1)", v + ".setInteger(k, 1)", "net.minecraft.nbt.CompoundTag#putInt(String,int)",
                    null, "agent:test", 1, null));
        }
        // audit records of an existing rule never draft
        CandidateIo.append(log, new Candidate(1, "c-r", "forge-1.19.2", "forge-1.7.10", "e", "missing_class", "A.java", 1, "X", "Y",
                "a.X", "type a.X -> b.Y", "rule:r-x", 5, "r-x"));
        List<CandidateAggregator.Group> groups = CandidateAggregator.aggregate(CandidateAggregator.readAll(List.of(tmp)));
        assertEquals(2, groups.size());
        List<Rule> drafts = CandidateAggregator.writeDrafts(groups, tmp.resolve("proposed"), 3, null, null);
        assertEquals(1, drafts.size());
        Rule r = drafts.get(0);
        assertEquals("net.minecraft.nbt.CompoundTag#putInt(String,int)", r.match().call());
        assertEquals("$v0", r.match().bind().recv());
        assertEquals(List.of("$v1", "$l0"), r.match().bind().args());
        assertEquals("$v0.setInteger($v1, $l0)", r.replace());
        assertTrue(r.id().startsWith("r-1.19.2-1.7.10-draft-putint-"), r.id());
        assertTrue(ContractValidator.get().validateFile(tmp.resolve("proposed").resolve(r.id() + ".rule.yaml"), null).valid());
    }

    @Test
    void handportImport() throws Exception {
        Path api = tmp.resolve("api.jsonl");
        Files.writeString(api, """
                {"from":"forge-1.19.2","to":"forge-1.7.10","before":"tank.drain(amount, IFluidHandler.FluidAction.EXECUTE)","after":"tank.drain(amount, true)","symbol":"X#EXECUTE","kind":"mechanical","count":9,"author":"agent:demo-steam"}
                {"from":"forge-1.19.2","to":"forge-1.7.10","before":"tank.drain(n, IFluidHandler.FluidAction.EXECUTE)","after":"tank.drain(n, true)","symbol":"X#EXECUTE","kind":"mechanical","count":1,"author":"agent:demo-barrels"}

                {"from":"forge-1.19.2","to":"forge-1.7.10","before":"getTanks()","after":"getTankInfo(from)","symbol":"Y#getTanks","kind":"adapter","count":2,"author":"bad author"}
                """, StandardCharsets.UTF_8);
        Path out = tmp.resolve("hp.candidates.jsonl");
        List<Candidate> cs = HandportImporter.importTo(api, out, "porting-log/api.jsonl");
        assertEquals(3, cs.size());
        assertTrue(ContractValidator.get().validateFile(out, null).valid());
        assertEquals(4, cs.get(2).line());
        assertEquals("human:handport", cs.get(2).author());
        List<CandidateAggregator.Group> g = CandidateAggregator.aggregate(CandidateIo.read(out));
        assertEquals(2, g.size());
        assertEquals(10, g.stream().filter(x -> x.normalized().startsWith("X#EXECUTE")).findFirst().orElseThrow().count());
    }

    @Test
    void graphLookup() {
        GraphLookup g = GraphLookup.open(LoopEngineTest.demo().resolve("graph"), "1.19.2", "1.7.10");
        assertNotNull(g);
        assertEquals(List.of("1.19.2", "1.7.10"), g.chain());
        Map<String, GraphLookup.Status> r = g.lookup(Set.of(GraphLookup.Query.parse("net.minecraft.util.Mth#clamp(int,int,int)"),
                GraphLookup.Query.parse("net.minecraft.world.level.block.Gone"), GraphLookup.Query.parse("a.b.Unknown")));
        assertEquals("renamed", r.get("net.minecraft.util.Mth#clamp").status());
        assertEquals(List.of("net.minecraft.util.MathHelper#clamp_int"), r.get("net.minecraft.util.Mth#clamp").results());
        assertEquals("removed", r.get("net.minecraft.world.level.block.Gone").status());
        assertEquals("no counterpart", r.get("net.minecraft.world.level.block.Gone").note());
        assertEquals("unknown", r.get("a.b.Unknown").status());
        assertNull(GraphLookup.open(LoopEngineTest.demo().resolve("graph"), "1.19.2", "1.12.2"));
    }
}
