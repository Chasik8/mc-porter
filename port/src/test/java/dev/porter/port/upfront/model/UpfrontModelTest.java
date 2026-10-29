package dev.porter.port.upfront.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpfrontModelTest {

    private static final String GET_BLOCK_ENTITY =
            "method:net.minecraft.world.level.Level#getBlockEntity(Lnet/minecraft/core/BlockPos;)"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;";

    @Test
    void parsesTypeMethodAndFieldKeys() {
        SymbolKey type = SymbolKey.parse("type:net.minecraft.world.item.ItemStack");
        assertEquals(Kind.TYPE, type.kind());
        assertEquals("net.minecraft.world.item.ItemStack", type.owner());
        assertEquals("", type.name());
        assertEquals("", type.desc());

        SymbolKey method = SymbolKey.parse(GET_BLOCK_ENTITY);
        assertEquals(Kind.METHOD, method.kind());
        assertEquals("net.minecraft.world.level.Level", method.owner());
        assertEquals("getBlockEntity", method.name());
        assertEquals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", method.desc());

        SymbolKey field = SymbolKey.parse("field:net.minecraft.world.entity.Entity#tickCount:I");
        assertEquals(Kind.FIELD, field.kind());
        assertEquals("tickCount", field.name());
        assertEquals("I", field.desc());
    }

    @Test
    void parsesNestedClassesArraysPrimitivesAndConstructors() {
        SymbolKey nested = SymbolKey.parse("type:net.minecraft.world.level.block.state.BlockBehaviour$Properties");
        assertEquals("net.minecraft.world.level.block.state.BlockBehaviour$Properties", nested.owner());

        SymbolKey arrays = SymbolKey.parse("method:a.b.C#pick([I[[Ljava/lang/String;J)[Ljava/lang/Object;");
        assertEquals("([I[[Ljava/lang/String;J)[Ljava/lang/Object;", arrays.desc());

        SymbolKey ctor = SymbolKey.parse("method:a.b.C$D#<init>(ZDLa/b/E$F;)V");
        assertEquals("<init>", ctor.name());
        assertEquals("a.b.C$D", ctor.owner());

        SymbolKey arrayField = SymbolKey.parse("field:a.b.C#table:[Ljava/util/Map;");
        assertEquals("[Ljava/util/Map;", arrayField.desc());
    }

    @Test
    void toStringRoundTripsAndFactoriesAgree() {
        for (String text : List.of("type:a.b.C", "type:a.b.C$D", GET_BLOCK_ENTITY,
                "method:a.b.C#<init>()V", "field:a.b.C#x:[I")) {
            assertEquals(text, SymbolKey.parse(text).toString());
        }
        assertEquals(SymbolKey.parse("field:a.b.C#x:I"), SymbolKey.field("a.b.C", "x", "I"));
        assertEquals("type:a.b.C", SymbolKey.type("a.b.C").toString());
    }

    @Test
    void rejectsMalformedKeys() {
        for (String bad : List.of("class:a.b.C", "type:", "type:a/b/C", "method:a.b.C#foo", "method:a.b.C#foo]",
                "method:a.b.C#(I)V", "method:a.b.C#foo(Q)V", "field:a.b.C#x", "field:a.b.C#:I", "field:a.b.C#x:Q",
                "a.b.C")) {
            assertThrows(IllegalArgumentException.class, () -> SymbolKey.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new SymbolKey(Kind.TYPE, "a.b.C", "x", ""));
        assertThrows(IllegalArgumentException.class, () -> SymbolKey.parse(null));
    }

    @Test
    void apiInventoryRoundTripsThroughJson(@TempDir Path dir) throws Exception {
        ApiSymbol sym = new ApiSymbol(SymbolKey.parse(GET_BLOCK_ENTITY), 3, Set.of("source", "bytecode"), 1,
                "net.minecraft.world.level.Level", List.of("example.mod.Machine"), null);
        ApiInventory inv = new ApiInventory(1, "forge-1.19.2", "addon",
                new ApiInventory.Sources("mod.jar", "src", List.of("classes.txt")),
                List.of(sym), new ApiInventory.Summary(0, 1, Map.of("srg", 1)));
        Path file = dir.resolve("api-inventory.json");
        inv.write(file);
        ApiInventory back = ApiInventory.read(file);
        assertEquals(inv, back);
        assertEquals(List.of("bytecode", "source"), List.copyOf(back.symbols().get(0).origin()));
        assertFalse(Files.readString(file).contains("\"signature\""));
    }

    @Test
    void apiInventoryWriteIsSortedAndDeterministic(@TempDir Path dir) throws Exception {
        ApiSymbol b = new ApiSymbol(SymbolKey.type("b.B"), 1, Set.of(), -1, "b.B", null, null);
        ApiSymbol a = new ApiSymbol(SymbolKey.type("a.A"), 1, Set.of(), -1, "a.A", null, null);
        ApiInventory unsorted = new ApiInventory(1, "forge-1.19.2", "addon", null, List.of(b, a),
                new ApiInventory.Summary(2, 0, Map.of("srg", 0, "forge", 0)));
        Path first = dir.resolve("one.json");
        Path second = dir.resolve("two.json");
        unsorted.write(first);
        ApiInventory.read(first).write(second);
        assertEquals(Files.readString(first), Files.readString(second));
        assertTrue(Files.readString(first).indexOf("type:a.A") < Files.readString(first).indexOf("type:b.B"));
    }

    @Test
    void matchTableTsvRoundTripKeepsColumns(@TempDir Path dir) throws Exception {
        MatchTable table = sampleTable();
        Path tsv = dir.resolve("match-table.tsv");
        table.writeTsv(tsv);
        List<String> lines = Files.readAllLines(tsv);
        assertEquals(String.join("\t", MatchTable.TSV_COLUMNS), lines.get(0));
        assertEquals(5, lines.size());

        MatchTable back = MatchTable.readTsv(tsv);
        assertEquals(table.sortedRows().size(), back.rows().size());
        for (int i = 0; i < back.rows().size(); i++) {
            MatchRow want = table.sortedRows().get(i);
            MatchRow got = back.rows().get(i);
            assertEquals(want.key(), got.key());
            assertEquals(want.plan(), got.plan());
            assertEquals(want.confidence(), got.confidence());
            assertEquals(want.target(), got.target());
            assertEquals(want.area(), got.area());
            assertEquals(want.shim(), got.shim());
            assertEquals(want.note(), got.note());
        }
        assertNull(back.rows().get(0).target());
        assertNull(back.rows().get(0).area());
    }

    @Test
    void matchTableJsonRoundTripKeepsEverything(@TempDir Path dir) throws Exception {
        MatchTable table = sampleTable();
        Path json = dir.resolve("match-table.json");
        table.writeJson(json);
        MatchTable back = MatchTable.read(json);
        assertEquals(table.sortedRows(), back.rows());
        assertEquals(MatchTable.SCHEMA_VERSION, back.schemaVersion());
        assertFalse(Files.readString(json).contains("\"kind\""));
    }

    @Test
    void matchTableTsvIsSortedAndTabsAreSanitised(@TempDir Path dir) throws Exception {
        MatchRow noisy = new MatchRow(SymbolKey.type("z.Z"), 1, null, "none", Plan.TODO, "none:no-owner", 0.0,
                null, null, null, null, null, "tab\there\nnewline");
        MatchRow first = new MatchRow(SymbolKey.type("a.A"), 1, "block", "none", Plan.MIRROR, "mirror", 1.0,
                null, "dev.porter.shim.A", null, null, null, null);
        Path tsv = dir.resolve("t.tsv");
        new MatchTable(1, List.of(noisy, first)).writeTsv(tsv);
        List<String> lines = Files.readAllLines(tsv);
        // a null area sorts first, so the noisy row is line 1 and the block row line 2
        assertTrue(lines.get(1).startsWith("type:z.Z\t"));
        assertTrue(lines.get(1).contains("tab here newline"));
        assertEquals(15, lines.get(1).split("\t", -1).length);
        assertTrue(lines.get(2).startsWith("type:a.A\t"));
    }

    @Test
    void matchRowKindComesFromKeyAndFeaturesAreSorted() {
        MatchRow row = new MatchRow(SymbolKey.field("a.A", "x", "I"), 1, null, "none", Plan.HINT, "lineage-sig",
                0.4, null, null, null, Map.of("ret", 1.0, "arity", 0.0), null, null);
        assertEquals(Kind.FIELD, row.kind());
        assertEquals(List.of("arity", "ret"), List.copyOf(row.features().keySet()));
        assertEquals(List.of(), row.alternatives());
    }

    @Test
    void planNamesMatchTheContract() {
        assertEquals(Set.of("MAP", "EXPAND", "MIRROR", "REGISTRY", "HINT", "TODO"),
                Set.of(Plan.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals("TODO", Json.json().readValue("\"TODO\"", Plan.class).name());
    }

    private static MatchTable sampleTable() {
        Target target = new Target("net.minecraft.world.World", "getTileEntity", "(III)Lnet/minecraft/tileentity/TileEntity;",
                false);
        MatchRow mapped = new MatchRow(SymbolKey.parse(GET_BLOCK_ENTITY), 3, "block", "none", Plan.EXPAND,
                "lineage-concept", 0.9, target, null, null, Map.of("name", 0.67, "arity", 1.0),
                List.of(new Alt("net.minecraft.world.World", "blockExists", "(III)Z", 0.65)), null);
        MatchRow todo = new MatchRow(SymbolKey.parse("method:a.b.C#isLoaded(Lnet/minecraft/core/BlockPos;)Z"), 1,
                "block", "none", Plan.TODO, "none:below-threshold", 0.0, null, null, null, null, null,
                "two candidates within margin");
        MatchRow registry = new MatchRow(SymbolKey.field("net.minecraft.world.item.Items", "STONE",
                "Lnet/minecraft/world/item/Item;"), 2, "registry", "none", Plan.REGISTRY, "registry", 0.5,
                new Target("net.minecraft.init.Blocks", "stone", "Lnet/minecraft/block/Block;", true), "shim", "rule-1",
                null, null, null);
        MatchRow typeRow = new MatchRow(SymbolKey.type("net.minecraft.world.item.ItemStack"), 5, null, "rule",
                Plan.MIRROR, "mirror", 1.0, null, "dev.porter.shim.ItemStack", "ruleA", null, null, null);
        return new MatchTable(MatchTable.SCHEMA_VERSION, List.of(mapped, todo, registry, typeRow));
    }
}
