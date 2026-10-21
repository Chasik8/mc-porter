package dev.porter.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.ir.IrIo;
import dev.porter.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class SoundsAndTexturesTest {
    @TempDir
    Path dir;

    @Test
    void soundsJsonTo1710() throws Exception {
        TestPack pack = new TestPack(dir).text("examplemod/sounds.json", """
                {
                  "machine.press": {"subtitle": "subtitles.examplemod.press", "sounds": [
                    "examplemod:machine/press", {"name": "examplemod:machine/press_alt", "volume": 0.5, "attenuation_distance": 24},
                    {"name": "examplemod:machine/loop", "stream": true, "preload": true}]},
                  "ambient.hum": {"sounds": ["examplemod:ambient/hum_é"]}
                }
                """);
        Path ir = dir.resolve("s.ir.json");
        Files.writeString(ir, """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2"}, "nodes": [
                  {"kind": "sound", "id": "examplemod:machine.press", "category": "block",
                   "sounds": [{"name": "examplemod:machine/press"}], "provenance": {"file": "Sounds.java", "version": "forge-1.19.2"}}
                ]}""");
        Context ctx = TestPack.ctx();
        ctx.ir.add(IrIo.read(ir));
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        String text = r.tree().text("examplemod/sounds.json");
        assertTrue(text.chars().allMatch(c -> c < 128), "1.7.10 reads sounds.json with the platform charset: ASCII only");
        JsonNode s = Json.json().readTree(text);
        JsonNode press = s.get("machine.press");
        assertEquals("block", press.get("category").asString());
        assertFalse(press.has("subtitle"));
        assertEquals("examplemod:machine/press", press.get("sounds").get(0).asString());
        assertEquals(0.5, press.get("sounds").get(1).get("volume").asDouble());
        assertFalse(press.get("sounds").get(1).has("attenuation_distance"));
        assertTrue(press.get("sounds").get(2).get("stream").asBoolean());
        assertFalse(press.get("sounds").get(2).has("preload"));
        assertFalse(s.get("ambient.hum").has("category"));
        assertEquals("examplemod:ambient/hum_é", s.get("ambient.hum").get("sounds").get(0).asString());
        assertTrue(r.sounds().lossless());
        assertEquals(2, r.sounds().details().get("events"));
        assertEquals(4, r.sounds().details().get("sounds"));
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.code().equals("sound-category-default") && w.subject().equals("examplemod:ambient.hum")));
        assertEquals(3, ctx.warnings.stream().filter(w -> w.code().equals("dropped-field")).count());
        assertTrue(ctx.losses.isEmpty());
    }

    @Test
    void invalidVolumeIsALoss() {
        TestPack pack = new TestPack(dir).text("examplemod/sounds.json",
                "{\"a\": {\"sounds\": [{\"name\": \"examplemod:a\", \"volume\": 0}]}}");
        Context ctx = TestPack.ctx();
        pack.convert("1.19.2", "1.7.10", ctx);
        assertEquals("sound-value-invalid", ctx.losses.get(0).code());
    }

    @Test
    void texturesMoveAndMcmetaIsStrict() {
        TestPack pack = new TestPack(dir)
                .png("examplemod/textures/block/press.png", 16, 16, 0xFF336699)
                .png("examplemod/textures/block/fluid_still.png", 16, 64, 0xFF0000FF)
                .text("examplemod/textures/block/fluid_still.png.mcmeta", "{\"animation\": {\"frametime\": 2, \"interpolate\": true}}")
                .png("examplemod/textures/item/wide.png", 32, 16, 0xFF00FF00)
                .png("examplemod/textures/gui/press.png", 256, 256, 0xFF808080)
                .text("examplemod/textures/item/gear.png.mcmeta", "{\"villager\": {\"hat\": \"full\"}}")
                .png("examplemod/textures/item/gear.png", 16, 16, 0xFF111111);
        Context ctx = TestPack.ctx();
        AssetsCommand.Result r = pack.convert("1.19.2", "1.7.10", ctx);
        AssetTree t = r.tree();
        assertTrue(t.has("examplemod/textures/blocks/press.png"));
        assertTrue(t.has("examplemod/textures/items/wide.png"));
        assertTrue(t.has("examplemod/textures/gui/press.png"));
        assertFalse(t.has("examplemod/textures/block/press.png"));
        JsonNode meta = Json.json().readTree(t.text("examplemod/textures/blocks/fluid_still.png.mcmeta"));
        assertEquals(2, meta.get("animation").get("frametime").asInt());
        assertFalse(meta.get("animation").has("interpolate"));
        assertFalse(Json.json().readTree(t.text("examplemod/textures/items/gear.png.mcmeta")).has("villager"));
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.code().equals("texture-not-square") && w.subject().endsWith("items/wide.png")));
        assertTrue(ctx.warnings.stream().noneMatch(w -> w.code().equals("texture-not-square") && w.subject().endsWith("fluid_still.png")));
        assertEquals(6, ctx.metrics.get("textures.moved"), "block/item PNGs and their .mcmeta");
    }

    @Test
    void outputIsDeterministic() throws Exception {
        new TestPack(dir.resolve("src"))
                .text("examplemod/lang/en_us.json", "{\"block.examplemod.a\": \"A\", \"gui.examplemod.b\": \"B\"}")
                .png("examplemod/textures/block/a.png", 16, 16, 0xFF123456)
                .text("examplemod/blockstates/a.json", "{\"variants\": {\"\": {\"model\": \"examplemod:block/a\"}}}")
                .text("examplemod/models/block/a.json", "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"examplemod:block/a\"}}");
        System.setProperty("porter.reports.dir", dir.resolve("reports").toString());
        for (String run : new String[] {"o1", "o2"}) {
            int code = new picocli.CommandLine(new AssetsCommand()).execute("--src", dir.resolve("src").toString(),
                    "--from", "1.19.2", "--to", "1.7.10", "--out", dir.resolve(run).toString());
            assertEquals(0, code);
        }
        try (var walk = Files.walk(dir.resolve("o1"))) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path other = dir.resolve("o2").resolve(dir.resolve("o1").relativize(p));
                assertEquals(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1),
                        new String(Files.readAllBytes(other), StandardCharsets.ISO_8859_1), p.toString());
            }
        }
        assertTrue(Files.exists(dir.resolve("o1/assets/examplemod/lang/en_US.lang")));
        assertTrue(Files.exists(dir.resolve("o1/meta/blocks.textures.ir.json")));
    }
}
