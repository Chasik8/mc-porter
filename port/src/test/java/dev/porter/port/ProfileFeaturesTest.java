package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.json.Json;
import dev.porter.port.config.PortConfig;
import dev.porter.port.passes.LangKeys;
import dev.porter.port.passes.SourcePasses;
import dev.porter.port.patch.PatchSet;
import dev.porter.port.patch.UnifiedDiff;
import dev.porter.port.run.Download;
import dev.porter.port.select.IrSelect;
import dev.porter.port.select.Overlay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Port-profile features (RFC 007): patches, IR selection + overlay, asset namespaces, lang keys, steps, vars. Synthetic inputs only. */
class ProfileFeaturesTest {
    @TempDir
    Path tmp;

    private static void roundTrip(String a, String b) {
        String d = UnifiedDiff.diff("x", a, "y", b, 3);
        List<UnifiedDiff.FilePatch> ps = UnifiedDiff.parse(d);
        if (a != null && a.equals(b)) {
            assertTrue(ps.isEmpty());
            return;
        }
        assertEquals(1, ps.size(), d);
        assertEquals(b, UnifiedDiff.apply(ps.get(0), a), d);
    }

    @Test
    void unifiedDiffRoundTripsIncludingFinalNewlines() {
        roundTrip("a\nb\nc\n", "a\nB\nc\n");
        roundTrip("a\nb\nc", "a\nb\nc\n");
        roundTrip("a\nb\nc\n", "a\nb\nc");
        roundTrip("a\nb", "a\nc");
        roundTrip(null, "new\nfile\n");
        roundTrip("", "x\n");
        roundTrip("x\r\ny\r\n", "x\r\nz\r\n");
        StringBuilder big = new StringBuilder();
        StringBuilder big2 = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append("line ").append(i).append('\n');
            big2.append(i % 37 == 0 ? "changed " : "line ").append(i).append('\n');
            if (i % 91 == 0) {
                big2.append("inserted\n");
            }
        }
        roundTrip(big.toString(), big2.toString());
        roundTrip(big2.toString(), big.toString());
        // deletion
        List<UnifiedDiff.FilePatch> del = UnifiedDiff.parse(UnifiedDiff.diff("x", "gone\n", "x", null, 3));
        assertTrue(del.get(0).deleted());
        assertNull(UnifiedDiff.apply(del.get(0), "gone\n"));
    }

    @Test
    void blindPatchesCarryNoBaseTextAndPinTheBase() {
        String base = "secret one\nkeep\nsecret two\nsecret three\nend";
        String want = "new one\nkeep\nend\nadded\n";
        String d = UnifiedDiff.diffBlind("o/A.java", base, "n/A.java", want);
        assertFalse(d.contains("secret"), d);
        assertFalse(d.contains("keep"), d);
        assertTrue(d.startsWith(UnifiedDiff.BLIND), d);
        List<UnifiedDiff.FilePatch> ps = UnifiedDiff.parse(d);
        assertTrue(ps.get(0).blind());
        assertEquals(want, UnifiedDiff.apply(ps.get(0), base));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> UnifiedDiff.apply(ps.get(0), base + "\n"));
        assertTrue(e.getMessage().contains("sha256"), e.getMessage());
        String two = d + UnifiedDiff.diff("x", "a\n", "x", "b\n", 3);
        assertEquals(2, UnifiedDiff.parse(two).size());
        assertFalse(UnifiedDiff.parse(two).get(1).blind());
    }

    @Test
    void patchesAreStrictAndMayMoveFilesBetweenTrees() throws Exception {
        Path base = tmp.resolve("decompiled");
        Path target = tmp.resolve("project");
        Text.write(base.resolve("org/a/Foo.java"), "package org.a;\nclass Foo {\n    int x = 1;\n}\n");
        Text.write(tmp.resolve("want/com/b/Foo.java"), "package com.b;\nclass Foo {\n    int x = 2;\n}\n");
        String p = PatchSet.make(base, tmp.resolve("want"), List.of(new PatchSet.Pair("org/a/Foo.java", "com/b/Foo.java")), 3);
        assertTrue(p.startsWith("--- a/org/a/Foo.java\n+++ b/com/b/Foo.java\n"), p);
        Text.write(tmp.resolve("p/foo.patch"), p);
        PatchSet.Applied a = PatchSet.apply(tmp.resolve("p"), base, target);
        assertEquals(List.of("com/b/Foo.java"), a.written());
        assertEquals(Text.read(tmp.resolve("want/com/b/Foo.java")), Text.read(target.resolve("com/b/Foo.java")));
        // a base that changed: refused, nothing written
        Text.write(base.resolve("org/a/Foo.java"), "package org.a;\nclass Foo {\n    int x = 3;\n}\n");
        Files.delete(target.resolve("com/b/Foo.java"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PatchSet.apply(tmp.resolve("p"), base, target));
        assertTrue(e.getMessage().contains("does not apply at line 3"), e.getMessage());
        assertFalse(Files.exists(target.resolve("com/b/Foo.java")));
    }

    @Test
    void assetNamespacePassMovesOnlyListedPathPrefixes() {
        Text.write(tmp.resolve("s/A.java"), "class A { Object a = new ResourceLocation(\"orig\", \"textures/gui/a.png\"); "
                + "Object b = new ResourceLocation( \"orig\" ,\"misc/gui/x\"); Object c = new ResourceLocation(\"orig\", \"recipe_id\"); }\n");
        int n = SourcePasses.assetNamespaces(tmp.resolve("s"), List.of(new SourcePasses.AssetNamespace("orig", "mine", List.of("textures/", "misc/gui/"))));
        assertEquals(2, n);
        String s = Text.read(tmp.resolve("s/A.java"));
        assertTrue(s.contains("new ResourceLocation(\"mine\", \"textures/gui/a.png\")"));
        assertTrue(s.contains("new ResourceLocation(\"mine\", \"misc/gui/x\")"));
        assertTrue(s.contains("new ResourceLocation(\"orig\", \"recipe_id\")"));
    }

    @Test
    void langKeysCopiesLiteralKeysIntoAReplaceableSection() {
        Text.write(tmp.resolve("src/G.java"), "class G { String a = \"gui.mod.title\"; String b = \"gui.mod.missing\"; String c = \"gui.mod.have\"; }\n");
        Text.write(tmp.resolve("lang/en_us.json"), "{\"gui.mod.title\": \"Title\\nTwo\", \"gui.mod.have\": \"x\", \"other\": \"y\"}");
        Text.write(tmp.resolve("lang/ru_ru.json"), "{\"gui.mod.title\": \"Заголовок\"}");
        Text.write(tmp.resolve("out/en_US.lang"), "gui.mod.have=Have\n");
        Text.write(tmp.resolve("out/ru_RU.lang"), "a=b");
        Map<String, String> loc = new LinkedHashMap<>();
        loc.put("en_us", "en_US");
        loc.put("ru_ru", "ru_RU");
        loc.put("de_de", "de_DE");
        Pattern key = Pattern.compile("\"(gui\\.mod\\.[a-z.]+)\"");
        Map<String, Integer> r = LangKeys.merge(tmp.resolve("src"), key, tmp.resolve("lang"), tmp.resolve("out"), loc, "en_us", "m");
        assertEquals(Map.of("en_US", 1, "ru_RU", 2), r);
        assertEquals("gui.mod.have=Have\n# --- m ---\ngui.mod.title=Title\\nTwo\n# --- end m ---\n", Text.read(tmp.resolve("out/en_US.lang")));
        assertEquals("a=b\n# --- m ---\ngui.mod.have=x\ngui.mod.title=Заголовок\n# --- end m ---\n", Text.read(tmp.resolve("out/ru_RU.lang")));
        LangKeys.merge(tmp.resolve("src"), key, tmp.resolve("lang"), tmp.resolve("out"), loc, "en_us", "m"); // idempotent
        assertEquals("a=b\n# --- m ---\ngui.mod.have=x\ngui.mod.title=Заголовок\n# --- end m ---\n", Text.read(tmp.resolve("out/ru_RU.lang")));
    }

    private void ir(Path dir, String kind, String nodes) {
        Text.write(dir.resolve("m." + kind + ".ir.json"), "{\"schemaVersion\": 1, \"source\": {\"version\": \"forge-1.19.2\"}, \"nodes\": [" + nodes + "]}");
    }

    @Test
    void selectFollowsReferencesSkipsHostIdsAndOverlayReproducesHandEdits() {
        Path in = tmp.resolve("ir");
        ir(in, "block", "{\"kind\":\"block\",\"id\":\"m:barrel\",\"blockEntity\":\"m:barrel\",\"item\":\"m:barrel\",\"extensions\":{\"porter:legacyName\":\"m.barrel\"}},"
                + "{\"kind\":\"block\",\"id\":\"m:other\"}");
        ir(in, "blockEntity", "{\"kind\":\"blockEntity\",\"id\":\"m:barrel\",\"logicClass\":\"org.m.BarrelTile\",\"menu\":\"m:barrel\"}");
        ir(in, "menu", "{\"kind\":\"menu\",\"id\":\"m:barrel\",\"logicClass\":\"org.m.containers.BarrelContainer\"}");
        ir(in, "item", "{\"kind\":\"item\",\"id\":\"m:barrel\",\"placesBlock\":\"m:barrel\"},{\"kind\":\"item\",\"id\":\"m:mug\"},{\"kind\":\"item\",\"id\":\"m:hops\"}");
        ir(in, "recipe", "{\"kind\":\"recipe\",\"id\":\"m:r1\",\"result\":{\"item\":\"m:mug\"}},{\"kind\":\"recipe\",\"id\":\"m:r2\",\"result\":{\"item\":\"m:other\"}},"
                + "{\"kind\":\"recipe\",\"id\":\"m:brew\",\"customType\":\"m:brewing\"}");
        ir(in, "unknown", "{\"kind\":\"unknown\",\"id\":\"m:u1\",\"item\":\"m:mug\",\"provenance\":{\"file\":\"x/Y.java\"}},"
                + "{\"kind\":\"unknown\",\"id\":\"m:u2\",\"provenance\":{\"file\":\"x/Barrel.java\"}},{\"kind\":\"unknown\",\"id\":\"m:u3\",\"provenance\":{\"file\":\"z.java\"}}");
        ir(in, "mod", "{\"kind\":\"mod\",\"id\":\"m\"}");
        Text.write(tmp.resolve("host.yaml"), "items: {\"m:hops\": \"Host.hops\"}\n");
        Text.write(tmp.resolve("src/org/m/components/BarrelComponent.java"), "class X {}\n");
        Text.write(tmp.resolve("spec.yaml"), "unit: u\nmodId: m\nseed: '^m:(barrel|mug|hops)$'\ncustomTypes: ['m:brewing']\nunknownFiles: [Barrel.java]\n"
                + "hostIds: [host.yaml]\nrelocate: ['org.m.=port.m.']\nlegacyNames: {from: 'm.', to: 'mine.'}\n"
                + "screenConvention: {replace: [['.containers.', '.components.']], suffix: [Container, Component], sources: src}\n");
        IrSelect.Spec spec = IrSelect.readSpec(tmp.resolve("spec.yaml"), p -> tmp.resolve(p));
        IrSelect.Result r = IrSelect.select(in, spec);
        Map<String, ObjectNode> d = r.docs();
        // every kind is in the result, also the empty ones (a profile can name <unit>.multiblock.ir.json for any seed)
        assertEquals(List.of("block", "blockEntity", "item", "fluid", "effect", "menu", "packet", "sound", "multiblock", "recipe",
                "unknown", "mod"), List.copyOf(d.keySet()));
        assertEquals(0, d.get("multiblock").get("nodes").size());
        assertEquals(1, d.get("block").get("nodes").size());
        assertEquals("mine.barrel", d.get("block").get("nodes").get(0).path("extensions").path("porter:legacyName").asString());
        assertEquals(2, d.get("item").get("nodes").size()); // barrel (referenced) + mug; hops is the host's
        assertEquals("port.m.BarrelTile", d.get("blockEntity").get("nodes").get(0).path("logicClass").asString());
        assertEquals("port.m.components.BarrelComponent", d.get("menu").get("nodes").get(0).path("screenClass").asString());
        assertEquals(List.of("m:r1", "m:brew"), ids(d.get("recipe")));
        assertEquals(List.of("m:u1", "m:u2"), ids(d.get("unknown")));
        // a hand-edited target: overlay made from the difference reproduces it
        Map<String, ObjectNode> want = new LinkedHashMap<>();
        d.forEach((k, v) -> want.put(k, v.deepCopy()));
        ((ObjectNode) want.get("recipe").get("nodes").get(1)).put("duration", 250);
        ((ObjectNode) want.get("recipe").get("nodes").get(1)).remove("customType");
        want.get("recipe").put("schemaVersion", 2);
        ((tools.jackson.databind.node.ArrayNode) want.get("recipe").get("nodes")).add(Json.json().readTree("{\"kind\":\"recipe\",\"id\":\"m:manual\"}"));
        ((tools.jackson.databind.node.ArrayNode) want.get("unknown").get("nodes")).remove(0);
        ObjectNode ov = Overlay.make(d, want);
        Map<String, ObjectNode> got = new LinkedHashMap<>();
        d.forEach((k, v) -> got.put(k, v.deepCopy()));
        Map<String, Object> counts = Overlay.apply(got, Json.yaml().readTree(Json.yaml().writeValueAsString(ov)), null);
        assertEquals(Map.of("merged", 1, "added", 1, "removed", 1), counts);
        assertTrue(Overlay.same(got, want));
    }

    private static List<String> ids(JsonNode doc) {
        List<String> out = new java.util.ArrayList<>();
        doc.get("nodes").forEach(n -> out.add(n.path("id").asString()));
        return out;
    }

    @Test
    void varsHaveDefaultsAndTheConfigDirectory() {
        Text.write(tmp.resolve("p/a.port.yaml"), "schemaVersion: 1\nname: a\nmode: full\nfrom: forge-1.19.2\nto: forge-1.7.10\n"
                + "project: \"${PORTER_SURELY_UNDEFINED_VAR:-${CONFIG_DIR}/proj}\"\n");
        assertEquals(tmp.resolve("p/proj").toAbsolutePath().normalize(), PortConfig.load(tmp.resolve("p/a.port.yaml")).project());
        Text.write(tmp.resolve("p/b.port.yaml"), "schemaVersion: 1\nname: b\nmode: full\nfrom: forge-1.19.2\nto: forge-1.7.10\n"
                + "vars: {PORTER_SURELY_UNDEFINED_VAR: \"${PORTER_SURELY_UNDEFINED_VAR:-${CONFIG_DIR}/d}\"}\nproject: \"${PORTER_SURELY_UNDEFINED_VAR}/x\"\n");
        assertEquals(tmp.resolve("p/d/x").toAbsolutePath().normalize(), PortConfig.load(tmp.resolve("p/b.port.yaml")).project());
    }

    @Test
    void generatedLangIsMergedIntoItsSectionInPlace() {
        Path p = tmp.resolve("en_US.lang");
        Text.write(p, "hand.key=Hand\n# --- porter:a ---\nold=1\n# --- end porter:a ---\nbook=Book\n");
        Text.write(tmp.resolve("gen.lang"), "# generated\nhand.key=Generated\nnew.key=New\n");
        PortRunner.mergeLangSection(tmp.resolve("gen.lang"), p, "a");
        assertEquals("hand.key=Hand\n# --- porter:a ---\nnew.key=New\n# --- end porter:a ---\nbook=Book\n", Text.read(p));
        PortRunner.mergeLangSection(tmp.resolve("gen.lang"), p, "b");
        assertTrue(Text.read(p).endsWith("book=Book\n# --- porter:b ---\n\n# --- end porter:b ---\n"), Text.read(p));
        PortRunner.mergeLangSection(tmp.resolve("gen.lang"), tmp.resolve("fresh.lang"), "a");
        assertEquals("# --- porter:a ---\nhand.key=Generated\nnew.key=New\n# --- end porter:a ---\n", Text.read(tmp.resolve("fresh.lang")));
    }

    @Test
    void compositeReplacesOnlyTheOverlaysOpaquePixels() throws Exception {
        java.awt.image.BufferedImage base = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        base.setRGB(0, 0, 0xFF112233);
        base.setRGB(1, 0, 0xFF445566);
        java.awt.image.BufferedImage over = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        over.setRGB(1, 0, 0xFFABCDEF);
        javax.imageio.ImageIO.write(base, "png", tmp.resolve("b.png").toFile());
        javax.imageio.ImageIO.write(over, "png", tmp.resolve("o.png").toFile());
        assertEquals(1, Steps.composite(tmp.resolve("b.png"), tmp.resolve("o.png"), tmp.resolve("out/r.png")));
        java.awt.image.BufferedImage r = javax.imageio.ImageIO.read(tmp.resolve("out/r.png").toFile());
        assertEquals(0xFF112233, r.getRGB(0, 0));
        assertEquals(0xFFABCDEF, r.getRGB(1, 0));
    }

    @Test
    void unzipStripsAPrefixAndRefusesEscapes() throws Exception {
        Path zip = tmp.resolve("a.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (String n : List.of("assets/m/textures/a.png", "assets/m/lang/en.json", "org/m/A.class")) {
                z.putNextEntry(new java.util.zip.ZipEntry(n));
                z.write(n.getBytes());
                z.closeEntry();
            }
        }
        assertEquals(2, Download.unzip(zip, "assets/m/", tmp.resolve("o"), true, false));
        assertTrue(Files.exists(tmp.resolve("o/textures/a.png")));
        assertEquals(1, Download.unzip(zip, "assets/m/textures/a.png", tmp.resolve("one"), true, false));
        assertTrue(Files.exists(tmp.resolve("one/a.png")));
        assertEquals(3, Download.unzip(zip, "", tmp.resolve("all"), false, false));
        Path bad = tmp.resolve("bad.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(bad))) {
            z.putNextEntry(new java.util.zip.ZipEntry("../evil.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertThrows(IllegalStateException.class, () -> Download.unzip(bad, "", tmp.resolve("x"), false, false));
        Download.verify(zip, Download.sha256(zip), "a");
        assertThrows(IllegalStateException.class, () -> Download.verify(zip, "0".repeat(64), "a"));
    }
}
