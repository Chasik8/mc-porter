package dev.porter.testkit.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.json.Json;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Plan compilation, trace building, GUI checks and trace diffs on synthetic inputs (no Minecraft needed). */
class PipelineTest {
    static Path res(String name) {
        try {
            return Path.of(PipelineTest.class.getResource("/synthetic/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    final Scenario sc = Scenario.read(res("press.scenario.yaml")).get(0);
    final IrIndex ir = IrIndex.read(List.of(res("press.ir.json")));
    final IdMap ids1710 = IdMap.read(res("ids-1.7.10.yaml"));

    @Test
    void breakBlockAndExplodeStepsCompileToOps() {
        Scenario sc2 = Scenario.read(res("break.scenario.yaml")).get(0);
        ObjectNode plan = new PlanCompiler(sc2, "forge-1.7.10", ids1710, ir).compile();
        JsonNode player = plan.path("ops").get(1);
        assertEquals("break", player.path("op").asString());
        assertEquals("player", player.path("by").asString());
        assertEquals(true, player.has("holding"));
        assertEquals(true, player.path("drops").asBoolean());
        JsonNode world = plan.path("ops").get(3);
        assertEquals("world", world.path("by").asString());
        assertEquals(false, world.path("drops").asBoolean());
        JsonNode boom = plan.path("ops").get(4);
        assertEquals("explode", boom.path("op").asString());
        assertEquals(3.0, boom.path("power").asDouble());
        assertEquals(true, boom.path("fire").asBoolean());
        assertEquals(true, boom.path("destroy").asBoolean());
    }

    @Test
    void insertEnergyStepCompilesToAnEnergyOp() {
        Scenario energy = Scenario.read(res("energy.scenario.yaml")).get(0);
        ObjectNode plan = new PlanCompiler(energy, "forge-1.7.10", ids1710, ir).compile();
        JsonNode op = plan.path("ops").get(1);
        assertEquals("energy", op.path("op").asString());
        assertEquals(128.5, op.path("amount").asDouble());
        assertEquals(32.0, op.path("voltage").asDouble());
        assertEquals("up", op.path("side").asString());
        JsonNode call = plan.path("ops").get(3);
        assertEquals("invoke", call.path("op").asString());
        assertEquals("onClientDataReceived", call.path("method").asString());
        assertEquals("$player", call.path("args").get(0).asString());
    }

    @Test
    void compilesPlanWithRotatedMultiblockIdsAndImplicitSamples() {
        ObjectNode plan = new PlanCompiler(sc, "forge-1.7.10", ids1710, ir).compile();
        JsonNode ops = plan.path("ops");
        List<String> kinds = new ArrayList<>();
        ops.forEach(o -> kinds.add(o.path("op").asString()));
        // 6 casings in layer 0 + 2 in layer 1 (? skipped) + controller last
        assertEquals(List.of("place", "place", "place", "place", "place", "place", "place", "place", "place",
                "insert", "fill", "sample", "tick", "sample", "open_gui"), kinds);
        JsonNode controller = ops.get(8);
        assertEquals("examplemod:machine", controller.path("block").path("id").asString());
        assertEquals(2, controller.path("block").path("meta").asInt());
        assertEquals("east", controller.path("facing").asString());
        assertEquals("[1,65,1]", controller.path("at").toString().replace(" ", ""));
        // controller facing state rotated too
        assertEquals("east", controller.path("block").path("state").path("facing").asString());
        // keyMap: canonical Progress -> 1.7.10 progress
        assertEquals("nbt:[1,65,0]/progress", ops.get(11).path("keys").get(0).asString());
        // assert keys are sampled implicitly right before the assert
        assertTrue(ops.get(13).path("implicit").asBoolean());
        assertEquals("item:[1,65,0]#1", ops.get(13).path("keys").get(0).asString());
        // vanilla fluids lose their namespace on 1.7.10
        assertEquals("water", ops.get(10).path("fluid").path("id").asString());
        assertEquals(20, sc.stepTicks()[5]);
    }

    @Test
    void rotationKeepsFootprint() {
        assertEquals("[1, 0]", java.util.Arrays.toString(PlanCompiler.rotate(0, 0, 3, 2, 1)));
        assertEquals("[0, 2]", java.util.Arrays.toString(PlanCompiler.rotate(2, 1, 3, 2, 1)));
        assertEquals("[2, 1]", java.util.Arrays.toString(PlanCompiler.rotate(0, 0, 3, 2, 2)));
        assertEquals("west", PlanCompiler.turn("north", 3));
    }

    static ObjectNode raw(String version, String progressKey, int dustCount, int slot1Y) {
        ObjectNode r = Json.json().createObjectNode();
        r.put("status", "completed");
        r.put("ticks", 20);
        ArrayNode s = r.putArray("samples");
        for (int t = 0; t <= 20; t += 10) {
            ObjectNode a = s.addObject();
            a.put("tick", t);
            a.put("key", progressKey);
            a.put("value", t * 5);
            ObjectNode e = s.addObject();
            e.put("tick", t);
            e.put("key", "field:[1,65,0]/energy");
            e.put("value", version.equals("forge-1.7.10") ? 1000 + t : 1000);
        }
        ObjectNode last = s.addObject();
        last.put("tick", 20);
        last.put("key", "item:[1,65,0]#1");
        ObjectNode item = last.putObject("value");
        item.put("item", version.equals("forge-1.7.10") ? "examplemod:items" : "examplemod:dust");
        item.put("count", dustCount);
        if (version.equals("forge-1.7.10")) {
            item.put("damage", 7);
        }
        ArrayNode gui = r.putArray("gui");
        ObjectNode g = gui.addObject();
        g.put("step", 6);
        g.put("menu", "examplemod:press");
        g.putObject("size").put("width", 176).put("height", 166);
        ArrayNode slots = g.putArray("slots");
        slots.addObject().put("index", 0).put("x", 56).put("y", 17);
        slots.addObject().put("index", 1).put("x", 116).put("y", slot1Y);
        for (int i = 0; i < 36; i++) {
            slots.addObject().put("index", 2 + i).put("x", 8 + (i % 9) * 18).put("y", i < 27 ? 84 + (i / 9) * 18 : 142).put("player", true);
        }
        return r;
    }

    @Test
    void buildsValidTracesAndDiffsThemWithKeyMapAndTolerances() {
        ObjectNode t19 = new TraceBuilder(sc, "forge-1.19.2", IdMap.empty(), ir).build(raw("forge-1.19.2", "nbt:[1,65,0]/Progress", 2, 35));
        ObjectNode t17 = new TraceBuilder(sc, "forge-1.7.10", ids1710, ir).build(raw("forge-1.7.10", "nbt:[1,65,0]/progress", 2, 35));
        assertTrue(ContractValidator.get().validate(Contract.TRACE, t19).isEmpty(), () -> ContractValidator.get().validate(Contract.TRACE, t19).toString());
        assertTrue(ContractValidator.get().validate(Contract.TRACE, t17).isEmpty());
        assertEquals("passed", t19.path("status").asString(), t19.toString());
        assertEquals("passed", t17.path("status").asString(), t17.toString());
        // 1.7.10 registry name + meta normalised back to the IR id
        JsonNode dust = null;
        for (JsonNode x : t17.path("samples")) {
            if (x.path("key").asString().equals("item:[1,65,0]#1")) {
                dust = x.path("value");
            }
        }
        assertEquals("examplemod:dust", dust.path("item").asString());
        assertFalse(dust.has("damage"));
        TraceDiff.Result d = TraceDiff.compare(sc, t19, t17);
        assertTrue(d.equal(), d.report().toString());
    }

    @Test
    void reportsFailuresAndGuiMismatches() {
        ObjectNode t = new TraceBuilder(sc, "forge-1.19.2", IdMap.empty(), ir).build(raw("forge-1.19.2", "nbt:[1,65,0]/Progress", 1, 36));
        assertEquals("failed", t.path("status").asString());
        String msg = t.path("message").asString();
        assertTrue(msg.contains("two dusts"), msg);
        assertTrue(msg.contains("slot 1 expected at (116,35), found (116,36)"), msg);

        ObjectNode ok = new TraceBuilder(sc, "forge-1.19.2", IdMap.empty(), ir).build(raw("forge-1.19.2", "nbt:[1,65,0]/Progress", 2, 35));
        ObjectNode other = ok.deepCopy();
        ((ObjectNode) other.path("samples").get(0)).put("value", 7); // Progress at tick 0: 0 vs 7 (abs 2)
        TraceDiff.Result d = TraceDiff.compare(sc, ok, other);
        assertFalse(d.equal());
        assertEquals(d.report().path("samples").asInt() - 1, d.report().path("matched").asInt());
    }

    @Test
    void assertsSeeOnlySamplesTakenBeforeThemInTheSameTick() throws Exception {
        Path f = Files.createTempFile("same-tick", ".scenario.yaml");
        Files.writeString(f, String.join("\n",
                "schemaVersion: 1", "id: same-tick", "world: flat-void", "steps:",
                "  - sample: { what: [\"field:[0,0,0]/n\"] }",
                "  - assert: { expr: \"field:[0,0,0]/n == 1\" }",
                "  - interact: { at: [0, 0, 0] }",
                "  - sample: { what: [\"field:[0,0,0]/n\"] }",
                "  - assert: { expr: \"field:[0,0,0]/n == 2\" }", ""));
        Scenario s = Scenario.read(f).get(0);
        ObjectNode raw = Json.json().createObjectNode();
        raw.put("status", "completed");
        raw.put("ticks", 0);
        ArrayNode samples = raw.putArray("samples");
        samples.addObject().put("tick", 0).put("step", 0).put("key", "field:[0,0,0]/n").put("value", 1);
        samples.addObject().put("tick", 0).put("step", 1).put("key", "field:[0,0,0]/n").put("value", 1);
        samples.addObject().put("tick", 0).put("step", 3).put("key", "field:[0,0,0]/n").put("value", 2);
        samples.addObject().put("tick", 0).put("step", 4).put("key", "field:[0,0,0]/n").put("value", 2);
        ObjectNode t = new TraceBuilder(s, "forge-1.19.2", IdMap.empty(), ir).build(raw);
        assertEquals("passed", t.path("status").asString(), t.toString());
        assertEquals(2, t.path("samples").size()); // exact repeats in one tick collapse, distinct values stay
        TraceDiff.Result d = TraceDiff.compare(s, t, t);
        assertTrue(d.equal());
        Files.delete(f);
    }

    @Test
    void timeoutTraceIsValid() {
        ObjectNode t = new TraceBuilder(sc, "forge-1.7.10", ids1710, ir).failedRun("timeout", "no exit within 10 s");
        assertTrue(ContractValidator.get().validate(Contract.TRACE, t).isEmpty());
    }
}
