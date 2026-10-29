package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.RuleTestRunner;
import dev.porter.rewrite.engine.TypeFacts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The emitter on a synthetic match table: exact YAML, rule tests, skipped rows. */
class RuleEmitterTest {
    static final String BP = "Lnet/minecraft/core/BlockPos;";
    static final String ENT = "net.minecraft.world.entity.Entity";
    static final String LVL = "net.minecraft.world.level.Level";

    static MatchRow row(String key, Plan plan, String resolvedBy, Target t, String shim) {
        return new MatchRow(SymbolKey.parse(key), 1, "area", resolvedBy, plan, "lineage", 0.9, t, shim, null, null, null, null);
    }

    static MatchTable table() {
        List<MatchRow> rows = new ArrayList<>();
        rows.add(row("type:" + LVL, Plan.MAP, "none", new Target("net.minecraft.world.World", "", "", false), null));
        rows.add(row("type:" + ENT, Plan.MAP, "none", new Target("net.minecraft.entity.Entity", "", "", false), null));
        rows.add(row("method:" + ENT + "#getId()I", Plan.MAP, "none", new Target("net.minecraft.entity.Entity", "getEntityId", "()I", false), null));
        rows.add(row("method:" + LVL + "#getBlockEntity(" + BP + ")Lnet/minecraft/world/level/block/entity/BlockEntity;", Plan.EXPAND, "none",
                new Target("net.minecraft.world.World", "getTileEntity", "(III)Lnet/minecraft/tileentity/TileEntity;", false), null));
        rows.add(row("method:" + LVL + "#getBlockState(" + BP + ")Lnet/minecraft/world/level/block/state/BlockState;", Plan.EXPAND, "none",
                new Target("net.minecraft.world.World", "getBlock", "(III)Lnet/minecraft/block/Block;", false), null));
        rows.add(row("field:net.minecraft.world.item.Items#IRON_INGOT:Lnet/minecraft/world/item/Item;", Plan.REGISTRY, "none",
                new Target("net.minecraft.init.Items", "iron_ingot", "Lnet/minecraft/item/Item;", true), null));
        rows.add(row("type:net.minecraft.world.level.block.state.properties.IntegerProperty", Plan.MIRROR, "none", null,
                "dev.porter.shim.mc.IntegerProperty"));
        rows.add(row("method:" + LVL + "#isLoaded(" + BP + ")Z", Plan.HINT, "none", null, "dev.porter.shim.gen.net.minecraft.world.level.LevelExt"));
        rows.add(row("method:" + LVL + "#getSeaLevel()I", Plan.MAP, "rule", new Target(LVL, "x", "()I", false), null));
        rows.add(row("method:" + ENT + "#unknown()V", Plan.TODO, "none", null, null));
        rows.add(row("method:" + "net.minecraft.world.level.Other" + "#foo()Z", Plan.MAP, "none",
                new Target("net.minecraft.entity.Other", "foo", "()Z", false), null));
        return new MatchTable(1, rows);
    }

    static RuleEmitter.Result emit() throws Exception {
        Concepts c = Concepts.load(Path.of(RuleEmitterTest.class.getResource("/upfront/concepts.yaml").toURI()));
        return new RuleEmitter(RuleEmitter.Options.defaults().withConcepts(c)).emit(table());
    }

    @Test
    void emitsTheExpectedFilesAndSkipsTheRest() throws Exception {
        RuleEmitter.Result res = emit();
        assertEquals(List.of("upfront-expand", "upfront-ext", "upfront-map", "upfront-mirror", "upfront-registry"),
                res.files().keySet().stream().sorted().toList());
        assertEquals(Map.of("method:net.minecraft.world.level.Other#foo()Z", "moved-instance-method"), res.skipped());
        for (List<Rule> rules : res.files().values()) {
            for (Rule r : rules) {
                assertEquals("upfront", r.provenance().source());
                assertTrue(r.provenance().ref().matches(".* 0\\.90"), r.provenance().ref());
            }
        }
        List<String> replaces = res.files().get("upfront-expand").stream().map(Rule::replace).sorted().toList();
        assertEquals(List.of(
                "$r.getTileEntity($a0.getX(), $a0.getY(), $a0.getZ())",
                "BlockState.of($r.getBlock($a0.getX(), $a0.getY(), $a0.getZ()))",
                "BlockState.of(LevelExt.getBlockState($r, $a0))",
                "LevelExt.getBlockEntity($r, $a0)").stream().sorted().toList(), replaces);
    }

    @Test
    void goldenYaml(@TempDir Path tmp) throws Exception {
        RuleEmitter.write(emit(), tmp);
        StringBuilder sb = new StringBuilder();
        for (String f : List.of("upfront-expand", "upfront-ext", "upfront-map", "upfront-mirror", "upfront-registry")) {
            sb.append("# ").append(f).append('\n').append(Files.readString(tmp.resolve(f + ".rule.yaml"), StandardCharsets.UTF_8));
        }
        if (System.getenv("PORTER_UPDATE_GOLDEN") != null) {
            Files.writeString(Path.of(System.getenv("PORTER_UPDATE_GOLDEN")), sb.toString(), StandardCharsets.UTF_8);
        }
        String golden = new String(RuleEmitterTest.class.getResourceAsStream("/upfront/emitted-golden.yaml").readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(golden, sb.toString());
    }

    @Test
    void emittedRulesPassTheirOwnTests(@TempDir Path tmp) throws Exception {
        RuleEmitter.Result res = emit();
        List<Rule> all = res.files().values().stream().flatMap(List::stream).toList();
        List<RuleTestRunner.Failure> f = new RuleTestRunner(NameTable.empty(), TypeFacts.bundledPlus("forge-1.19.2", List.of()),
                Rewriter.Options.defaults()).run(all);
        assertTrue(f.isEmpty(), () -> String.join("\n", f.stream().map(RuleTestRunner.Failure::message).toList()));
        RuleEmitter.write(res, tmp);
        for (String name : res.files().keySet()) {
            assertEquals(res.files().get(name).size(), RuleIo.read(tmp.resolve(name + ".rule.yaml")).size()); // schema-valid
        }
    }

    @Test
    void getterToFieldRulesPassTheirOwnTests() {
        List<MatchRow> rows = List.of(
                row("method:" + ENT + "#getXSize()I", Plan.MAP, "none", new Target("net.minecraft.entity.Entity", "xSize", "I", false), null),
                row("method:" + ENT + "#instance()Lnet/minecraft/world/entity/Entity;", Plan.MAP, "none",
                        new Target("net.minecraft.entity.Entity", "instance", "Lnet/minecraft/entity/Entity;", true), null));
        RuleEmitter.Result res = new RuleEmitter(new RuleEmitter.Options("1.19.2", "1.7.10", "forge-1.19.2", "forge-1.7.10",
                RuleEmitter.Options.defaults().concepts(), Map.of(), Map.of(ENT, "net.minecraft.entity.Entity"))).emit(new MatchTable(1, rows));
        assertEquals(Map.of(), res.skipped());
        List<Rule> all = res.files().get("upfront-map");
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(r -> "$r.xSize".equals(r.replace())));
        assertTrue(all.stream().anyMatch(r -> "Entity.instance".equals(r.replace())));
        List<RuleTestRunner.Failure> f = new RuleTestRunner(NameTable.empty(), TypeFacts.bundledPlus("forge-1.19.2", List.of()),
                Rewriter.Options.defaults()).run(all);
        assertTrue(f.isEmpty(), () -> String.join("\n", f.stream().map(RuleTestRunner.Failure::message).toList()));
    }

    @Test
    void deterministic(@TempDir Path tmp) throws Exception {
        RuleEmitter.write(emit(), tmp.resolve("a"));
        RuleEmitter.write(emit(), tmp.resolve("b"));
        assertEquals(Files.readString(tmp.resolve("a/upfront-map.rule.yaml")), Files.readString(tmp.resolve("b/upfront-map.rule.yaml")));
    }
}
