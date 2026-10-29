package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WP9 glue on synthetic data: registry join, sound names, the report buckets, the hierarchy file format. */
class UpfrontIntegrationTest {
    static final String ITEMS = "net.minecraft.world.item.Items";
    static final String BLOCKS = "net.minecraft.world.level.block.Blocks";

    static MatchRow row(String key, Plan plan, String resolvedBy, Target t, String shim) {
        return new MatchRow(SymbolKey.parse(key), 1, "area", resolvedBy, plan, "lineage", 0.9, t, shim, null, null, null, null);
    }

    static String field(String owner, String name) {
        return "field:" + owner + "#" + name + ":L" + owner.replace('.', '/') + ";";
    }

    @Test
    void registryJoinFillsOnlyRowsWithAnExpression(@TempDir Path tmp) throws IOException {
        Path reg = tmp.resolve("registry.tsv");
        Files.writeString(reg, Flattening.HEADER + "\n"
                + "Items\tsugar_cane\tSUGAR_CANE\tITEM\tItems.reeds\t\t0.90\tflatten\n"
                + "Blocks\tgranite\tGRANITE\tBLOCK\tBlocks.stone\t1\t0.90\tstate\n"
                + "Items\tnetherite\tNETHERITE\tABSENT\t\t\t0.00\tno item\n"
                + "Items\tbed\tBED\tBLOCK_ITEM\tItem.getItemFromBlock(Blocks.bed)\t\t0.95\tidentity\n", StandardCharsets.UTF_8);
        List<MatchRow> rows = new ArrayList<>();
        rows.add(row(field(ITEMS, "SUGAR_CANE"), Plan.REGISTRY, "none", null, null));
        rows.add(row(field(BLOCKS, "GRANITE"), Plan.REGISTRY, "none", null, null));
        rows.add(row(field(ITEMS, "NETHERITE"), Plan.REGISTRY, "none", null, null));
        rows.add(row(field(ITEMS, "UNKNOWN"), Plan.REGISTRY, "none", null, null));
        rows.add(row(field(ITEMS, "BED"), Plan.REGISTRY, "none", null, null));
        RegistryJoin.Joined j = RegistryJoin.apply(new MatchTable(MatchTable.SCHEMA_VERSION, rows), reg);
        assertEquals(5, j.registryRows());
        assertEquals(2, j.withTarget());
        assertEquals(1, j.needsMeta());
        assertEquals(2, j.absent());
        Map<String, Target> by = new java.util.HashMap<>();
        j.table().rows().forEach(r -> by.put(r.key().name(), r.target()));
        assertEquals("Items.reeds", by.get("SUGAR_CANE").name());
        assertNull(by.get("GRANITE"));
        assertNull(by.get("NETHERITE"));
        assertNotNull(by.get("BED"));
        // the emitter turns the joined rows into registry rules with the imports of the expression
        RuleEmitter.Result res = new RuleEmitter(RuleEmitter.Options.defaults()).emit(j.table());
        assertEquals(2, res.files().get("upfront-registry").size());
        assertEquals("registry-needs-field-target", res.skipped().get(field(ITEMS, "UNKNOWN")));
    }

    @Test
    void expressionImports() {
        assertEquals(List.of("net.minecraft.init.Blocks", "net.minecraft.item.Item"), RegistryJoin.importsOf("Item.getItemFromBlock(Blocks.bed)"));
        assertEquals(List.of(), RegistryJoin.importsOf("\"mob.Zombie.say\""));
        assertEquals(List.of("net.minecraft.potion.Potion"), RegistryJoin.importsOf("Potion.moveSpeed"));
    }

    @Test
    void soundNamesMapOnlyWhatTheTargetHas(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("sounds.json");
        Files.writeString(f, "{\"mob.zombie.say\":{\"category\":\"hostile\",\"sounds\":[\"a\"]},\"dig.stone\":{},\"random.click\":{},\"mob.horse.zombie.hit\":{}}", StandardCharsets.UTF_8);
        Set<String> names = SoundNames.load(f);
        assertEquals(4, names.size());
        assertEquals("mob.zombie.say", SoundNames.find("entity.zombie.ambient", names));
        assertEquals("dig.stone", SoundNames.find("block.stone.break", names));
        assertEquals("random.click", SoundNames.find("ui.button.click", names));
        assertNull(SoundNames.find("entity.warden.roar", names));
        int[] found = new int[1];
        List<Flattening.Row> out = SoundNames.apply(List.of(
                new Flattening.Row("SoundEvents", "entity.zombie.ambient", "ZOMBIE_AMBIENT", "TODO", "", "", 0, "x"),
                new Flattening.Row("SoundEvents", "entity.warden.roar", "WARDEN_ROAR", "TODO", "", "", 0, "x"),
                new Flattening.Row("Items", "stick", "STICK", "ITEM", "Items.stick", "", 0.9, "identity")), names, found);
        assertEquals(1, found[0]);
        assertEquals("SOUND", out.get(0).kind());
        assertEquals("\"mob.zombie.say\"", out.get(0).expr());
        assertEquals("ABSENT", out.get(1).kind());
        assertEquals("ITEM", out.get(2).kind());
    }

    @Test
    void everySymbolFallsIntoOneBucket() {
        List<MatchRow> rows = new ArrayList<>();
        Target t = new Target("a.B", "m", "()V", false);
        rows.add(row("method:x.A#hand()V", Plan.MAP, "rule", t, null));
        rows.add(row("method:x.A#graph()V", Plan.MAP, "graph", t, null));
        rows.add(row("method:x.A#map()V", Plan.MAP, "none", t, null));
        rows.add(row("method:x.A#expand()V", Plan.EXPAND, "none", t, null));
        rows.add(row(field(ITEMS, "OK"), Plan.REGISTRY, "none", t, null));
        rows.add(row(field(ITEMS, "NO"), Plan.REGISTRY, "none", null, null));
        rows.add(row("method:x.A#moved()V", Plan.MAP, "none", t, "x.AExt"));
        rows.add(row("method:x.A#lost()V", Plan.MAP, "none", t, null));
        rows.add(row("method:x.A#mirror()V", Plan.MIRROR, "none", null, null));
        rows.add(row("method:x.A#todo()V", Plan.TODO, "none", null, null));
        rows.add(row("method:x.A#filled()V", Plan.TODO, "none", null, null));
        MatchTable table = new MatchTable(MatchTable.SCHEMA_VERSION, rows);
        Set<String> todo = Set.of("method:x.A#todo()V", "method:x.A#mirror()V");
        Set<String> skipped = Set.of(field(ITEMS, "NO"), "method:x.A#moved()V", "method:x.A#lost()V");
        Map<String, Integer> b = UpfrontReport.buckets(table, todo, skipped, r -> true);
        assertEquals(1, b.get("hand rules"));
        assertEquals(1, b.get("graph rules"));
        assertEquals(1, b.get("emitted map rules"));
        assertEquals(1, b.get("concepts (expand rules)"));
        assertEquals(1, b.get("flattening (registry rules)"));
        assertEquals(1, b.get("shim delegates"));
        assertEquals(1, b.get("bodies DB or existing shim"));
        assertEquals(2, b.get("todo (body needed)"));
        assertEquals(1, b.get("registry (no 1.7.10 counterpart)"));
        assertEquals(1, b.get("emitter skipped, no shim"));
        assertEquals(rows.size(), b.values().stream().mapToInt(Integer::intValue).sum());
        List<String> lines = UpfrontReport.render("t", b, b, null, Map.of("TODO", 1));
        assertTrue(lines.get(0).contains("11 inventory symbols"));
    }

    @Test
    void generatedClassesDoNotExtendWhatTheyCannotConstruct(@TempDir Path tmp) throws IOException {
        String base = "net.minecraft.world.Base";
        String kid = "net.minecraft.world.Kid";
        String en = "net.minecraft.world.Kind";
        String lib = "com.mojang.blaze3d.Vertex";
        List<MatchRow> rows = List.of(
                row("type:" + base, Plan.MIRROR, "none", null, null),
                row("method:" + base + "#<init>(I)V", Plan.MIRROR, "none", null, null),
                row("type:" + kid, Plan.MIRROR, "none", null, null),
                row("method:" + kid + "#size()I", Plan.MIRROR, "none", null, null),
                row("type:" + en, Plan.MIRROR, "none", null, null),
                row("method:" + en + "#label()Ljava/lang/String;", Plan.MIRROR, "none", null, null),
                row("method:" + en + "#vertex()L" + lib.replace('.', '/') + ";", Plan.MIRROR, "none", null, null));
        Path hier = tmp.resolve("hier.tsv");
        Files.writeString(hier, base + "\tjava.lang.Object\t\t1\n" + kid + "\t" + base + "\t\t1\n" + en + "\tjava.lang.Enum\t\t16417\n", StandardCharsets.UTF_8);
        ShimGen.Input in = new ShimGen.Input(new MatchTable(1, rows), Map.of(), ShimIndex.scan(List.of()), BodiesDb.load(null),
                ShimGen.readHierarchy(hier), Map.of());
        ShimGen.Output o = new ShimGen(in).run();
        String kidSrc = o.files().get("src/dev/porter/shim/gen/world/Kid.java");
        String enSrc = o.files().get("src/dev/porter/shim/gen/world/Kind.java");
        assertNotNull(kidSrc);
        assertTrue(!kidSrc.contains(" extends "), kidSrc);
        assertTrue(o.warnings().stream().anyMatch(w -> w.contains("without 'extends")), o.warnings().toString());
        assertTrue(!enSrc.contains("Enum"), enSrc);
        assertTrue(!enSrc.contains("com.mojang"), enSrc); // a 1.19.2-only library type is written as Object, not by its name
    }

    @Test
    void shimgenReadsTheApiIndexHierarchyFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("source-hier.tsv");
        Files.writeString(f, "# class\tsuper\tinterfaces\taccess\n"
                + "a.b.Plain\tjava.lang.Object\t\t33\n"
                + "a.b.Itf\tjava.lang.Object\tx.Y,x.Z\t1537\n", StandardCharsets.UTF_8);
        Map<String, ShimGen.Hier> h = ShimGen.readHierarchy(f);
        assertEquals(2, h.size());
        assertTrue(h.get("a.b.Itf").isInterface());
        assertEquals(List.of("x.Y", "x.Z"), h.get("a.b.Itf").interfaces());
    }
}
