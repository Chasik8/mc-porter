package dev.porter.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.assets.texture.Png;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.ir.IrIo;
import dev.porter.core.json.Json;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class RenderConversionTest {
    @TempDir
    Path dir;

    private static JsonNode meta(Context ctx, String name) {
        return Json.json().readTree(new String(ctx.meta.get(name), StandardCharsets.UTF_8));
    }

    private static JsonNode block(JsonNode ir, String id) {
        for (JsonNode n : ir.get("nodes")) {
            if (n.get("id").asString().equals(id)) {
                return n;
            }
        }
        return null;
    }

    @Test
    void cubeModelsBecomePerSideIcons() {
        TestPack pack = new TestPack(dir)
                .text("examplemod/blockstates/stone_tile.json", "{\"variants\": {\"\": {\"model\": \"examplemod:block/stone_tile\"}}}")
                .text("examplemod/models/block/stone_tile.json", "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"examplemod:block/stone_tile\"}}")
                .text("examplemod/blockstates/log.json", "{\"variants\": {\"axis=y\": {\"model\": \"examplemod:block/log\"}, \"axis=x\": {\"model\": \"examplemod:block/log\", \"x\": 90, \"y\": 90}}}")
                .text("examplemod/models/block/log.json", "{\"parent\": \"block/cube_column\", \"textures\": {\"end\": \"examplemod:block/log_top\", \"side\": \"examplemod:block/log\"}}")
                .text("examplemod/blockstates/press.json", """
                        {"variants": {"facing=north,lit=false": {"model": "examplemod:block/press"},
                                      "facing=north,lit=true": {"model": "examplemod:block/press_on"}}}""")
                .text("examplemod/models/block/press.json", """
                        {"parent": "minecraft:block/orientable", "textures": {"top": "examplemod:block/press_top",
                         "front": "examplemod:block/press_front", "side": "examplemod:block/press_side"}}""")
                .text("examplemod/models/block/press_on.json", "{\"parent\": \"examplemod:block/press\", \"textures\": {\"front\": \"examplemod:block/press_front_on\"}}")
                .text("examplemod/blockstates/crate.json", "{\"variants\": {\"\": {\"model\": \"examplemod:block/crate\"}}}")
                .text("examplemod/models/block/crate.json", """
                        {"textures": {"t": "examplemod:block/crate", "particle": "#t"}, "elements": [{"from": [0,0,0], "to": [16,16,16],
                         "faces": {"down": {"texture": "#t"}, "up": {"texture": "#t"}, "north": {"texture": "#t"},
                                   "south": {"texture": "#t"}, "west": {"texture": "#t"}, "east": {"texture": "#t"}}}]}""");
        Context ctx = TestPack.ctx();
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        JsonNode ir = meta(ctx, "blocks.textures.ir.json");
        assertTrue(ContractValidator.get().validate(Contract.IR, ir).isEmpty());

        JsonNode tile = block(ir, "examplemod:stone_tile");
        assertEquals("examplemod:block/stone_tile", tile.get("textures").get("north").asString(), "IR keeps source layout");
        assertEquals("examplemod:stone_tile", tile.get("extensions").get("assets:icons").get("up").asString());
        assertEquals("cube_all", tile.get("model").get("shape").asString());
        assertFalse(tile.has("confidence"));

        JsonNode log = block(ir, "examplemod:log");
        assertEquals("examplemod:block/log_top", log.get("textures").get("up").asString());
        assertEquals("examplemod:block/log", log.get("textures").get("west").asString());
        assertTrue(log.get("extensions").get("assets:note").asString().contains("x=90"));

        JsonNode press = block(ir, "examplemod:press");
        assertEquals("examplemod:block/press_front", press.get("textures").get("north").asString());
        assertEquals("examplemod:block/press_top", press.get("textures").get("down").asString());
        assertEquals("examplemod:press_front_on",
                press.get("extensions").get("assets:stateIcons").get("facing=north,lit=true").get("north").asString());

        JsonNode crate = block(ir, "examplemod:crate");
        assertEquals("examplemod:block/crate", crate.get("textures").get("particle").asString());
        assertEquals("cube", crate.get("model").get("shape").asString());

        assertFalse(r.tree().paths().stream().anyMatch(p -> p.contains("/models/") || p.contains("/blockstates/")));
        assertEquals(0, meta(ctx, "needs-manual-render.json").size());
    }

    @Test
    void complexModelsAreListedWithObjExport() {
        TestPack pack = new TestPack(dir)
                .text("examplemod/blockstates/pipe.json", "{\"variants\": {\"\": {\"model\": \"examplemod:block/pipe\"}}}")
                .text("examplemod/models/block/pipe.json", """
                        {"textures": {"p": "examplemod:block/pipe"}, "elements": [
                          {"from": [6,0,6], "to": [10,16,10], "faces": {"north": {"texture": "#p"}, "south": {"texture": "#p", "uv": [0,0,4,16]}}},
                          {"from": [0,6,6], "to": [16,10,10], "rotation": {"origin": [8,8,8], "axis": "y", "angle": 45},
                           "faces": {"up": {"texture": "#p"}}}]}""")
                .text("examplemod/blockstates/wire.json", "{\"multipart\": [{\"apply\": {\"model\": \"examplemod:block/wire\"}}]}")
                .text("examplemod/blockstates/stairs.json", "{\"variants\": {\"\": {\"model\": \"examplemod:block/stairs\"}}}")
                .text("examplemod/models/block/stairs.json", "{\"parent\": \"minecraft:block/stairs\", \"textures\": {\"side\": \"examplemod:block/s\"}}");
        Context ctx = TestPack.ctx();
        pack.convert("1.19.2", "1.7.10", ctx);
        JsonNode manual = meta(ctx, "needs-manual-render.json");
        assertEquals(3, manual.size());
        Map<String, JsonNode> bySubject = new HashMap<>();
        manual.forEach(m -> bySubject.put(m.get("subject").asString(), m));
        assertEquals("multipart blockstate", bySubject.get("examplemod:wire").get("reason").asString());
        assertFalse(bySubject.get("examplemod:stairs").has("obj"));
        assertEquals("meta/obj/examplemod/pipe.obj", bySubject.get("examplemod:pipe").get("obj").asString());
        String obj = new String(ctx.meta.get("obj/examplemod/pipe.obj"), StandardCharsets.UTF_8);
        assertEquals(12, obj.lines().filter(l -> l.startsWith("v ")).count());
        assertEquals(3, obj.lines().filter(l -> l.startsWith("f ")).count());
        assertTrue(obj.contains("vt 0.000000 1.000000"));
        assertTrue(new String(ctx.meta.get("obj/examplemod/pipe.mtl"), StandardCharsets.UTF_8)
                .contains("map_Kd ../../../assets/examplemod/textures/blocks/pipe.png"));
    }

    @Test
    void texturePackagesWithAliasesAndStates() throws Exception {
        TestPack pack = new TestPack(dir)
                .text("examplemod/lang/en_us.json", "{\"block.examplemod.generator\": \"Generator\", \"block.examplemod.casing\": \"Casing\"}")
                .text("examplemod/textures/block/machine/generator/texture_package.json", """
                        {"active_side": "examplemod:block/machine/generator/side", "active_front": "examplemod:block/machine/generator/front_on",
                         "active_vertical": "examplemod:block/machine/generator/top",
                         "inactive_side": "examplemod:block/machine/generator/side", "inactive_front": "examplemod:block/machine/generator/front",
                         "inactive_vertical": "examplemod:block/machine/generator/top", "inactive_back": "examplemod:block/machine/generator/back"}""")
                .text("examplemod/textures/block/misc/plain/texture_package.json", "{\"all\": \"examplemod:block/misc/plain\"}")
                .text("examplemod/textures/block/crops/wheat/texture_package.json", "{\"stage_0\": \"examplemod:block/crops/wheat_0\"}")
                .png("examplemod/textures/gui/icons/yes.png", 16, 16, 0xFF00FF00)
                .png("examplemod/textures/gui/icons/no.png", 16, 16, 0xFFFF0000)
                .text("examplemod/textures/gui/icons/texture_package.json", "{\"yes\": \"examplemod:gui/icons/yes\", \"no\": \"examplemod:gui/icons/no\"}");
        Path ir = dir.resolve("b.ir.json");
        Files.writeString(ir, """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2"}, "nodes": [
                  {"kind": "block", "id": "examplemod:casing", "provenance": {"file": "B.java", "version": "forge-1.19.2"},
                   "extensions": {"porter:texturePackage": "examplemod:block/misc/plain"}},
                  {"kind": "block", "id": "examplemod:casing_frame", "provenance": {"file": "B.java", "version": "forge-1.19.2"},
                   "extensions": {"porter:texturePackage": "examplemod:block/misc/plain"}}]}""");
        Context ctx = TestPack.ctx();
        ctx.ir.add(IrIo.read(ir));
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        JsonNode out = meta(ctx, "blocks.textures.ir.json");
        JsonNode gen = block(out, "examplemod:generator");
        assertEquals(0.6, gen.get("confidence").asDouble());
        assertEquals("examplemod:block/machine/generator/back", gen.get("textures").get("north").asString(), "later entries override earlier aliases");
        assertEquals("examplemod:block/machine/generator/front", gen.get("textures").get("south").asString());
        assertEquals("examplemod:block/machine/generator/top", gen.get("textures").get("up").asString());
        assertEquals("examplemod:machine/generator/front_on", gen.get("extensions").get("assets:stateIcons").get("active").get("south").asString());
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.code().equals("texture-package-guessed-block")));

        JsonNode casing = block(out, "examplemod:casing");
        assertFalse(casing.has("confidence"), "declared in IR: certain");
        assertEquals("examplemod:block/misc/plain", casing.get("textures").get("east").asString());
        assertEquals("examplemod:block/misc/plain", block(out, "examplemod:casing_frame").get("textures").get("up").asString(),
                "two blocks naming one package both get it");
        JsonNode merged = meta(ctx, "ir/input-0.textures.ir.json");
        assertEquals("examplemod:block/misc/plain", block(merged, "examplemod:casing").get("textures").get("up").asString());

        assertEquals("examplemod:block/crops/wheat", meta(ctx, "needs-manual-render.json").get(0).get("subject").asString());

        JsonNode sheet = meta(ctx, "sheets/gui_icons.json");
        assertEquals("examplemod:textures/porter_sheets/gui_icons.png", sheet.get("sheets").get(0).get("texture").asString());
        JsonNode no = sheet.get("sprites").get("no");
        BufferedImage img = Png.read(r.tree().get("examplemod/textures/porter_sheets/gui_icons.png"));
        assertEquals(32, img.getWidth());
        assertEquals(img.getRGB(no.get("u").asInt(), no.get("v").asInt()), TestPack.image(16, 16, 0xFFFF0000).getRGB(0, 0));
    }

    @Test
    void sheetPackerPlacesWithoutOverlapAndTrims() {
        TestPack pack = new TestPack(dir);
        for (int i = 0; i < 5; i++) {
            pack.png("examplemod/textures/mob_effect/e" + i + ".png", 18, 18, 0xFF000000 | i * 0x111111);
        }
        BufferedImage padded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(10, 20, 0xFFABCDEF);
        padded.setRGB(13, 21, 0xFFABCDEF);
        try {
            Files.createDirectories(pack.assets.resolve("examplemod/textures/gui"));
            Files.write(pack.assets.resolve("examplemod/textures/gui/padded.png"), Png.write(padded));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Context ctx = TestPack.ctx();
        ctx.packSpecs.put("effects", "examplemod:mob_effect/*.png");
        ctx.packSpecs.put("trimmed", "examplemod:gui/**");
        ctx.packSpecs.put("none", "examplemod:nothing/*.png");
        ctx.trimSprites = true;
        ctx.sheetSize = 64;
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        JsonNode effects = meta(ctx, "sheets/effects.json");
        assertEquals(5, effects.get("sprites").size());
        boolean[][] used = new boolean[64][64];
        for (JsonNode s : effects.get("sprites")) {
            for (int y = 0; y < s.get("h").asInt(); y++) {
                for (int x = 0; x < s.get("w").asInt(); x++) {
                    int sheet = s.get("sheet").asInt();
                    assertEquals(0, sheet);
                    assertFalse(used[s.get("v").asInt() + y][s.get("u").asInt() + x], "overlap");
                    used[s.get("v").asInt() + y][s.get("u").asInt() + x] = true;
                }
            }
        }
        JsonNode t = meta(ctx, "sheets/trimmed.json").get("sprites").get("gui/padded");
        assertEquals(4, t.get("w").asInt());
        assertEquals(2, t.get("h").asInt());
        assertEquals(10, t.get("trim").get("x").asInt());
        assertEquals(64, t.get("trim").get("width").asInt());
        assertNotNull(r.tree().get("examplemod/textures/porter_sheets/trimmed.png"));
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.code().equals("pack-empty")));
        byte[] first = r.tree().get("examplemod/textures/porter_sheets/effects.png");
        Context again = TestPack.ctx();
        again.packSpecs.putAll(ctx.packSpecs);
        again.trimSprites = true;
        again.sheetSize = 64;
        assertArrayEquals(first, pack.convert("1.19.2", "1.7.10", again).tree().get("examplemod/textures/porter_sheets/effects.png"));
        assertNull(ctx.meta.get("sheets/none.json"));
    }
}
