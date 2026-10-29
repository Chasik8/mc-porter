package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Synthetic jars only: a 1.19.2-like client jar (obfuscated names) and a tiny 1.7.10 jar. */
class UpfrontFlatteningTest {
    private static byte[] cls(String name, String[] fields, String method, boolean isStatic, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String f : fields) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, f, "Ljava/lang/Object;", null, null).visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), method, "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void jar(Path p, Map<String, byte[]> classes) throws Exception {
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            for (var e : classes.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    private static void put(MethodVisitor mv, String k, String v) {
        mv.visitLdcInsn(k);
        mv.visitLdcInsn(v);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private static void reg(MethodVisitor mv, String id, String owner, String field) {
        mv.visitLdcInsn(id);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "h/R", "r", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Ljava/lang/Object;");
    }

    private static void state(MethodVisitor mv, int id, String name) {
        mv.visitIntInsn(Opcodes.SIPUSH, id);
        mv.visitLdcInsn("{Name:'minecraft:" + name + "'}");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "h/S", "a", "(ILjava/lang/String;)V", false);
    }

    private static void addObject(MethodVisitor mv, int id, String name) {
        mv.visitIntInsn(Opcodes.SIPUSH, id);
        mv.visitLdcInsn(name);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/RegistryNamespaced", "addObject", "(ILjava/lang/String;Ljava/lang/Object;)V", false);
    }

    @Test
    void derivesRowsFromSyntheticJars(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> client = new LinkedHashMap<>();
        client.put("h/Fix", cls("h/Fix", new String[0], "a", true, mv -> {
            put(mv, "minecraft:stone.0", "minecraft:stone");
            put(mv, "minecraft:stone.1", "minecraft:granite");
            put(mv, "minecraft:coal.1", "minecraft:charcoal");
            put(mv, "minecraft:fish.5", "minecraft:foo_fish");
            put(mv, "minecraft:sign", "minecraft:oak_sign");
        }));
        client.put("h/St", cls("h/St", new String[0], "b", true, mv -> {
            state(mv, 16, "stone");
            state(mv, 17, "granite");
        }));
        client.put("h/Blocks", cls("h/Blocks", new String[] {"a", "b"}, "<clinit>", true, mv -> {
            reg(mv, "stone", "h/Blocks", "a");
            reg(mv, "granite", "h/Blocks", "b");
        }));
        client.put("h/Items", cls("h/Items", new String[] {"a", "b", "c", "d"}, "<clinit>", true, mv -> {
            reg(mv, "granite", "h/Items", "a");
            reg(mv, "charcoal", "h/Items", "b");
            reg(mv, "mystery", "h/Items", "c");
            mv.visitFieldInsn(Opcodes.GETSTATIC, "h/Blocks", "a", "Ljava/lang/Object;");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "h/R", "b", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, "h/Items", "d", "Ljava/lang/Object;");
        }));
        client.put("h/Sounds", cls("h/Sounds", new String[] {"a"}, "<clinit>", true, mv -> reg(mv, "ui.button.click", "h/Sounds", "a")));
        Path clientJar = tmp.resolve("client.jar");
        jar(clientJar, client);

        Map<String, byte[]> target = new LinkedHashMap<>();
        target.put("net/minecraft/block/Block", cls("net/minecraft/block/Block", new String[0], "registerBlocks", true, mv -> addObject(mv, 1, "stone")));
        target.put("net/minecraft/item/Item", cls("net/minecraft/item/Item", new String[0], "registerItems", true, mv -> {
            addObject(mv, 1, "stone");
            addObject(mv, 263, "coal");
        }));
        target.put("net/minecraft/init/Blocks", cls("net/minecraft/init/Blocks", new String[] {"stone"}, "<clinit>", true,
                mv -> reg(mv, "stone", "net/minecraft/init/Blocks", "stone")));
        target.put("net/minecraft/init/Items", cls("net/minecraft/init/Items", new String[] {"coal"}, "<clinit>", true,
                mv -> reg(mv, "coal", "net/minecraft/init/Items", "coal")));
        Path targetJar = tmp.resolve("mcp.jar");
        jar(targetJar, target);

        Path tiny = tmp.resolve("1.19.2.tiny");
        Files.writeString(tiny, String.join("\n", "tiny\t2\t0\tobf\tmojmap\tsrg",
                "c\th/Items\tnet/minecraft/world/item/Items\tx", "\tf\tLjava/lang/Object;\ta\tGRANITE\tx", "\tf\tLjava/lang/Object;\tb\tCHARCOAL\tx",
                "\tf\tLjava/lang/Object;\tc\tMYSTERY\tx", "\tf\tLjava/lang/Object;\td\tSTONE\tx",
                "c\th/Blocks\tnet/minecraft/world/level/block/Blocks\tx", "\tf\tLjava/lang/Object;\ta\tSTONE\tx", "\tf\tLjava/lang/Object;\tb\tGRANITE\tx",
                "c\th/Sounds\tnet/minecraft/sounds/SoundEvents\tx", "\tf\tLjava/lang/Object;\ta\tUI_BUTTON_CLICK\tx", ""));

        StringBuilder stats = new StringBuilder();
        List<Flattening.Row> rows = Flattening.derive(clientJar, tiny, targetJar, stats);
        Map<String, Flattening.Row> by = new LinkedHashMap<>();
        rows.forEach(r -> by.put(r.registry() + "." + r.field(), r));

        Flattening.Row g = by.get("Items.GRANITE");
        assertEquals("BLOCK_ITEM", g.kind());
        assertEquals("Item.getItemFromBlock(Blocks.stone)", g.expr());
        assertEquals("1", g.meta());
        Flattening.Row c = by.get("Items.CHARCOAL");
        assertEquals("ITEM", c.kind());
        assertEquals("Items.coal", c.expr());
        assertEquals("1", c.meta());
        assertEquals("BLOCK_ITEM", by.get("Items.STONE").kind());
        assertEquals("", by.get("Items.STONE").meta());
        assertEquals("ABSENT", by.get("Items.MYSTERY").kind());
        Flattening.Row b = by.get("Blocks.GRANITE");
        assertEquals("Blocks.stone", b.expr());
        assertEquals("1", b.meta());
        assertEquals("TODO", by.get("SoundEvents.UI_BUTTON_CLICK").kind());
        assertTrue(stats.toString().contains("flatten pairs 4"), stats.toString());
    }
}
