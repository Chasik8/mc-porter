package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ApiIndexTest {
    private static String cls(String v, String n) {
        return "{\"version\":\"" + v + "\",\"kind\":\"class\",\"name\":\"" + n + "\"}";
    }

    private static String mem(String v, String kind, String o, String n, String d) {
        return "{\"version\":\"" + v + "\",\"kind\":\"" + kind + "\",\"owner\":\"" + o + "\",\"name\":\"" + n + "\",\"desc\":\"" + d + "\"}";
    }

    private static String edge(String from, String to, String status, double c) {
        return "{\"from\":" + from + (to == null ? "" : ",\"to\":" + to) + ",\"status\":\"" + status + "\",\"confidence\":" + c
                + ",\"evidence\":[\"x\"]}";
    }

    private static void edges(Path dir, String pair, String... edges) throws Exception {
        Files.writeString(dir.resolve("edges-" + pair + ".graph.json"),
                "{\"schemaVersion\":1,\"versions\":[{\"id\":\"a\",\"sources\":[{\"n\":1}]}],\"edges\":[" + String.join(",", edges) + "]}");
    }

    /** Chain with a rename, a signature change, a removal in the middle, a missing key and a split. */
    private static void graph(Path g) throws Exception {
        Files.createDirectories(g);
        String[] v = {"1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.7.10"};
        String be = "(Lp/BlockPos;)Lp/BE;";
        edges(g, "1.19.2__1.18.2",
                edge(cls(v[0], "p.Level"), cls(v[1], "p.Level"), "same", 0.99),
                edge(mem(v[0], "method", "p.Level", "getBlockEntity", be), mem(v[1], "method", "p.Level", "getBlockEntity", be), "same", 0.9),
                edge(mem(v[0], "method", "p.Level", "gone", "()V"), mem(v[1], "method", "p.Level", "gone", "()V"), "same", 0.9),
                edge(mem(v[0], "field", "p.Level", "orphan", "I"), mem(v[1], "field", "p.Level", "orphan", "I"), "same", 0.8),
                edge(cls(v[0], "p.Split"), cls(v[1], "p.SplitA"), "split", 0.5),
                edge(cls(v[0], "p.Split"), cls(v[1], "p.SplitB"), "split", 0.4));
        edges(g, "1.18.2__1.16.5",
                edge(cls(v[1], "p.Level"), cls(v[2], "p.Level"), "same", 0.99),
                edge(mem(v[1], "method", "p.Level", "getBlockEntity", be), mem(v[2], "method", "p.Level", "getBlockEntity", be), "same", 0.9),
                edge(mem(v[1], "method", "p.Level", "gone", "()V"), null, "removed", 0.7),
                edge(cls(v[1], "p.SplitA"), cls(v[2], "p.SplitA"), "same", 1.0));
        edges(g, "1.16.5__1.12.2",
                edge(cls(v[2], "p.Level"), cls(v[3], "p.World"), "renamed", 0.9),
                edge(mem(v[2], "method", "p.Level", "getBlockEntity", be),
                        mem(v[3], "method", "p.World", "getTileEntity", "(Lp/BlockPos;)Lp/TE;"), "signature_changed", 0.8),
                edge(cls(v[2], "p.SplitA"), cls(v[3], "p.SplitA"), "same", 1.0));
        edges(g, "1.12.2__1.7.10",
                edge(cls(v[3], "p.World"), cls(v[4], "p.World"), "same", 0.99),
                edge(mem(v[3], "method", "p.World", "getTileEntity", "(Lp/BlockPos;)Lp/TE;"),
                        mem(v[4], "method", "p.World", "getTileEntity", "(III)Lp/TE;"), "signature_changed", 0.7),
                edge(cls(v[3], "p.SplitA"), cls(v[4], "p.SplitA"), "same", 1.0));
    }

    private static byte[] clazz(String name, String sup, String itf, String[] fields, String[] methods) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, sup, itf == null ? null : new String[] {itf});
        for (String f : fields) {
            w.visitField(Opcodes.ACC_PUBLIC, f.split(":")[0], f.split(":")[1], null, null).visitEnd();
        }
        for (String m : methods) {
            w.visitMethod(Opcodes.ACC_PUBLIC, m.split(":")[0], m.split(":")[1], null, null).visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static Path jar(Path f, String[] names, byte[][] data) throws Exception {
        try (OutputStream o = Files.newOutputStream(f); ZipOutputStream z = new ZipOutputStream(o)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i] + ".class"));
                z.write(data[i]);
                z.closeEntry();
            }
        }
        return f;
    }

    private static Path targetJar(Path dir) throws Exception {
        return jar(dir.resolve("t.jar"), new String[] {"p/World", "p/Outer$Inner"},
                new byte[][] {clazz("p/World", "java/lang/Object", "p/IWorld", new String[] {"f:I"}, new String[] {"getTileEntity:(III)Lp/TE;", "<init>:()V"}),
                        clazz("p/Outer$Inner", "java/lang/Object", null, new String[0], new String[0])});
    }

    private static Path sourceJar(Path dir) throws Exception {
        return jar(dir.resolve("s.jar"), new String[] {"net/minecraft/p/Level"},
                new byte[][] {clazz("net/minecraft/p/Level", "java/lang/Object", null, new String[0], new String[0])});
    }

    private static Path tiny(Path dir) throws Exception {
        Path t = dir.resolve("1.7.10.tiny");
        Files.writeString(t, "tiny\t2\t0\tobf\tsrg\tmcp\nc\ta\tp/World\tp/World\n\tm\t()V\tb\tfunc_1_a\ttick\n\tf\tI\tc\tfield_2_b\tcount\n"
                + "\tm\t()V\td\tgetTileEntity\tgetTileEntity\n\t\tp\t0\tx\tx\tx\n");
        return t;
    }

    @Test
    void composesPerHopAndStopsAtRemoved(@TempDir Path tmp) throws Exception {
        graph(tmp.resolve("g"));
        List<Path> files = ApiIndex.PAIRS.stream().map(p -> ApiIndex.edgeFile(tmp.resolve("g"), p)).toList();
        List<LineageTable.Row> rows = LineageTable.compose(files, ApiIndex.PAIRS);
        assertEquals(5, rows.size());
        assertEquals("class\tp.Level\t\t\tp.World\t\t\tsame,same,renamed,same\t0.873\t", rows.get(0).line());
        assertEquals("method\tp.Level\tgetBlockEntity\t(Lp/BlockPos;)Lp/BE;\tp.World\tgetTileEntity\t(III)Lp/TE;\t"
                + "same,same,signature_changed,signature_changed\t0.454\t", rows.get(1).line());
        assertEquals("method\tp.Level\tgone\t()V\t\t\t\tsame,removed\t0.630\tremoved@1.18.2__1.16.5", rows.get(2).line());
        assertEquals("field\tp.Level\torphan\tI\t\t\t\tsame\t0.800\tnokey@1.18.2__1.16.5", rows.get(3).line());
        // split: the first edge wins
        assertEquals("class\tp.Split\t\t\tp.SplitA\t\t\tsplit,same,same,same\t0.500\t", rows.get(4).line());
    }

    @Test
    void buildsAllFilesAndCachesByHash(@TempDir Path tmp) throws Exception {
        graph(tmp.resolve("g"));
        ApiIndex.Inputs in = new ApiIndex.Inputs(tmp.resolve("g"), targetJar(tmp), List.of(sourceJar(tmp)), tiny(tmp), List.of("net.minecraft."));
        Path out = tmp.resolve("out");
        ApiIndex.Stats s = ApiIndex.build(in, out, false);
        assertEquals(false, s.cached());
        assertEquals(5, s.lineageRows());
        assertEquals(2, s.classes());
        assertEquals(3, s.members());
        assertEquals(1, s.sourceClasses());
        assertEquals(2, s.srgNames());
        assertEquals(List.of("class\tp.Outer$Inner\tjava.lang.Object\t\t1", "class\tp.World\tjava.lang.Object\tp.IWorld\t1",
                "member\tp.World\tfield\tf\tI\t1", "member\tp.World\tmethod\tgetTileEntity\t(III)Lp/TE;\t1",
                "member\tp.World\tmethod\t<init>\t()V\t1"), Files.readAllLines(out.resolve("target.tsv")));
        assertEquals(List.of("field_2_b\tcount", "func_1_a\ttick"), Files.readAllLines(out.resolve("srg-1.7.10.tsv")));
        assertTrue(Files.readString(out.resolve("source-hier.tsv")).contains("net.minecraft.p.Level\tjava.lang.Object\t\t1"));
        assertTrue(Files.readString(out.resolve("manifest.json")).contains("edges-1.19.2__1.18.2.graph.json"));
        assertEquals(true, ApiIndex.build(in, out, false).cached());
        // a changed input rebuilds
        edges(tmp.resolve("g"), "1.12.2__1.7.10");
        assertEquals(false, ApiIndex.build(in, out, false).cached());
    }

    @Test
    void missingGraphNamesTheGraphCommand(@TempDir Path tmp) throws Exception {
        ApiIndex.Inputs in = new ApiIndex.Inputs(tmp, targetJar(tmp), List.of(sourceJar(tmp)), tiny(tmp), List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiIndex.build(in, tmp.resolve("o"), false));
        assertTrue(e.getMessage().contains("porter graph build"), e.getMessage());
    }
}
