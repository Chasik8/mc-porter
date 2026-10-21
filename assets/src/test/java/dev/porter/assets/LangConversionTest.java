package dev.porter.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.assets.lang.KeyTable;
import dev.porter.assets.lang.LangFiles;
import dev.porter.core.ir.IrIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangConversionTest {
    @TempDir
    Path dir;

    private static final String EN = """
            {
              "block.examplemod.copper_press": "Copper Press",
              "item.examplemod.gear": "Gear",
              "item.examplemod.gear.tooltip": "Spins",
              "fluid_type.examplemod.steam": "Steam",
              "effect.examplemod.shaky": "Shaky",
              "gui.examplemod.press.title": "Press\\nline two",
              "itemGroup.examplemod": "Example"
            }
            """;

    @Test
    void jsonToLegacyLangWithDefaultKeys() {
        TestPack pack = new TestPack(dir).text("examplemod/lang/en_us.json", EN)
                .text("examplemod/lang/ru_ru.json", "{\"block.examplemod.copper_press\": \"Медный пресс\"}");
        Context ctx = TestPack.ctx();
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);

        assertTrue(r.tree().has("examplemod/lang/en_US.lang"));
        assertTrue(r.tree().has("examplemod/lang/ru_RU.lang"));
        assertFalse(r.tree().has("examplemod/lang/en_us.json"));
        Map<String, String> en = LangFiles.parseLang(r.tree().text("examplemod/lang/en_US.lang"), true);
        assertEquals("Copper Press", en.get("tile.copper_press.name"));
        assertEquals("Gear", en.get("item.gear.name"));
        assertEquals("Spins", en.get("item.examplemod.gear.tooltip"), "non-registry keys are kept verbatim");
        assertEquals("Steam", en.get("fluid.steam"));
        assertEquals("Shaky", en.get("potion.shaky"));
        assertEquals("Example", en.get("itemGroup.examplemod"));
        assertEquals("Press\nline two", en.get("gui.examplemod.press.title"));
        assertTrue(r.tree().text("examplemod/lang/en_US.lang").contains("gui.examplemod.press.title=Press\\nline two\n"));
        assertEquals("Медный пресс", LangFiles.parseLang(r.tree().text("examplemod/lang/ru_RU.lang"), false).get("tile.copper_press.name"));

        assertTrue(r.lang().lossless());
        assertEquals(8, r.lang().details().get("matched"));
        // every guessed key is reported once
        long guessed = ctx.warnings.stream().filter(w -> w.code().equals("lang-guessed-key")).count();
        assertEquals(4, guessed);
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.code().equals("lang-multiline")));
        assertTrue(ctx.losses.isEmpty());
    }

    @Test
    void irKeyTableWinsAndIsNotAGuess() throws Exception {
        Path ir = dir.resolve("mod.ir.json");
        Files.writeString(ir, """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2", "modId": "examplemod"}, "nodes": [
                  {"kind": "block", "id": "examplemod:copper_press", "translationKey": "block.examplemod.copper_press",
                   "provenance": {"file": "Blocks.java", "version": "forge-1.19.2"},
                   "extensions": {"porter:legacyName": "examplemod.copperPress"}},
                  {"kind": "item", "id": "examplemod:gear", "provenance": {"file": "Items.java", "version": "forge-1.19.2"}}
                ]}
                """);
        TestPack pack = new TestPack(dir).text("examplemod/lang/en_us.json", EN);
        Context ctx = TestPack.ctx();
        ctx.ir.add(IrIo.read(ir));
        ctx.legacyNames = Context.LegacyNames.NS_CAMEL;
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        Map<String, String> en = LangFiles.parseLang(r.tree().text("examplemod/lang/en_US.lang"), true);
        assertEquals("Copper Press", en.get("tile.examplemod.copperPress.name"));
        assertEquals("Gear", en.get("item.examplemod.gear.name"));
        assertEquals("Steam", en.get("fluid.examplemod.steam"), "defaults follow --legacy-names");
        assertEquals(KeyTable.Origin.IR, ctx.langKeyMap.get("block.examplemod.copper_press").origin());
        assertTrue(ctx.warnings.stream().noneMatch(w -> w.subject().equals("block.examplemod.copper_press")));
        assertTrue(r.lang().lossless());
    }

    @Test
    void keyMapOverrideAndKeepSourceKeys() {
        TestPack pack = new TestPack(dir).text("examplemod/lang/en_us.json", EN);
        Context ctx = TestPack.ctx();
        ctx.keyMapOverrides.put("item.examplemod.gear", "item.ironGear.name");
        ctx.keepSourceKeys = true;
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        Map<String, String> en = LangFiles.parseLang(r.tree().text("examplemod/lang/en_US.lang"), true);
        assertEquals("Gear", en.get("item.ironGear.name"));
        assertEquals("Gear", en.get("item.examplemod.gear"));
        assertTrue(r.lang().lossless());
    }

    @Test
    void splitFilesAreMergedAndConflictsReported() {
        TestPack pack = new TestPack(dir)
                .text("examplemod/lang/en_us.json", "{\"gui.examplemod.a\": \"A\"}")
                .text("examplemod/lang/blocks_en_us.json", "{\"block.examplemod.b\": \"B\"}")
                .text("examplemod/lang/items_en_us.json", "{\"gui.examplemod.a\": \"other A\"}")
                .text("examplemod/lang/blocks_ru_ru.json", "{\"block.examplemod.b\": \"Б\"}");
        Context ctx = TestPack.ctx();
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        assertEquals(3, ctx.metrics.get("lang.splitFilesMerged"));
        Map<String, String> en = LangFiles.parseLang(r.tree().text("examplemod/lang/en_US.lang"), true);
        assertEquals("B", en.get("tile.b.name"));
        assertEquals("other A", en.get("gui.examplemod.a"));
        assertEquals("Б", LangFiles.parseLang(r.tree().text("examplemod/lang/ru_RU.lang"), true).get("tile.b.name"));
        assertEquals(1, ctx.losses.size());
        assertEquals("lang-split-conflict", ctx.losses.get(0).code());
    }

    @Test
    void collisionsAndUnrepresentableKeysAreLosses() {
        TestPack pack = new TestPack(dir).text("examplemod/lang/en_us.json", """
                {"block.examplemod.foo": "Foo", "tile.foo.name": "Different", "weird=key": "x"}
                """);
        Context ctx = TestPack.ctx();
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        assertFalse(r.lang().lossless());
        assertTrue(ctx.losses.stream().anyMatch(l -> l.code().equals("lang-key-collision")));
        assertTrue(ctx.losses.stream().anyMatch(l -> l.code().equals("lang-unrepresentable-key")));
    }

    @Test
    void intermediateTargetsUseTheirOwnFormats() {
        TestPack pack = new TestPack(dir).text("examplemod/lang/en_us.json", EN);
        AssetsCommand.Result r12 = pack.convert("1.19.2", "1.12.2", TestPack.ctx());
        assertTrue(r12.tree().has("examplemod/lang/en_us.lang"));
        assertTrue(r12.lang().lossless());
        AssetsCommand.Result r16 = pack.convert("1.19.2", "1.16.5", TestPack.ctx());
        assertTrue(r16.tree().has("examplemod/lang/en_us.json"));
        assertTrue(r16.lang().lossless());
    }

    @Test
    void localeNames() {
        assertEquals("ru_RU", LangFiles.legacyLocale("ru_ru"));
        assertEquals("fil_PH", LangFiles.legacyLocale("fil_ph"));
        assertTrue(LangFiles.LOCALES_1_7_10.contains("uk_UA"));
        assertNull(new KeyTable(new Context()).map("item.examplemod.a.b").reason(), "dotted paths are not registry keys");
    }
}
