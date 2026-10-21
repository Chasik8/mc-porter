package dev.porter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.contract.ContractViolationException;
import dev.porter.core.contract.Documents;
import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrIo;
import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Java models must bind every valid example losslessly and write only valid documents. */
class ModelRoundTripTest {
    @TempDir
    Path tmp;

    private static Path example(String rel) {
        return ContractExamplesTest.examples().resolve(rel);
    }

    @Test
    void irExamplesRoundTripLosslessly() throws IOException {
        for (Path f : ContractExamplesTest.files(Contract.IR, "valid")) {
            IrDocument doc = IrIo.read(f);
            Path out = tmp.resolve(f.getFileName());
            IrIo.write(out, doc);
            JsonNode original = Documents.read(f).documents().get(0);
            JsonNode written = Documents.read(out).documents().get(0);
            assertEquals(original, written, "round trip of " + f.getFileName());
            assertEquals(doc, IrIo.read(out));
        }
    }

    /** RFC 003: viewer fields bind, and documents get the lowest schemaVersion that carries their nodes. */
    @Test
    void rfc003FieldsBindAndPickTheVersion() throws IOException {
        IrDocument doc = IrIo.read(example("ir/valid/machine-recipes.ir.json"));
        IrNode.Recipe press = doc.nodes(IrNode.Recipe.class).findFirst().orElseThrow();
        assertEquals(List.of("examplemod:copper_press"), press.machines());
        assertEquals(250, press.fluidIngredients().get(0).amount());
        assertEquals(0.5, press.fluidResults().get(0).chance());
        assertEquals(0.25, press.results().get(1).chance());
        assertEquals(new IrNode.Note(null, "Needs 20 EU/t"), press.notes().get(0));
        assertEquals("examplemod.recipe.pressing.hint", press.notes().get(1).key());
        assertEquals(Boolean.TRUE, doc.nodes(IrNode.Item.class).findFirst().orElseThrow().hidden());
        IrDocument.Source src = new IrDocument.Source("forge-1.19.2", "examplemod", null);
        assertEquals(2, new IrDocument(src, doc.nodes()).schemaVersion());
        IrDocument v1 = IrIo.read(example("ir/valid/all-concepts.ir.json"));
        assertEquals(1, new IrDocument(src, v1.nodes()).schemaVersion());
        Path out = tmp.resolve("v2.ir.json");
        IrIo.write(out, new IrDocument(src, doc.nodes()));
        assertEquals(doc.nodes(), IrIo.read(out).nodes());
    }

    @Test
    void allConceptsExampleBindsEveryKind() {
        IrDocument doc = IrIo.read(example("ir/valid/all-concepts.ir.json"));
        List<String> kinds = doc.nodes().stream().map(IrNode::kind).distinct().sorted().toList();
        assertEquals(List.of("block", "blockEntity", "effect", "fluid", "item", "lang", "menu", "mod", "multiblock",
                "packet", "recipe", "sound", "tag"), kinds);
        IrNode.Multiblock mb = doc.nodes(IrNode.Multiblock.class).findFirst().orElseThrow();
        assertEquals('P', mb.charAt(1, 1, 0));
        assertEquals(Boolean.TRUE, mb.palette().get("?").any());
        assertEquals("examplemod:press_casing", mb.palette().get("C").block());
        IrNode.Block press = doc.nodes(IrNode.Block.class).findFirst().orElseThrow();
        assertEquals("north", press.stateProperties().get(0).defaultValue());
    }

    @Test
    void writerRejectsInvalidIr() {
        IrNode bad = new IrNode.Block("no_namespace", null, null, null, null, null, 99, null, null, null, null, null,
                null, null, null, null, null, Provenance.of("A.java", 1, "forge-1.19.2"), null, null);
        IrDocument doc = new IrDocument(new IrDocument.Source("forge-1.19.2", null, null), List.of(bad));
        ContractViolationException e = assertThrows(ContractViolationException.class, () -> IrIo.write(tmp.resolve("x.ir.json"), doc));
        assertTrue(e.issues().size() >= 2, e.getMessage());
        assertTrue(Files.notExists(tmp.resolve("x.ir.json")));
    }

    @Test
    void unknownNodeHasNoConfidence() throws IOException {
        IrNode.Unknown u = IrNode.Unknown.of("custom helper", "Foo.reg(x)", Provenance.of("Foo.java", 3, "forge-1.19.2"));
        IrDocument doc = new IrDocument(new IrDocument.Source("forge-1.19.2", "tiny", null), List.of(u));
        Path out = tmp.resolve("u.ir.json");
        IrIo.write(out, doc);
        String text = Files.readString(out);
        assertTrue(text.contains("\"kind\" : \"unknown\""), text);
        assertTrue(!text.contains("confidence"), text);
        assertInstanceOf(IrNode.Unknown.class, IrIo.read(out).nodes().get(0));
    }

    @Test
    void rulesRoundTrip() throws IOException {
        List<Rule> rules = RuleIo.read(example("rule/valid/mechanical-types.rule.yaml"));
        assertEquals(2, rules.size());
        assertEquals(Rule.Kind.MECHANICAL, rules.get(0).kind());
        Path out = tmp.resolve("r.rule.yaml");
        RuleIo.write(out, rules);
        assertEquals(rules, RuleIo.read(out));
        assertEquals(6, RuleIo.readAll(ContractExamplesTest.examples().resolve("rule/valid")).size());
        // RFC 006: declaration rules bind their edit, round-trip, and have no replace
        List<Rule> decl = RuleIo.read(example("rule/valid/declaration-edit.rule.yaml"));
        assertEquals("com.example.host.api.Energy", decl.get(0).match().declaration());
        assertEquals(List.of("$target(com.example.host.api.Energy)"), decl.get(0).edit().addSupertypes());
        assertEquals(null, decl.get(0).replace());
        Path dout = tmp.resolve("d.rule.yaml");
        RuleIo.write(dout, decl);
        assertEquals(decl, RuleIo.read(dout));
        Rule idiom = RuleIo.read(example("rule/valid/idiom-composite.rule.yaml")).get(0);
        assertTrue(idiom.reversibleOrDefault());
        assertEquals("$p", idiom.match().bind().recv());
    }

    @Test
    void candidatesAppendAndRead() {
        List<Candidate> log = CandidateIo.read(example("candidate/valid/log.candidates.jsonl"));
        assertEquals(2, log.size());
        Path out = tmp.resolve("c.candidates.jsonl");
        log.forEach(c -> CandidateIo.append(out, c));
        assertEquals(log, CandidateIo.read(out));
        assertTrue(ContractValidator.get().validateFile(out, null).valid());
    }

    @Test
    void detectionByContent() {
        assertEquals(Contract.SCENARIO, ContractValidator.detect(Json.json().readTree("{\"steps\":[]}")).orElseThrow());
        assertTrue(ContractValidator.detect(Json.json().readTree("{\"steps\":[],\"nodes\":[]}")).isEmpty());
    }
}
