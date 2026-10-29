package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Setter-marker splicing, covariant overrides in shimgen, and the default 1.7.10 class path. */
class UpfixTest {
    private static void put(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static MatchRow row(String key, Plan plan) {
        return new MatchRow(SymbolKey.parse(key), 2, "world", "none", plan, "none:no-candidates", 0.0, null, null, null, null, List.of(), null);
    }

    @Test
    void setterMarkerIsSplicedFromSetBody(@TempDir Path tmp) throws Exception {
        String key = "field:net.minecraft.world.Gadget#level:I";
        put(tmp, "gen/src/g/GadgetExt.java", "package g;\npublic final class GadgetExt {\n"
                + "    public static int getLevel(Object self) {\n        // porter:body " + key + "\n"
                + "        throw new UnsupportedOperationException(\"x\");\n        // porter:end\n    }\n\n"
                + "    public static void setLevel(Object self, int value) {\n        // porter:body " + key + " set\n"
                + "        throw new UnsupportedOperationException(\"y\");\n        // porter:end\n    }\n}\n");
        put(tmp, "bodies/a.yaml", "key: \"" + key + "\"\nbody: |-\n  return 7;\nset-body: |-\n  Object o = value;\nimports: []\nverified: none\n");
        Map<SymbolKey, Path> fileOf = new java.util.HashMap<>();
        Map<SymbolKey, BodyEntry> db = BodiesCheck.load(List.of(tmp.resolve("bodies")), fileOf);
        assertEquals("Object o = value;", db.get(SymbolKey.parse(key)).setBody());
        BodiesCheck.Result r = BodiesCheck.check(db, tmp.resolve("gen"), List.of(), null, null, tmp.resolve("work"));
        assertTrue(r.success(), r.toString());
        String spliced = Files.readString(tmp.resolve("work/spliced/src/g/GadgetExt.java"));
        assertTrue(spliced.contains("Object o = value;"), spliced);
        assertTrue(spliced.contains("return 7;"), spliced);
        assertTrue(!spliced.contains("\"y\""), spliced);

        // an error in the set-body is reported against the key
        put(tmp, "bodies/a.yaml", "key: \"" + key + "\"\nbody: |-\n  return 7;\nset-body: |-\n  undefinedCall(value);\nimports: []\nverified: none\n");
        db = BodiesCheck.load(List.of(tmp.resolve("bodies")), new java.util.HashMap<>());
        r = BodiesCheck.check(db, tmp.resolve("gen"), List.of(), null, null, tmp.resolve("work"));
        assertEquals(1, r.failed().size(), r.toString());
        // without a set-body the generated setter stays and compiles
        put(tmp, "bodies/a.yaml", "key: \"" + key + "\"\nbody: |-\n  return 7;\nimports: []\nverified: none\n");
        db = BodiesCheck.load(List.of(tmp.resolve("bodies")), new java.util.HashMap<>());
        r = BodiesCheck.check(db, tmp.resolve("gen"), List.of(), null, null, tmp.resolve("work"));
        assertTrue(r.success(), r.toString());
        assertTrue(Files.readString(tmp.resolve("work/spliced/src/g/GadgetExt.java")).contains("\"y\""));
    }

    @Test
    void covariantOverrideUsesTheAncestorReturnType(@TempDir Path tmp) throws Exception {
        String base = "net.minecraft.world.Base";
        String sub = "net.minecraft.world.Sub";
        String gad = "net.minecraft.world.Gadget";
        List<MatchRow> rows = List.of(
                row("type:" + base, Plan.MIRROR), row("type:" + sub, Plan.MIRROR), row("type:" + gad, Plan.MIRROR),
                row("method:" + base + "#focus()L" + gad.replace('.', '/') + ";", Plan.MIRROR),
                row("method:" + sub + "#focus()Lnet/minecraft/world/Unmapped;", Plan.MIRROR));
        ShimGen.Input in = new ShimGen.Input(new MatchTable(1, rows), Map.of(), ShimIndex.scan(List.of()), BodiesDb.empty(),
                Map.of(sub, new ShimGen.Hier(base, List.of(), false)), Map.of());
        Map<String, String> f = new ShimGen(in).run().files();
        String s = f.get("src/dev/porter/shim/gen/world/Sub.java");
        assertTrue(s.contains("public dev.porter.shim.gen.world.Gadget focus()"), s);
    }

    @Test
    void defaultClasspathComesFromTheForgeVersionJson(@TempDir Path tmp) throws Exception {
        put(tmp, "mc/versions/Forge 1.7.10/Forge 1.7.10.json", "{\"libraries\":[{\"name\":\"a:b:1\",\"artifact\":{\"path\":\"a/b/1/b-1.jar\"}},"
                + "{\"name\":\"c:d:1\",\"url\":\"x\"},{\"name\":\"e:f:1\",\"artifact\":{\"path\":\"e/f/1/missing.jar\"}}]}");
        put(tmp, "mc/libraries/a/b/1/b-1.jar", "jar");
        Files.createDirectories(tmp.resolve("repo/rewrite/shim/src/adapters/java"));
        Path mcp = tmp.resolve("mcp.jar");
        List<Path> cp = TargetClasspath.build(mcp, tmp.resolve("mc"), "Forge 1.7.10", TargetClasspath.adapterDirs(tmp.resolve("repo")));
        assertEquals(List.of(mcp, tmp.resolve("repo/rewrite/shim/src/adapters/java"), tmp.resolve("mc/libraries/a/b/1/b-1.jar")), cp);
    }
}
