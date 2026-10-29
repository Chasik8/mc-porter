package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Todo batch and bodies check, synthetic fixtures only. */
class UpfrontFillTest {
    private static void put(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    // ---- todo batch ----

    private static List<TodoEntry> thirtyRows() {
        int[] sizes = {6, 5, 4, 4, 3, 3, 2, 1, 1, 1};
        String[] areas = {"world", "world", "item", "item", "world", "gui", "gui", "item", "world", "gui"};
        List<TodoEntry> out = new ArrayList<>();
        for (int o = 0; o < sizes.length; o++) {
            String owner = "net.minecraft.O" + o;
            for (int m = 0; m < sizes[o]; m++) {
                out.add(new TodoEntry(SymbolKey.parse("method:" + owner + "#m" + m + "()V"), owner, "public void m" + m + "()",
                        m % 3 == 2 ? Plan.HINT : Plan.TODO, m % 3 == 2 ? "lineage target x" : "",
                        m == 0 ? List.of(new Alt("old.W", "f", "()V", 0.5), new Alt("old.W", "g", "()V", 0.4)) : List.of(),
                        2, areas[o]));
            }
        }
        assertEquals(30, out.size());
        return out;
    }

    @Test
    void splitKeepsOwnersWholeAndBalances() {
        List<TodoEntry> todos = thirtyRows();
        TodoBatch.Result r = TodoBatch.split(todos, 4, null, null, null);
        assertEquals(4, r.batches().size());
        Map<String, Integer> ownerWorker = new HashMap<>();
        int entries = 0;
        for (TodoBatch.Batch b : r.batches()) {
            for (TodoBatch.Item it : b.items()) {
                Integer prev = ownerWorker.put(it.todo().owner(), b.worker());
                assertTrue(prev == null || prev == b.worker(), "owner split: " + it.todo().owner());
                entries++;
            }
        }
        assertEquals(30, entries);
        double total = todos.stream().mapToDouble(TodoBatch::weight).sum();
        double avg = total / 4;
        for (TodoBatch.Batch b : r.batches()) {
            assertTrue(Math.abs(b.weight() - avg) <= avg * 0.2, "weight " + b.weight() + " vs avg " + avg);
        }
        assertTrue(r.maxWeight() / r.minWeight() <= 1.3);
        // deterministic
        assertEquals(TodoBatch.renderIndex(r.batches()), TodoBatch.renderIndex(TodoBatch.split(todos, 4, null, null, null).batches()));
    }

    @Test
    void duplicateKeyIsAnError() {
        List<TodoEntry> todos = new ArrayList<>(thirtyRows());
        todos.add(todos.get(0));
        assertThrows(IllegalArgumentException.class, () -> TodoBatch.split(todos, 4, null, null, null));
    }

    @Test
    void batchFilesCarryCallSitesAndIndex(@TempDir Path tmp) throws IOException {
        put(tmp, "src/mod/Machine.java", "package mod;\nimport a.B;\npublic class Machine {\n  void f(B b) {\n    b.m0();\n"
                + "    // b.m0() in a comment\n    b.m0(); b.other();\n  }\n}\n");
        SymbolKey k = SymbolKey.parse("method:net.minecraft.O0#m0()V");
        ApiInventory inv = new ApiInventory(1, "forge-1.19.2", "addon", new ApiInventory.Sources("", "", List.of()),
                List.of(new ApiSymbol(k, 2, Set.of("bytecode"), 1, "net.minecraft.O0", List.of("mod.Machine", "mod.Missing"), null)),
                new ApiInventory.Summary(0, 1, Map.of()));
        TodoBatch.Result r = TodoBatch.split(thirtyRows(), 4, null, inv, tmp.resolve("src"));
        Path out = tmp.resolve("todo-batch");
        TodoBatch.write(r, out);
        String all = "";
        for (int i = 1; i <= 4; i++) {
            all += Files.readString(out.resolve(i + ".md"));
        }
        assertTrue(all.contains("- call site: `Machine.java:5: b.m0();`"), all);
        assertTrue(all.contains("- call site: `Machine.java:7: b.m0(); b.other();`"), all);
        assertTrue(all.contains("- candidates: `old.W#f ()V` 0.5;"), all);
        assertTrue(all.contains("- hint: lineage target x"), all);
        String idx = Files.readString(out.resolve("index.tsv"));
        assertEquals(5, idx.split("\n").length, idx);
        assertTrue(idx.startsWith("worker\tfile\tentries\tweight\towners\tarea\tbodies\n"));
    }

    // ---- bodies check ----

    private static final String KEY_OK = "method:net.minecraft.world.Gadget#size()I";
    private static final String KEY_BAD = "method:net.minecraft.world.Gadget#bad()I";
    private static final String KEY_SYN = "method:net.minecraft.world.Gadget#syn()I";
    private static final String KEY_IMP = "method:net.minecraft.world.Gadget#list()Ljava/lang/Object;";
    private static final String KEY_TODO = "method:net.minecraft.world.Gadget#todo()V";
    private static final String KEY_FRAG = "method:net.minecraft.world.Holder#reset()V";

    private static String member(String key, String sig, String stub) {
        return "    /** porter:upfront " + key + " */\n    " + sig + " {\n        // porter:body " + key + "\n        " + stub
                + "\n        // porter:end\n    }\n\n";
    }

    private Path genDir(Path tmp) throws IOException {
        Path gen = tmp.resolve("gen");
        put(gen, "src/dev/porter/shim/gen/world/Gadget.java", "// porter:generated\npackage dev.porter.shim.gen.world;\n\n@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n"
                + "public class Gadget {\n"
                + member(KEY_OK, "public int size()", "throw new UnsupportedOperationException(\"porter:todo size\");")
                + member(KEY_BAD, "public int bad()", "throw new UnsupportedOperationException(\"porter:todo bad\");")
                + member(KEY_SYN, "public int syn()", "throw new UnsupportedOperationException(\"porter:todo syn\");")
                + member(KEY_IMP, "public Object list()", "throw new UnsupportedOperationException(\"porter:todo list\");")
                + member(KEY_TODO, "public void todo()", "throw new UnsupportedOperationException(\"porter:todo todo\");")
                + "}\n");
        put(gen, "fragments/Holder.members.java", "// porter:generated (shimgen) - members to add\n\n// a.Holder\n"
                + member(KEY_FRAG, "public void reset()", "throw new UnsupportedOperationException(\"porter:todo reset\");"));
        List<TodoEntry> todos = new ArrayList<>();
        for (String k : List.of(KEY_OK, KEY_BAD, KEY_SYN, KEY_IMP, KEY_TODO, KEY_FRAG)) {
            todos.add(new TodoEntry(SymbolKey.parse(k), "o", "sig", Plan.TODO, "", List.of(), 1, "world"));
        }
        TodoEntry.write(gen.resolve("todo.tsv"), todos);
        return gen;
    }

    private static String doc(String key, String body, String imports, String verified) {
        return "key: \"" + key + "\"\nbody: |-\n  " + body.replace("\n", "\n  ") + "\nimports: " + imports + "\nverified: " + verified + "\n";
    }

    private Path stubJar(Path tmp) throws IOException {
        Path cp = tmp.resolve("cp");
        put(cp, "src/old/World.java", "package old;\npublic class World { public int light() { return 1; } }\n");
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path classes = tmp.resolve("cp-classes");
        Files.createDirectories(classes);
        assertEquals(0, jc.run(null, null, null, "--release", "8", "-d", classes.toString(), cp.resolve("src/old/World.java").toString()));
        return classes;
    }

    @Test
    void emptyDbReportsExactlyTheTodoCount(@TempDir Path tmp) throws IOException {
        Path gen = genDir(tmp);
        BodiesCheck.Result r = BodiesCheck.check(Map.of(), gen, List.of(stubJar(tmp)), null, gen.resolve("todo.tsv"), tmp.resolve("work"));
        assertEquals(6, r.todo());
        assertEquals(0, r.bodies());
        assertTrue(r.success(), r.genErrors().toString());
    }

    @Test
    void oneJavacSplitsGoodFromBadAndMarksVerified(@TempDir Path tmp) throws IOException {
        Path gen = genDir(tmp);
        Path bodies = tmp.resolve("bodies");
        put(bodies, "world-1.yaml", "# header\n" + doc(KEY_OK, "return new old.World().light();", "[]", "none")
                + "---\n" + doc(KEY_BAD, "return \"text\";", "[]", "compile")
                + "---\n" + doc(KEY_SYN, "return (1 +;", "[]", "none")
                + "---\n" + doc(KEY_IMP, "return new ArrayList<String>();", "[java.util.ArrayList]", "none")
                + "---\n" + doc(KEY_FRAG, "int x = new old.World().light();", "[]", "none")
                + "---\n" + doc("method:net.minecraft.world.Gone#x()V", "return;", "[]", "none"));
        Map<SymbolKey, Path> fileOf = new HashMap<>();
        Map<SymbolKey, BodyEntry> db = BodiesCheck.load(List.of(bodies), fileOf);
        BodiesCheck.Result r = BodiesCheck.check(db, gen, List.of(stubJar(tmp)), null, gen.resolve("todo.tsv"), tmp.resolve("work"));

        assertTrue(r.genErrors().isEmpty(), r.genErrors().toString());
        assertEquals(Set.of(SymbolKey.parse(KEY_BAD), SymbolKey.parse(KEY_SYN)), r.failed().keySet(), r.failed().toString());
        assertTrue(r.failed().get(SymbolKey.parse(KEY_SYN)).startsWith("syntax:"), r.failed().toString());
        assertEquals(Set.of(SymbolKey.parse(KEY_OK), SymbolKey.parse(KEY_IMP), SymbolKey.parse(KEY_FRAG)), new HashSet<>(r.ok()));
        assertEquals(List.of(SymbolKey.parse("method:net.minecraft.world.Gone#x()V")), r.orphans());
        assertEquals(1, r.todo());
        assertFalse(r.success());

        int changed = BodiesCheck.markVerified(fileOf, db, r.ok(), r.failed().keySet());
        assertEquals(4, changed);
        Map<SymbolKey, BodyEntry> after = BodiesCheck.load(List.of(bodies), new HashMap<>());
        assertEquals("compile", after.get(SymbolKey.parse(KEY_OK)).verified());
        assertEquals("compile", after.get(SymbolKey.parse(KEY_IMP)).verified());
        assertEquals("compile", after.get(SymbolKey.parse(KEY_FRAG)).verified());
        assertEquals("none", after.get(SymbolKey.parse(KEY_BAD)).verified());
        assertEquals("none", after.get(SymbolKey.parse(KEY_SYN)).verified());
        assertEquals("return new old.World().light();", after.get(SymbolKey.parse(KEY_OK)).body());
        assertTrue(Files.readString(bodies.resolve("world-1.yaml")).startsWith("# header\n"));
    }

    @Test
    void duplicateBodyKeysAcrossFilesAreRejected(@TempDir Path tmp) throws IOException {
        Path bodies = tmp.resolve("bodies");
        put(bodies, "a-1.yaml", doc(KEY_OK, "return 1;", "[]", "none"));
        put(bodies, "b-2.yaml", doc(KEY_OK, "return 2;", "[]", "none"));
        assertThrows(IllegalArgumentException.class, () -> BodiesCheck.load(List.of(bodies), new HashMap<>()));
    }

    @Test
    void helperSourcesAreCompiledWithTheBodies(@TempDir Path tmp) throws IOException {
        Path gen = genDir(tmp);
        Path bodies = tmp.resolve("bodies");
        put(bodies, "helpers/Util.java", "package dev.porter.shim.gen;\npublic final class Util { public static int one() { return 1; } }\n");
        put(bodies, "world-1.yaml", doc(KEY_OK, "return dev.porter.shim.gen.Util.one();", "[]", "none"));
        Map<SymbolKey, BodyEntry> db = BodiesCheck.load(List.of(bodies), new HashMap<>());
        BodiesCheck.Result r = BodiesCheck.check(db, gen, List.of(stubJar(tmp)), bodies.resolve("helpers"), null, tmp.resolve("work"));
        assertTrue(r.success(), r.failed() + " " + r.genErrors());
    }
}
