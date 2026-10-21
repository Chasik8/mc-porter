package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.closure.ApiCompare;
import dev.porter.port.closure.ClassIndex;
import dev.porter.port.closure.Closure;
import dev.porter.port.javac.CastFix;
import dev.porter.port.javac.JavacLog;
import dev.porter.port.javac.Symbols;
import dev.porter.port.passes.Adapters;
import dev.porter.port.passes.HubStubs;
import dev.porter.port.passes.SourcePasses;
import dev.porter.port.passes.SrgMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic inputs only (a made-up mod "com.example.mod"). */
class PortToolsTest {
    @TempDir
    Path tmp;

    private void src(Path root, String fqn, String body) {
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        Text.write(root.resolve(fqn.replace('.', '/') + ".java"), "package " + pkg + ";\n" + body);
    }

    @Test
    void closureCopiesRecursivelyStopsAtHubsAndLeavesIdenticalKnownClasses() {
        Path s = tmp.resolve("src");
        src(s, "com.example.mod.machine.Grinder", "import com.example.mod.util.Helper;\nimport com.example.mod.registry.Registry;\n"
                + "public class Grinder { Helper h; Registry r; Shared x; Api a; }\n");
        src(s, "com.example.mod.machine.Shared", "public class Shared { }\n");
        src(s, "com.example.mod.machine.Api", "public interface Api { int power(); }\n");
        src(s, "com.example.mod.util.Helper", "import com.example.mod.util.Deep;\npublic class Helper { Deep d; }\n");
        src(s, "com.example.mod.util.Deep", "public class Deep { }\n");
        src(s, "com.example.mod.registry.Registry", "import com.example.mod.util.Unused;\npublic class Registry { }\n");
        src(s, "com.example.mod.util.Unused", "public class Unused { }\n");
        Path known = tmp.resolve("base");
        src(known, "com.example.mod.machine.Api", "public interface Api {\n    int power();\n}\n");      // identical API
        src(known, "com.example.mod.machine.Shared", "public class Shared { void other() {} }\n");    // different
        Closure.Result r = Closure.compute(new ClassIndex(s), new Closure.Spec(List.of("com.example.mod."),
                List.of("com.example.mod.machine.Grinder"), List.of(), List.of("com.example.mod.registry."), List.of(), List.of(),
                List.of(known)));
        assertEquals(List.of("com.example.mod.machine.Grinder", "com.example.mod.machine.Shared", "com.example.mod.util.Deep",
                "com.example.mod.util.Helper"), r.port());
        assertEquals(List.of("com.example.mod.registry.Registry"), r.frontier());
        assertEquals("identical", r.known().get("com.example.mod.machine.Api"));
        assertEquals("different", r.known().get("com.example.mod.machine.Shared"));
    }

    @Test
    void apiCompareIgnoresBodiesCommentsAndParameterNames() {
        assertTrue(ApiCompare.identical("public class A { /** x */ public int f(int a) { return 1; } }",
                "class A {\n  int f(final int b) { return 2; }\n}"));
        assertFalse(ApiCompare.identical("class A { int f(int a); }", "class A { long f(int a); }"));
    }

    @Test
    void hubStubInertBodiesReturnEmptyContainersNotNull() {
        assertEquals("return java.util.Collections.emptyMap(); // porter:hub-stub inert",
                HubStubs.inertBody("public static Map<String, TextureAtlasSprite> ", false));
        assertEquals("return java.util.Collections.emptyList(); // porter:hub-stub inert",
                HubStubs.inertBody("default public java.util.List<BlockState> ", false));
        assertEquals("return java.util.Optional.empty(); // porter:hub-stub inert",
                HubStubs.inertBody("public <T> Optional<T> ", false));
        assertEquals("return null; // porter:hub-stub inert", HubStubs.inertBody("public ObjectArrayList<ItemStack> ", false));
        assertEquals("return null; // porter:hub-stub inert", HubStubs.inertBody("public com.example.Map ", false));
        assertEquals("return 0; // porter:hub-stub inert", HubStubs.inertBody("public static int ", false));
    }

    @Test
    void hubStubKeepsOnlyReferencedMembersWithInertBodies() {
        Path rw = tmp.resolve("rw");
        src(rw, "my.ported.machine.Grinder", "import my.ported.registry.Registry;\npublic class Grinder { int x = Registry.count(); Registry r = new Registry(); }\n");
        src(rw, "my.ported.registry.Registry", "import java.util.List;\nimport net.minecraft.world.level.Level;\n"
                + "public class Registry {\n    public Registry() { setup(); }\n    public static int count() { return compute(); }\n"
                + "    public static void unused() { }\n    public Level level() { return null; }\n    private static int compute() { return 3; }\n}\n");
        List<Relocation> rel = List.of(Relocation.parse("com.example.mod.=my.ported."));
        List<String> port = List.of("com.example.mod.machine.Grinder");
        List<String> fr = List.of("com.example.mod.registry.Registry");
        HubStubs hs = new HubStubs(rw, port, fr, Set.of(), new HubStubs.Options(rel, "my.", List.of("my.ported.registry."), "// stub\n"));
        hs.write(rw, fr, tmp.resolve("out"), null);
        String stub = Text.read(tmp.resolve("out/my/ported/registry/Registry.java"));
        assertTrue(stub.startsWith("// stub\npackage my.ported.registry;"), stub);
        assertTrue(stub.contains("public static int count() {\n        return 0; // porter:hub-stub inert"), stub);
        assertTrue(stub.contains("public Registry() {\n        // porter:hub-stub inert"), stub);
        assertFalse(stub.contains("unused"), stub);
        assertFalse(stub.contains("Level"), "invalid target type dropped: " + stub);
    }

    @Test
    void java8PassRewritesRecordsAndPrivateInterfaceMethods() {
        Path d = tmp.resolve("j8");
        src(d, "a.P", "public record P(int x, String name) { }\n");
        src(d, "a.I", "public interface I {\n    private int helper() { return 1; }\n    default int v() { return helper(); }\n}\n");
        src(d, "a.L", "class L { String[] f(java.util.List<String> l) { return l.toArray(String[]::new); } }\n");
        SourcePasses.Java8Counts c = SourcePasses.java8(d);
        assertEquals(new SourcePasses.Java8Counts(1, 1, 1), c);
        String p = Text.read(d.resolve("a/P.java"));
        assertTrue(p.contains("public final class P {") && p.contains("private final int x;") && p.contains("public String name() {"), p);
        assertTrue(Text.read(d.resolve("a/I.java")).contains("default int helper()"));
        assertTrue(Text.read(d.resolve("a/L.java")).contains("l.toArray(new String[0])"));
        // Stream.toArray(IntFunction) is Java 8: a stream pipeline keeps X[]::new (before: rewritten to an invalid call)
        Path d2 = tmp.resolve("j8s");
        src(d2, "a.S", "class S { String[] f(java.util.List<String> l) { return l.stream().map(x -> x).toArray(String[]::new); } }\n");
        assertEquals(new SourcePasses.Java8Counts(0, 0, 0), SourcePasses.java8(d2));
        assertTrue(Text.read(d2.resolve("a/S.java")).contains(".toArray(String[]::new)"));
    }

    @Test
    void tileConstructorsImportsAndEdits() {
        Path d = tmp.resolve("t");
        src(d, "a.MyTile", "import a.Unused;\nimport a.BlockPos;\n\npublic class MyTile extends Base {\n    public MyTile(BlockPos pos, BlockState state) {\n        super(pos, state);\n    }\n}\n");
        assertEquals(1, SourcePasses.tileConstructors(d));
        assertTrue(Text.read(d.resolve("a/MyTile.java")).contains("this(new BlockPos(0, 0, 0), null);"));
        assertEquals(1, SourcePasses.unusedImports(d));
        assertFalse(Text.read(d.resolve("a/MyTile.java")).contains("a.Unused"));
        SourcePasses.edits(d, List.of(new SourcePasses.SourceEdit("a/MyTile.java", "(extends )Base\\b", "\\1NewBase", "rebase")));
        assertTrue(Text.read(d.resolve("a/MyTile.java")).contains("extends NewBase"));
        assertThrows(IllegalStateException.class, () -> SourcePasses.edits(d,
                List.of(new SourcePasses.SourceEdit("a/MyTile.java", "no such text", "", "gone"))));
    }

    @Test
    void pythonStyleTemplates() {
        assertEquals("b-a\n", Text.subn(Pattern.compile("(a)(b)"), "\\2-\\g<1>\\n", "ab", 1).text());
    }

    @Test
    void srgMapFromTinyAndPass() {
        Text.write(tmp.resolve("m.tiny"), "tiny\t2\t0\tobf\tmojmap\tsrg\nc\tabc\tnet/minecraft/world/level/Level\tC_1_\n"
                + "\tm\t()V\ta\tgetGameTime\tm_46467_\n\tf\tI\tb\tisClientSide\tf_46443_\n");
        SrgMap m = SrgMap.fromTiny(tmp.resolve("m.tiny"));
        assertEquals(2, m.size());
        src(tmp.resolve("s"), "a.U", "class U { long t(Object l) { return l.m_46467_(); } }\n");
        assertEquals(1, SourcePasses.srgToMojmap(tmp.resolve("s"), m));
        assertTrue(Text.read(tmp.resolve("s/a/U.java")).contains("l.getGameTime()"));
    }

    @Test
    void castFixAndSymbols() {
        Path p = tmp.resolve("proj");
        Text.write(p.resolve("src/A.java"), "class A {\n    void f(java.util.List l) {\n        for (String s : l.get(0)) {\n        }\n        g((Object) s2);\n    }\n}\n");
        String log = "src/A.java:3: error: incompatible types: Object cannot be converted to String\n"
                + "        for (String s : l.get(0)) {\n                         ^\n"
                + "src/A.java:5: error: incompatible types: Object cannot be converted to String\n"
                + "        g((Object) s2);\n          ^\n"
                + "src/A.java:7: error: cannot find symbol\n  symbol:   method m_46467_()\n  location: class Level\n"
                + "src/A.java:8: error: cannot find symbol\n  symbol:   method m_46467_()\n  location: class Level\n"
                + "src/B.java:1: error: package net.minecraft.world does not exist\nimport net.minecraft.world.InteractionHand;\n       ^\n"
                + "5 errors\n";
        assertEquals(2, CastFix.apply(log, p));
        String a = Text.read(p.resolve("src/A.java"));
        assertTrue(a.contains("for (String s : (Iterable<String>) (Iterable) (l.get(0))) {"), a);
        assertTrue(a.contains("g( s2);") || a.contains("g(s2);"), a);
        Symbols.Result r = Symbols.aggregate(JavacLog.parse(log), SrgMap.empty(), "1.19.2");
        assertEquals(5, r.errors());
        assertEquals(2, r.symbols().stream().filter(e -> e.key().equals("method Level#m_46467_")).findFirst().orElseThrow().count());
        assertTrue(r.symbols().stream().anyMatch(e -> e.key().equals("class net.minecraft.world.InteractionHand")));
    }

    @Test
    void adapterPackIsBundledAndRelocates() {
        List<String> idx = Adapters.index();
        assertTrue(idx.size() > 50, "adapter pack in the jar: " + idx.size());
        int n = Adapters.write(tmp.resolve("ad"), "my.mod.shim");
        assertEquals(idx.size(), n);
        String bs = Text.read(tmp.resolve("ad/my/mod/shim/mc/BlockState.java"));
        assertTrue(bs.contains("package my.mod.shim.mc;") && !bs.contains("dev.porter.shim"), "relocated");
    }

    @Test
    void gradleWrapperOnWindows() throws Exception {
        // before: "./gradlew" could not be started on Windows (CreateProcess error 193)
        java.nio.file.Path proj = tmp.resolve("proj");
        java.nio.file.Files.createDirectories(proj);
        java.nio.file.Files.writeString(proj.resolve("gradlew.bat"), "@echo off\n");
        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 10");
            assertEquals(proj.resolve("gradlew.bat").toString(), PortRunner.windowsWrapper(java.util.List.of("./gradlew", "build"), proj).get(0));
            System.setProperty("os.name", "Linux");
            assertEquals(java.util.List.of("./gradlew", "build"), PortRunner.windowsWrapper(java.util.List.of("./gradlew", "build"), proj));
        } finally {
            System.setProperty("os.name", os);
        }
    }
}
