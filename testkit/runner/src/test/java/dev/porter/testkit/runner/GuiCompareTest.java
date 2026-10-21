package dev.porter.testkit.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.json.Json;
import dev.porter.testkit.runner.gui.GuiCaptures;
import dev.porter.testkit.runner.gui.GuiCaptures.Capture;
import dev.porter.testkit.runner.gui.GuiCompare;
import dev.porter.testkit.runner.gui.GuiReport;
import dev.porter.testkit.runner.gui.Lang;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** GUI comparator on synthetic captures (no Minecraft): verdicts, masks, slots, texts, indicators, structural mode. */
class GuiCompareTest {
    static final Lang NO_LANG = Lang.of(List.of());

    /** A 60x40 "GUI": grey panel, dark border, one slot at (10,10), optional bar of the given height at x=40. */
    static BufferedImage gui(int bar, int tint) {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 60; x++) {
                boolean border = x == 0 || y == 0 || x == 59 || y == 39;
                int c = border ? 0xff373737 : 0xffc6c6c6 + tint;
                if (x >= 10 && x < 26 && y >= 10 && y < 26) {
                    c = 0xff8b8b8b;
                }
                if (x >= 40 && x < 46 && y >= 35 - bar && y < 35) {
                    c = 0xff2050d0;
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    static Capture cap(String name, BufferedImage img, String json) {
        ObjectNode e = (ObjectNode) Json.json().readTree(json);
        e.put("capture", name);
        return new Capture(name, e, img, null);
    }

    static final String SLOTS = "{\"screenClass\":\"x.Gui\",\"size\":{\"width\":60,\"height\":40},"
            + "\"slots\":[{\"index\":0,\"x\":10,\"y\":10}],\"texts\":[{\"text\":\"Press\",\"x\":28,\"y\":3,\"width\":25,\"color\":\"404040\"}],\"check\":{}";

    @Test
    void identicalCapturesAreEqual() {
        GuiCompare.Result r = GuiCompare.diff(cap("g", gui(0, 0), SLOTS + "}"), cap("g", gui(0, 0), SLOTS + "}"), null, null, NO_LANG, NO_LANG);
        assertEquals("equal", r.verdict(), r.findings().toString());
        assertEquals(0.0, r.diffRatio());
    }

    @Test
    void slightTextureStyleDifferenceIsAcceptableAndLargeIsBroken() {
        // different panel shade over the whole GUI (texture style) -> differs everywhere -> broken by default
        GuiCompare.Result big = GuiCompare.diff(cap("g", gui(0, 0), SLOTS + "}"), cap("g", gui(0, 0x303030), SLOTS + "}"), null, null, NO_LANG, NO_LANG);
        assertEquals("broken", big.verdict());
        // the same with a generous limit -> acceptable
        String loose = SLOTS + ",\"check\":{\"maxDiff\":1.0}}";
        GuiCompare.Result ok = GuiCompare.diff(cap("g", gui(0, 0), loose.replace(",\"check\":{}", "")), cap("g", gui(0, 0x303030), loose.replace(",\"check\":{}", "")),
                null, null, NO_LANG, NO_LANG);
        assertEquals("acceptable-diff", ok.verdict(), ok.findings().toString());
        // a tiny shade change stays under the pixel threshold -> equal
        GuiCompare.Result tiny = GuiCompare.diff(cap("g", gui(0, 0), SLOTS + "}"), cap("g", gui(0, 0x030303), SLOTS + "}"), null, null, NO_LANG, NO_LANG);
        assertEquals("equal", tiny.verdict());
    }

    @Test
    void movedSlotAndMissingTextAreBroken() {
        String moved = SLOTS.replace("\"x\":10,\"y\":10", "\"x\":12,\"y\":10").replace("Press", "Prss") + "}";
        GuiCompare.Result r = GuiCompare.diff(cap("g", gui(0, 0), SLOTS + "}"), cap("g", gui(0, 0), moved), null, null, NO_LANG, NO_LANG);
        assertEquals("broken", r.verdict());
        String all = r.findings().toString();
        assertTrue(all.contains("machine slots of A missing in B at (10,10)"), all);
        assertTrue(all.contains("text of A not drawn in B: \"Press\""), all);
    }

    @Test
    void textMissingInAMaskedRegionIsAcceptable() {
        String masked = SLOTS.replace("\"check\":{}", "\"check\":{\"mask\":[[26,0,34,12]]}") + "}";
        String without = masked.replace("\"texts\":[{\"text\":\"Press\",\"x\":28,\"y\":3,\"width\":25,\"color\":\"404040\"}]", "\"texts\":[]");
        GuiCompare.Result r = GuiCompare.diff(cap("g", gui(0, 0), masked), cap("g", gui(0, 0), without), null, null, NO_LANG, NO_LANG);
        assertEquals("acceptable-diff", r.verdict(), r.findings().toString());
        assertTrue(r.findings().toString().contains("masked region: text of A not drawn in B: \"Press\""), r.findings().toString());
    }

    @Test
    void dynamicIndicatorsMustMatch() {
        String dyn = SLOTS + ",\"check\":{\"dynamicFrom\":\"base\"}}";
        dyn = dyn.replace(",\"check\":{},", ",");
        Capture aBase = cap("base", gui(0, 0), SLOTS + "}");
        Capture bBase = cap("base", gui(0, 0), SLOTS + "}");
        Capture aFull = cap("g", gui(20, 0), dyn);
        GuiCompare.Result ok = GuiCompare.diff(aFull, cap("g", gui(18, 0), dyn), aBase, bBase, NO_LANG, NO_LANG);
        assertEquals(1, ok.indicatorsA().size());
        assertEquals(1, ok.indicatorsB().size());
        assertTrue(!ok.verdict().equals("broken") || !ok.findings().toString().contains("does not change"), ok.findings().toString());
        // the port's gauge never moves -> broken
        GuiCompare.Result bad = GuiCompare.diff(aFull, cap("g", gui(0, 0), dyn), aBase, bBase, NO_LANG, NO_LANG);
        assertEquals("broken", bad.verdict());
        assertTrue(bad.findings().toString().contains("does not change in B"));
    }

    @Test
    void structuralModeChecksTextBoxesKeysAndEmptiness() {
        String s = "{\"screenClass\":\"x.Book\",\"size\":{\"width\":60,\"height\":40},\"slots\":[],\"texts\":["
                + "{\"text\":\"Hello\",\"x\":2,\"y\":2,\"width\":25,\"color\":\"000000\"}],\"check\":{\"mode\":\"structural\",\"textBoxes\":[[0,0,30,20]]}}";
        assertEquals("ok", GuiCompare.structural(cap("b", gui(0, 0), s), NO_LANG).verdict());
        String overflow = s.replace("\"x\":2,\"y\":2,\"width\":25", "\"x\":20,\"y\":2,\"width\":25");
        assertEquals("broken", GuiCompare.structural(cap("b", gui(0, 0), overflow), NO_LANG).verdict());
        String rawKey = s.replace("Hello", "guide.examplemod.press.title");
        assertTrue(GuiCompare.structural(cap("b", gui(0, 0), rawKey), NO_LANG).findings().toString().contains("untranslated"));
        BufferedImage magenta = gui(0, 0);
        for (int y = 2; y < 10; y++) {
            for (int x = 30; x < 38; x++) {
                magenta.setRGB(x, y, (x + y) % 2 == 0 ? 0xfff800f8 : 0xff000000);
            }
        }
        assertTrue(GuiCompare.structural(cap("b", magenta, s), NO_LANG).findings().toString().contains("missing-texture"));
        BufferedImage blank = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);
        assertEquals("broken", GuiCompare.structural(cap("b", blank, s), NO_LANG).verdict());
        String absent = "{\"screenClass\":null,\"check\":{\"mode\":\"absent\"}}";
        assertEquals("ok", GuiCompare.structural(cap("n", null, absent), NO_LANG).verdict());
    }

    @Test
    void langPatternsMatchFormattedTexts() {
        assertTrue(Lang.toPattern("Heat: %s / %d HU").matcher("Heat: 50 / 1000 HU").matches());
        assertTrue(Lang.toPattern("Steam output %1$s mB/t (%%)").matcher("Steam output 12.5 mB/t (%)").matches());
        assertTrue(Lang.looksLikeKey("gui.examplemod.boiler.heat"));
        assertTrue(!Lang.looksLikeKey("Fuel Boiler"));
    }

    @Test
    void compilesGuiSteps(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("g.scenario.yaml");
        Files.writeString(f, String.join("\n", "schemaVersion: 1", "id: g", "world: flat-void", "side: client", "steps:",
                "  - open_gui: { item: \"examplemod:manual\", capture: home, keepOpen: true, check: { mode: structural, size: [256, 192] } }",
                "  - gui_input: { key: RIGHT, capture: next }",
                "  - gui_input: { call: { method: openEntry, args: [\"press\", 2] } }",
                "  - close_gui: {}", ""), StandardCharsets.UTF_8);
        Scenario sc = Scenario.read(f).get(0);
        IdMap ids = IdMap.read(PipelineTest.res("ids-1.7.10.yaml"));
        ObjectNode plan = new PlanCompiler(sc, "forge-1.7.10", ids, IrIndex.read(List.of())).compile();
        JsonNode ops = plan.path("ops");
        assertEquals("open_gui", ops.get(0).path("op").asString());
        assertEquals("examplemod:manual", ops.get(0).path("item").path("id").asString());
        assertEquals("home", ops.get(0).path("capture").asString());
        assertTrue(ops.get(0).path("keepOpen").asBoolean());
        assertEquals("RIGHT", ops.get(1).path("key").asString());
        assertEquals(2, ops.get(2).path("call").path("args").get(1).asInt());
        assertEquals("close_gui", ops.get(3).path("op").asString());
    }

    @Test
    void exportsLoadsAndReports(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("run");
        Files.createDirectories(run.resolve("screens"));
        BufferedImage full = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        BufferedImage g = gui(5, 0);
        full.getGraphics().drawImage(g, 70, 30, null);
        ImageIO.write(full, "png", run.resolve("screens/press.png").toFile());
        Files.writeString(run.resolve("console.log"), "[porter-testkit] gui op open_gui press start\n[Client/ERROR]: boom\n"
                + "[porter-testkit] capture press end\n", StandardCharsets.UTF_8);
        String raw = "{\"scenario\":\"s\",\"version\":\"forge-1.7.10\",\"gui\":[" + SLOTS.replace("\"check\":{}", "\"check\":{}")
                + ",\"step\":1,\"capture\":\"press\",\"guiLeft\":70,\"guiTop\":30,\"scale\":1,\"screenshotFile\":\""
                + run.resolve("screens/press.png").toString().replace("\\", "\\\\") + "\"}]}";
        JsonNode rawNode = Json.json().readTree(raw);
        Path dir = tmp.resolve("s-forge-1.7.10.gui");
        GuiCaptures.export(rawNode, run, dir, List.of());
        GuiCaptures.Set set = GuiCaptures.load(dir);
        Capture c = set.captures().get("press");
        assertEquals(60, c.image().getWidth());
        assertEquals(g.getRGB(41, 33), c.image().getRGB(41, 33));
        assertEquals(1, c.entry().path("renderErrors").size());
        GuiReport rep = new GuiReport(tmp.resolve("report"));
        rep.compare(set, set);
        Path html = rep.write("t", "a", "b");
        assertTrue(Files.readString(html).contains("press"));
        // the original's render error is a note, the port's the same error is broken
        assertEquals("broken", rep.results().get(0).verdict());
    }
}
