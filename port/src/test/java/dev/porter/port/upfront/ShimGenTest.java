package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShimGenTest {
    private static final String CORE = "net.minecraft.core.";

    private static MatchRow row(String key, Plan plan, Target t, String shim, List<Alt> alts) {
        return new MatchRow(SymbolKey.parse(key), 2, "world", "none", plan, "none:no-candidates", 0.0, t, shim, null, null, alts, null);
    }

    private static void put(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private ShimGen.Output generate(Path shims, Path bodies) throws IOException {
        String lvl = "net.minecraft.world.level.Level";
        String gad = "net.minecraft.world.Gadget";
        String hol = "net.minecraft.world.Holder";
        List<MatchRow> rows = List.of(
                row("type:" + gad, Plan.MIRROR, null, null, null),
                row("method:" + gad + "#size()I", Plan.MIRROR, null, null, null),
                row("method:" + gad + "#make()L" + gad.replace('.', '/') + ";", Plan.MIRROR, new Target("", "", "", true), null, null),
                row("method:" + gad + "#put(L" + CORE.replace('.', '/') + "Direction;)V", Plan.TODO, null, null, null),
                row("method:" + gad + "#put(Lnet/minecraft/Unknown;)V", Plan.TODO, null, null, null),
                row("field:" + gad + "#level:I", Plan.MIRROR, null, null, null),
                row("method:" + hol + "#count(I)I", Plan.MIRROR, null, null, null),
                row("method:" + hol + "#reset()V", Plan.TODO, null, null, null),
                row("type:" + lvl, Plan.MAP, new Target("old.World", "", "", false), null, null),
                row("method:" + lvl + "#isLoaded(L" + CORE.replace('.', '/') + "BlockPos;)Z", Plan.TODO, null, null,
                        List.of(new Alt("old.World", "blockExists", "(III)Z", 0.65), new Alt("old.World", "chunkExists", "(II)Z", 0.58))),
                row("method:" + lvl + "#getBrightness(L" + CORE.replace('.', '/') + "BlockPos;)I", Plan.TODO, null, null, null),
                row("method:" + lvl + "#tickRate()I", Plan.HINT, new Target("old.World", "tickRate", "()I", false), null, null),
                row("method:" + lvl + "#getDay()J", Plan.MAP, new Target("old.World", "getDay", "()J", false), "dev.porter.shim.mc.Holder", null),
                row("field:" + lvl + "#tickCount:I", Plan.TODO, null, null, null),
                row("method:" + lvl + "#gone()V", Plan.REGISTRY, null, null, null));
        ShimGen.Input in = new ShimGen.Input(new MatchTable(1, rows), Map.of(CORE + "BlockPos", "dev.porter.shim.BlockPos"),
                ShimIndex.scan(List.of(shims)), BodiesDb.load(bodies), Map.of(), Map.of());
        return new ShimGen(in).run();
    }

    private Path fixtureShims(Path tmp) throws IOException {
        Path s = tmp.resolve("shims");
        put(s, "dev/porter/shim/BlockPos.java", "package dev.porter.shim;\npublic final class BlockPos {\n"
                + "    public int getX() { return 0; }\n    public int getY() { return 0; }\n    public int getZ() { return 0; }\n}\n");
        put(s, "dev/porter/shim/mc/Holder.java", "package dev.porter.shim.mc;\npublic class Holder {\n"
                + "    public int count(int i) { return i; }\n}\n");
        return s;
    }

    private Path fixtureBodies(Path tmp) throws IOException {
        Path b = tmp.resolve("bodies");
        put(b, "world-1.yaml", "# header\n---\nkey: \"method:net.minecraft.world.level.Level#getBrightness(Lnet/minecraft/core/BlockPos;)I\"\n"
                + "body: \"return self.light(blockPos.getX(), blockPos.getY(), blockPos.getZ());\"\nimports: []\nverified: compile\n");
        return b;
    }

    @Test
    void generatesGoldenShapesAndCompilesOnJava8(@TempDir Path tmp) throws Exception {
        ShimGen.Output o = generate(fixtureShims(tmp), fixtureBodies(tmp));
        Map<String, String> f = o.files();

        String gadget = f.get("src/dev/porter/shim/gen/world/Gadget.java");
        assertTrue(gadget.contains("public class Gadget {"), gadget);
        assertTrue(gadget.contains("public int size() {"), gadget);
        assertTrue(gadget.contains("public static dev.porter.shim.gen.world.Gadget make() {"), gadget);
        assertTrue(gadget.contains("public int level;"), gadget);
        assertTrue(gadget.contains("// porter:body method:net.minecraft.world.Gadget#size()I"), gadget);
        assertTrue(gadget.contains("throw new UnsupportedOperationException(\"porter:todo size\");"), gadget);
        // overload collision: one member, both keys named
        assertEquals(1, gadget.split("public void put\\(", -1).length - 1, gadget);
        assertTrue(gadget.contains("also: method:net.minecraft.world.Gadget#put("), gadget);

        // existing shim member is not generated, the missing one goes to a fragment
        String frag = f.get("fragments/Holder.members.java");
        assertTrue(frag.contains("public void reset()"), frag);
        assertFalse(frag.contains("count("), frag);
        assertFalse(f.containsKey("src/dev/porter/shim/gen/world/Holder.java"));

        // MAP owner: Ext helpers, self first, concept shim for BlockPos, DB body spliced, delegate, hint, field pair
        String ext = f.get("src/dev/porter/shim/gen/world/level/Level_Ext.java");
        if (ext == null) {
            ext = f.get("src/dev/porter/shim/gen/world/level/LevelExt.java");
        }
        assertTrue(ext.contains("public static boolean isLoaded(old.World self, dev.porter.shim.BlockPos blockPos)"), ext);
        assertTrue(ext.contains("plan TODO; candidates: blockExists 0.65, chunkExists 0.58"), ext);
        assertTrue(ext.contains("return self.light(blockPos.getX(), blockPos.getY(), blockPos.getZ());"), ext);
        assertTrue(ext.contains("hint: lineage target old.World#tickRate()I"), ext);
        assertTrue(ext.contains("return self.getDay();"), ext);
        assertTrue(ext.contains("public static int getTickCount(old.World self)"), ext);
        assertTrue(ext.contains("public static void setTickCount(old.World self, int value)"), ext);
        assertFalse(ext.contains("gone"), ext);

        // todo list: DB body and delegate are not todos; collision key listed
        List<String> todoKeys = o.todos().stream().map(t -> t.key().toString()).collect(Collectors.toList());
        assertFalse(todoKeys.contains("method:net.minecraft.world.level.Level#getBrightness(Lnet/minecraft/core/BlockPos;)I"));
        assertFalse(todoKeys.contains("method:net.minecraft.world.level.Level#getDay()J"));
        assertTrue(todoKeys.contains("method:net.minecraft.world.level.Level#isLoaded(Lnet/minecraft/core/BlockPos;)Z"));
        assertTrue(todoKeys.contains("method:net.minecraft.world.level.Level#tickRate()I"));
        assertEquals(1, o.withBody());
        assertEquals(1, o.skippedExisting());
        Path tsv = tmp.resolve("todo.tsv");
        TodoEntry.write(tsv, o.todos());
        assertEquals(o.todos().size(), TodoEntry.read(tsv).size());

        // deterministic
        assertEquals(o.files(), generate(fixtureShims(tmp), fixtureBodies(tmp)).files());

        // compiles with --release 8 against stubs
        Path stubs = tmp.resolve("stub");
        put(stubs, "old/World.java", "package old;\npublic class World {\n public boolean blockExists(int x,int y,int z){return true;}\n"
                + " public int light(int x,int y,int z){return 0;}\n public int tickRate(){return 1;}\n public long getDay(){return 1L;}\n}\n");
        List<String> sources = new ArrayList<>();
        try (var s = Files.walk(stubs)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
        }
        try (var s = Files.walk(tmp.resolve("shims"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
        }
        ShimGen.write(o, tmp.resolve("out"), tmp.resolve("out/todo.tsv"));
        try (var s = Files.walk(tmp.resolve("out/src"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
        }
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-d", classes.toString()));
        args.addAll(sources);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int rc = jc.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, rc, err.toString());
    }

    @Test
    void duplicateBodyKeysAreAnError(@TempDir Path tmp) throws IOException {
        Path b = tmp.resolve("bodies");
        String doc = "key: \"field:a.B#c:I\"\nbody: \"return 1;\"\n";
        put(b, "a-1.yaml", doc + "---\n" + doc);
        assertThrows(IllegalArgumentException.class, () -> BodiesDb.load(b));
    }

    @Test
    void bodiesRenderRoundTrip() {
        BodyEntry e = new BodyEntry(SymbolKey.parse("method:a.B#c()V"), "int x = 1;\nreturn;", List.of("java.util.List"), "compile");
        List<BodyEntry> back = BodiesDb.parse(BodiesDb.render(e), "t");
        assertEquals(List.of(e), back);
    }

    @Test
    void shimIndexKeysByNameArityAndSimpleTypes() {
        ShimIndex idx = new ShimIndex();
        idx.add("package p;\npublic class A {\n public void f(java.util.List<String> a, int... b) {}\n public A(int x) {}\n"
                + " public static final int K = 1;\n}\n", "A.java");
        ShimIndex.Info i = idx.bySimple("A");
        assertTrue(i.methods.contains("f(List,int[])"), i.methods.toString());
        assertTrue(i.methods.contains("<init>(int)"), i.methods.toString());
        assertTrue(i.fields.contains("K"));
    }
}
