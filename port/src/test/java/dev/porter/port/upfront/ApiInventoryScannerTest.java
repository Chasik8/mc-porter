package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Synthetic fixtures only: a fake platform {@code net/minecraft/a/Foo}, a mod that uses and overrides it. */
class ApiInventoryScannerTest implements Opcodes {
    private static final String FOO = "net/minecraft/a/Foo";
    private static final String BAR = "net/minecraft/a/Bar";

    private static byte[] cls(String name, String sup, String[] itf, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, sup, itf);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor method(ClassWriter cw, int acc, String n, String d) {
        MethodVisitor mv = cw.visitMethod(acc, n, d, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    private static Path jar(Path dir, Map<String, byte[]> classes) throws IOException {
        Path j = dir.resolve("mod.jar");
        try (OutputStream o = Files.newOutputStream(j); ZipOutputStream z = new ZipOutputStream(o)) {
            for (var e : classes.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return j;
    }

    private static ApiInventoryScanner.Sym find(ApiInventoryScanner.Result r, String key) {
        return r.symbols().stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    @Test
    void symbolsDeclaringClassesOverridesLambdaArraysCtors(@TempDir Path dir) throws IOException {
        // platform: Foo declares m_1_ (Bar -> I) and field f; Bar extends Foo. Described by a tiny + a hierarchy table.
        Path tiny = dir.resolve("1.19.2.tiny");
        Files.writeString(tiny, "tiny\t2\t0\tobf\tmojmap\tsrg\n"
                + "c\ta\t" + FOO + "\tnet/minecraft/src/C_1_\n"
                + "\tm\t(Lb;)I\ta\ttick\tm_1_\n"
                + "\tm\t()V\t<init>\t<init>\t<init>\n"
                + "\tf\tI\tb\tcount\tf_2_\n"
                + "c\tb\t" + BAR + "\tnet/minecraft/src/C_2_\n");
        Path hier = dir.resolve("hier.tsv");
        Files.writeString(hier, "# header\n" + "net.minecraft.a.Foo\tjava.lang.Object\t\t33\n"
                + "net.minecraft.a.Bar\tnet.minecraft.a.Foo\t\t33\n");

        Map<String, byte[]> cs = new TreeMap<>();
        // mod class A: calls Bar.m_1_ (declared in Foo), reads Bar.f_2_, new Bar(), array owner clone + a lambda, overrides m_1_
        cs.put("m/A", cls("m/A", FOO, null, cw -> {
            MethodVisitor init = method(cw, ACC_PUBLIC, "<init>", "()V");
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, FOO, "<init>", "()V", false);
            end(init);
            MethodVisitor mv = method(cw, ACC_PUBLIC, "use", "(L" + BAR + ";[L" + BAR + ";)V");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, BAR, "m_1_", "(L" + BAR + ";)I", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, BAR, "f_2_", "I");
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, BAR);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, "[L" + BAR + ";", "clone", "()Ljava/lang/Object;", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "m/A", "m_1_", "(L" + BAR + ";)I", false); // via the mod subclass
            mv.visitInsn(POP);
            Handle h = new Handle(H_INVOKEVIRTUAL, BAR, "m_1_", "(L" + BAR + ";)I", false);
            mv.visitLdcInsn(h);
            mv.visitInsn(POP);
            end(mv);
            MethodVisitor ov = method(cw, ACC_PUBLIC, "m_1_", "(L" + BAR + ";)I");
            ov.visitInsn(ICONST_0);
            ov.visitInsn(IRETURN);
            ov.visitMaxs(1, 2);
            ov.visitEnd();
        }));
        // mod class B in a platform package: must be excluded; class C calls it
        cs.put("net/minecraft/a/Mine", cls("net/minecraft/a/Mine", "java/lang/Object", null, cw -> {
            end(method(cw, ACC_PUBLIC, "x", "()V"));
        }));
        cs.put("m/C", cls("m/C", "java/lang/Object", null, cw -> {
            MethodVisitor mv = method(cw, ACC_PUBLIC, "go", "()V");
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/a/Mine", "x", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
            end(mv);
        }));
        Path jar = jar(dir, cs);
        PlatformIndex idx = new PlatformIndex();
        idx.loadHier(hier);
        idx.loadTiny(tiny);

        ApiInventoryScanner.Result r = new ApiInventoryScanner(idx,
                new ApiInventoryScanner.Options(jar, null, List.of(), List.of(), true)).scan();
        List<String> keys = r.symbols().stream().map(ApiInventoryScanner.Sym::key).toList();
        assertEquals(List.of(
                "field:net.minecraft.a.Foo#count:I",
                "method:net.minecraft.a.Foo#<init>()V",
                "method:net.minecraft.a.Foo#tick(Lnet/minecraft/a/Bar;)I",
                "type:net.minecraft.a.Bar",
                "type:net.minecraft.a.Foo"), keys);
        assertFalse(keys.stream().anyMatch(k -> k.contains("Mine") || k.contains("clone") || k.contains("Math")));
        assertEquals(2, r.types());
        assertEquals(3, r.members());
        ApiInventoryScanner.Sym tick = r.symbols().stream().filter(s -> s.key().startsWith("method:net.minecraft.a.Foo#tick")).findFirst().orElseThrow();
        assertEquals(List.of("bytecode", "override"), List.copyOf(tick.origin));
        assertEquals(List.of("m.A"), List.copyOf(tick.refClasses));
        assertEquals("srg", tick.category());
        assertEquals(Map.of("srg", 2, "forge", 0, "ctor", 1, "mcNonSrg", 0, "lib", 0), r.byOrigin());
        assertTrue(find(r, "type:net.minecraft.a.Bar").origin.contains("bytecode"));
        assertEquals(33, find(r, "type:net.minecraft.a.Foo").access);

        // without overrides, a mod that only overrides contributes nothing extra
        ApiInventoryScanner.Result r2 = new ApiInventoryScanner(idx,
                new ApiInventoryScanner.Options(jar, null, List.of(), List.of(), false)).scan();
        assertEquals(List.of("bytecode"), List.copyOf(find(r2, tick.key()).origin));
    }

    @Test
    void sourceUniverseAddsImportsAndSrgIds(@TempDir Path dir) throws IOException {
        Path tiny = dir.resolve("1.19.2.tiny");
        Files.writeString(tiny, "tiny\t2\t0\tobf\tmojmap\tsrg\n"
                + "c\ta\t" + FOO + "\tnet/minecraft/src/C_1_\n"
                + "\tm\t()I\ta\ttick\tm_1_\n");
        Path hier = dir.resolve("hier.tsv");
        Files.writeString(hier, "net.minecraft.a.Foo\tjava.lang.Object\t\t33\nnet.minecraft.a.Foo$In\tjava.lang.Object\t\t9\n");
        Path src = dir.resolve("src/m");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "package m;\nimport net.minecraft.a.Foo;\nimport net.minecraft.a.Foo.In;\nimport java.util.List;\n"
                + "class A { void x(Foo f){ f.m_1_(); f.m_1_(); } }\n");
        Path jar = jar(dir, Map.of("m/A", cls("m/A", "java/lang/Object", null, cw -> end(method(cw, ACC_PUBLIC, "x", "()V")))));
        Path list = dir.resolve("classes.txt");
        Files.writeString(list, "m.A\n");
        PlatformIndex idx = new PlatformIndex();
        idx.loadHier(hier);
        idx.loadTiny(tiny);
        ApiInventoryScanner.Result r = new ApiInventoryScanner(idx,
                new ApiInventoryScanner.Options(jar, dir.resolve("src"), List.of(list), List.of(), true)).scan();
        assertEquals(List.of("method:net.minecraft.a.Foo#tick()I", "type:net.minecraft.a.Foo", "type:net.minecraft.a.Foo$In"),
                r.symbols().stream().map(ApiInventoryScanner.Sym::key).toList());
        assertEquals(2, find(r, "method:net.minecraft.a.Foo#tick()I").uses());
        assertEquals(List.of("source"), List.copyOf(find(r, "type:net.minecraft.a.Foo").origin));
        assertEquals(1, r.sourceFiles());
    }
}
