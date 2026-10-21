package dev.porter.extract.forge1192.vanilla;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Builds a {@link VanillaIndex} from the real 1.19.2 artifacts in a data directory:
 *
 * <pre>
 *   client_mappings/client.txt           Mojang official mappings (piston-data, sha1 8e8c9be5...)
 *   mcp_config/config/joined.tsrg        MCPConfig 1.19.2-20220805.130853 (obf → srg), or the zip itself
 *   client_jar/client.jar                vanilla client (sha1 055b30d8...)
 *   forge/forge-1.19.2-*-universal.jar   Forge universal jar (net.minecraftforge.common.Tags)
 * </pre>
 *
 * Every missing input only reduces what the index knows; nothing is guessed.
 */
public final class VanillaIndexBuilder {
    public static final String CACHE_FILE = "porter-vanilla-index.json";

    /** Static registry holder classes and the namespace of their ids. */
    private static final Map<String, String> VANILLA_HOLDERS = new LinkedHashMap<>();

    static {
        VANILLA_HOLDERS.put("net.minecraft.world.level.block.Blocks", "block");
        VANILLA_HOLDERS.put("net.minecraft.world.item.Items", "item");
        VANILLA_HOLDERS.put("net.minecraft.world.effect.MobEffects", "effect");
        VANILLA_HOLDERS.put("net.minecraft.sounds.SoundEvents", "sound");
        VANILLA_HOLDERS.put("net.minecraft.world.level.material.Fluids", "fluid");
        VANILLA_HOLDERS.put("net.minecraft.tags.ItemTags", "tag");
        VANILLA_HOLDERS.put("net.minecraft.tags.BlockTags", "tag");
        VANILLA_HOLDERS.put("net.minecraft.tags.FluidTags", "tag");
        VANILLA_HOLDERS.put("net.minecraft.world.item.enchantment.Enchantments", "enchantment");
        VANILLA_HOLDERS.put("net.minecraft.world.entity.EntityType", "entity");
    }

    private final Map<String, String> classOfficialToObf = new HashMap<>();
    private final Map<String, String> classObfToOfficial = new HashMap<>();
    /** obf class → obf field → official field. */
    private final Map<String, Map<String, String>> fields = new HashMap<>();
    /** obf class → obf name + obf desc → official method. */
    private final Map<String, Map<String, String>> methods = new HashMap<>();

    private VanillaIndexBuilder() {
    }

    /** Loads the cached index from {@code dir}, building it first when missing or outdated. */
    public static VanillaIndex loadOrBuild(Path dir) {
        Path cache = dir.resolve(CACHE_FILE);
        if (Files.isRegularFile(cache)) {
            VanillaIndex idx = VanillaIndex.read(cache);
            if (idx.formatVersion() == VanillaIndex.FORMAT) {
                return idx;
            }
        }
        VanillaIndex idx = build(dir);
        idx.write(cache);
        return idx;
    }

    public static VanillaIndex build(Path dir) {
        VanillaIndexBuilder b = new VanillaIndexBuilder();
        Path clientTxt = dir.resolve("client_mappings/client.txt");
        if (!Files.isRegularFile(clientTxt)) {
            return VanillaIndex.empty();
        }
        b.readProguard(clientTxt);
        Map<String, String> srg = b.readTsrg(dir);
        Map<String, String> staticIds = new LinkedHashMap<>();
        Map<String, VanillaIndex.StateProp> props = new LinkedHashMap<>();
        Map<String, Integer> constants = new LinkedHashMap<>();
        Map<String, List<String>> enums = new LinkedHashMap<>();
        Path clientJar = dir.resolve("client_jar/client.jar");
        if (Files.isRegularFile(clientJar)) {
            try (ZipFile zip = new ZipFile(clientJar.toFile())) {
                for (Map.Entry<String, String> e : VANILLA_HOLDERS.entrySet()) {
                    b.scanHolder(zip, e.getKey(), e.getValue(), staticIds);
                }
                b.scanStateProperties(zip, props);
                b.scanScreenDefaults(zip, constants);
                for (String e : List.of("net.minecraft.world.item.DyeColor")) {
                    b.scanEnum(zip, e, enums);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String forgeVersion = null;
        Path forgeDir = dir.resolve("forge");
        if (Files.isDirectory(forgeDir)) {
            try (Stream<Path> s = Files.list(forgeDir)) {
                Path jar = s.filter(p -> p.getFileName().toString().endsWith("-universal.jar")).sorted().findFirst()
                        .orElse(null);
                if (jar != null) {
                    forgeVersion = jar.getFileName().toString().replace("forge-", "").replace("-universal.jar", "");
                    try (ZipFile zip = new ZipFile(jar.toFile())) {
                        for (String inner : List.of("Items", "Blocks", "Fluids")) {
                            scanForgeTags(zip, "net/minecraftforge/common/Tags$" + inner, staticIds);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new VanillaIndex(VanillaIndex.FORMAT, "1.19.2", forgeVersion, srg, staticIds, props, constants, enums);
    }

    // ---- mappings ------------------------------------------------------------------------------------------

    private void readProguard(Path file) {
        List<String[]> pendingMembers = new ArrayList<>();
        String currentObf = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            List<String> memberLines = new ArrayList<>();
            List<String> owners = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    int arrow = line.indexOf(" -> ");
                    String official = line.substring(0, arrow);
                    String obf = line.substring(arrow + 4, line.length() - 1);
                    classOfficialToObf.put(official, obf);
                    classObfToOfficial.put(obf, official);
                    currentObf = obf;
                } else {
                    memberLines.add(line.trim());
                    owners.add(currentObf);
                }
            }
            // Members need the full class table (descriptors), so they are resolved in a second pass.
            for (int i = 0; i < memberLines.size(); i++) {
                String m = memberLines.get(i);
                String owner = owners.get(i);
                int arrow = m.indexOf(" -> ");
                String left = m.substring(0, arrow);
                String obfName = m.substring(arrow + 4);
                int paren = left.indexOf('(');
                if (paren < 0) {
                    String[] parts = left.split(" ");
                    fields.computeIfAbsent(owner, k -> new HashMap<>()).put(obfName, parts[1]);
                } else {
                    String head = left.substring(0, paren);
                    String[] lineSplit = head.split(":");
                    head = lineSplit[lineSplit.length - 1];
                    String[] parts = head.split(" ");
                    String ret = parts[0];
                    String name = parts[1];
                    String args = left.substring(paren + 1, left.indexOf(')'));
                    StringBuilder desc = new StringBuilder("(");
                    if (!args.isEmpty()) {
                        for (String a : args.split(",")) {
                            desc.append(obfDescriptor(a));
                        }
                    }
                    desc.append(')').append(obfDescriptor(ret));
                    methods.computeIfAbsent(owner, k -> new HashMap<>()).put(obfName + desc, name);
                }
            }
            pendingMembers.clear();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String obfDescriptor(String javaType) {
        int dims = 0;
        while (javaType.endsWith("[]")) {
            dims++;
            javaType = javaType.substring(0, javaType.length() - 2);
        }
        String base = switch (javaType) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + classOfficialToObf.getOrDefault(javaType, javaType).replace('.', '/') + ";";
        };
        return "[".repeat(dims) + base;
    }

    private Map<String, String> readTsrg(Path dir) {
        Map<String, String> srg = new HashMap<>();
        Path tsrg = dir.resolve("mcp_config/config/joined.tsrg");
        try {
            BufferedReader reader;
            ZipFile zip = null;
            if (Files.isRegularFile(tsrg)) {
                reader = Files.newBufferedReader(tsrg, StandardCharsets.UTF_8);
            } else {
                Path zipPath;
                try (Stream<Path> s = Files.list(dir.resolve("mcp_config"))) {
                    zipPath = s.filter(p -> p.toString().endsWith(".zip")).findFirst().orElse(null);
                } catch (IOException e) {
                    zipPath = null;
                }
                if (zipPath == null) {
                    return srg;
                }
                zip = new ZipFile(zipPath.toFile());
                ZipEntry entry = zip.getEntry("config/joined.tsrg");
                reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
            }
            try (BufferedReader r = reader) {
                String line;
                String cls = null;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("tsrg2") || line.startsWith("\t\t")) {
                        continue;
                    }
                    if (!line.startsWith("\t")) {
                        cls = line.split(" ")[0];
                        continue;
                    }
                    String[] p = line.trim().split(" ");
                    String official = null;
                    String srgName;
                    if (p.length == 3) { // field: obf srg id
                        srgName = p[1];
                        Map<String, String> f = fields.get(cls);
                        official = f == null ? null : f.get(p[0]);
                    } else if (p.length == 4) { // method: obf desc srg id
                        srgName = p[2];
                        Map<String, String> m = methods.get(cls);
                        official = m == null ? null : m.get(p[0] + p[1]);
                    } else {
                        continue;
                    }
                    if (official != null && (srgName.startsWith("m_") || srgName.startsWith("f_"))) {
                        srg.putIfAbsent(srgName, official);
                    }
                }
            } finally {
                if (zip != null) {
                    zip.close();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return srg;
    }

    // ---- bytecode scans ------------------------------------------------------------------------------------

    private byte[] classBytes(ZipFile zip, String officialClass) throws IOException {
        String obf = classOfficialToObf.get(officialClass);
        if (obf == null) {
            return null;
        }
        ZipEntry e = zip.getEntry(obf.replace('.', '/') + ".class");
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private String officialField(String obfOwner, String obfField) {
        Map<String, String> f = fields.get(obfOwner.replace('/', '.'));
        return f == null ? null : f.get(obfField);
    }

    /** One static initialiser "segment": everything between two PUTSTATICs of the class itself. */
    private static final class Segment {
        String firstString;
        final List<String> strings = new ArrayList<>();
        String firstStaticOwner;
        String firstStaticField;
        final List<Integer> ints = new ArrayList<>();
        final List<String> staticRefs = new ArrayList<>(); // owner#field (obf)
        final List<String> calls = new ArrayList<>(); // owner#name (obf)
    }

    private interface SegmentSink {
        void accept(String obfField, Segment seg);
    }

    private static void scanClinit(byte[] bytes, String ownerInternal, SegmentSink sink) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!name.equals("<clinit>")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    Segment seg = new Segment();

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            seg.strings.add(s);
                        }
                        if (value instanceof String s && seg.firstString == null) {
                            seg.firstString = s;
                        } else if (value instanceof Integer i) {
                            seg.ints.add(i);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                            seg.ints.add(opcode - Opcodes.ICONST_0);
                        }
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                            seg.ints.add(operand);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        seg.calls.add(owner + "#" + name);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.PUTSTATIC && owner.equals(ownerInternal)) {
                            sink.accept(name, seg);
                            seg = new Segment();
                        } else if (opcode == Opcodes.GETSTATIC) {
                            if (seg.firstStaticOwner == null) {
                                seg.firstStaticOwner = owner;
                                seg.firstStaticField = name;
                            }
                            seg.staticRefs.add(owner + "#" + name);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private void scanHolder(ZipFile zip, String officialClass, String kind, Map<String, String> out)
            throws IOException {
        byte[] bytes = classBytes(zip, officialClass);
        if (bytes == null) {
            return;
        }
        String obfOwner = classOfficialToObf.get(officialClass);
        String blocksObf = classOfficialToObf.get("net.minecraft.world.level.block.Blocks");
        Map<String, String> blockIds = new HashMap<>();
        for (Map.Entry<String, String> e : out.entrySet()) {
            if (e.getKey().startsWith("net.minecraft.world.level.block.Blocks#")) {
                blockIds.put(e.getKey(), e.getValue());
            }
        }
        scanClinit(bytes, obfOwner.replace('.', '/'), (obfField, seg) -> {
            String field = officialField(obfOwner, obfField);
            if (field == null) {
                return;
            }
            String id = null;
            if (seg.firstString != null) {
                String s = seg.firstString;
                id = s.contains(":") ? s : "minecraft:" + s;
            } else if (kind.equals("item") && blocksObf != null
                    && blocksObf.replace('.', '/').equals(seg.firstStaticOwner)) {
                String blockField = officialField(blocksObf, seg.firstStaticField);
                id = blockIds.get("net.minecraft.world.level.block.Blocks#" + blockField);
            }
            if (id != null && id.matches("^[a-z0-9_.-]+:[a-z0-9_./-]+$")) {
                out.put(officialClass + "#" + field, id);
            }
        });
    }

    private void scanStateProperties(ZipFile zip, Map<String, VanillaIndex.StateProp> out) throws IOException {
        String cls = "net.minecraft.world.level.block.state.properties.BlockStateProperties";
        byte[] bytes = classBytes(zip, cls);
        if (bytes == null) {
            return;
        }
        String obfOwner = classOfficialToObf.get(cls);
        String boolObf = obfInternal("net.minecraft.world.level.block.state.properties.BooleanProperty");
        String intObf = obfInternal("net.minecraft.world.level.block.state.properties.IntegerProperty");
        String dirObf = obfInternal("net.minecraft.world.level.block.state.properties.DirectionProperty");
        String planeObf = obfInternal("net.minecraft.core.Direction$Plane");
        String directionObf = obfInternal("net.minecraft.core.Direction");
        scanClinit(bytes, obfOwner.replace('.', '/'), (obfField, seg) -> {
            String field = officialField(obfOwner, obfField);
            if (field == null || seg.firstString == null || seg.calls.isEmpty()) {
                return;
            }
            String call = seg.calls.get(seg.calls.size() - 1);
            String owner = call.substring(0, call.indexOf('#'));
            if (owner.equals(boolObf)) {
                out.put(field, new VanillaIndex.StateProp(seg.firstString, "boolean", List.of("true", "false")));
            } else if (owner.equals(intObf) && seg.ints.size() >= 2) {
                int min = seg.ints.get(seg.ints.size() - 2);
                int max = seg.ints.get(seg.ints.size() - 1);
                List<String> values = new ArrayList<>();
                for (int i = min; i <= max; i++) {
                    values.add(Integer.toString(i));
                }
                out.put(field, new VanillaIndex.StateProp(seg.firstString, "integer", values));
            } else if (owner.equals(dirObf)) {
                List<String> values = new ArrayList<>();
                boolean plane = false;
                for (String ref : seg.staticRefs) {
                    String o = ref.substring(0, ref.indexOf('#'));
                    String f = officialField(o, ref.substring(ref.indexOf('#') + 1));
                    if (o.equals(planeObf) && "HORIZONTAL".equals(f)) {
                        plane = true;
                    } else if (o.equals(directionObf) && f != null) {
                        values.add(f.toLowerCase(java.util.Locale.ROOT));
                    }
                }
                if (plane && values.isEmpty()) {
                    // Direction.Plane.HORIZONTAL iterates NORTH, EAST, SOUTH, WEST.
                    values = List.of("north", "east", "south", "west");
                }
                if (!values.isEmpty()) {
                    out.put(field, new VanillaIndex.StateProp(seg.firstString, "direction", values));
                }
            }
        });
    }

    /**
     * Enum constants of a vanilla enum: {@code new E("CONST", ordinal, ..., "serialized", ...)} — the constant name is
     * the first string, the serialized name the second.
     */
    private void scanEnum(ZipFile zip, String cls, Map<String, List<String>> out) throws IOException {
        byte[] bytes = classBytes(zip, cls);
        if (bytes == null) {
            return;
        }
        String obfOwner = classOfficialToObf.get(cls);
        List<String> constants = new ArrayList<>();
        scanClinit(bytes, obfOwner.replace('.', '/'), (obfField, seg) -> {
            String field = officialField(obfOwner, obfField);
            if (field != null && seg.strings.size() >= 2 && field.equals(seg.strings.get(0))) {
                // ints: ordinal first, then the first int constructor argument (DyeColor id)
                constants.add(field + "=" + seg.strings.get(1) + (seg.ints.size() >= 2 ? "#" + seg.ints.get(1) : ""));
            }
        });
        if (!constants.isEmpty()) {
            out.put(cls, constants);
        }
    }

    private String obfInternal(String official) {
        String o = classOfficialToObf.get(official);
        return o == null ? null : o.replace('.', '/');
    }

    private void scanScreenDefaults(ZipFile zip, Map<String, Integer> out) throws IOException {
        String cls = "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen";
        byte[] bytes = classBytes(zip, cls);
        if (bytes == null) {
            return;
        }
        String obfOwner = classOfficialToObf.get(cls);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!name.equals("<init>")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    Integer last;

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        last = operand;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String d) {
                        if (opcode == Opcodes.PUTFIELD && last != null) {
                            String f = officialField(obfOwner, fname);
                            if ("imageWidth".equals(f) || "imageHeight".equals(f)) {
                                out.put(cls + "#" + f, last);
                            }
                        }
                        last = null;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void scanForgeTags(ZipFile zip, String internal, Map<String, String> out) throws IOException {
        ZipEntry e = zip.getEntry(internal + ".class");
        if (e == null) {
            return;
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(e)) {
            bytes = in.readAllBytes();
        }
        String official = internal.replace('/', '.');
        scanClinit(bytes, internal, (field, seg) -> {
            if (seg.firstString == null || seg.calls.isEmpty()) {
                return;
            }
            String call = seg.calls.get(seg.calls.size() - 1);
            String name = call.substring(call.indexOf('#') + 1);
            String id;
            if (name.equals("tag")) {
                id = "forge:" + seg.firstString;
            } else if (name.equals("neoforgeTag") || name.equals("forgeTag")) {
                id = "forge:" + seg.firstString;
            } else {
                return; // other factories (vanilla tags etc.) are not decoded
            }
            if (id.matches("^[a-z0-9_.-]+:[a-z0-9_./-]+$")) {
                out.put(official + "#" + field, id);
            }
        });
    }
}
