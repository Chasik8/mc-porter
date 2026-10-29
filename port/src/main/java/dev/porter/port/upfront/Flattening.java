package dev.porter.port.upfront;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Registry-constant table: {@code Items.X}, {@code Blocks.X}, {@code SoundEvents.X}, ... of 1.19.2 to a 1.7.10
 * expression. Everything is derived at run time from the user's own files: the flattening strings in the 1.19.2 client
 * jar (found by content, never by obfuscated name), the registry holder classes of that jar (field names through the
 * tiny mappings) and the registration code of the 1.7.10 MCP jar. Nothing of it is shipped.
 */
public final class Flattening {
    public static final String HEADER = "registry\tid1192\tfield1192\ttargetKind\ttargetExpr\tmeta\tconf\tsource";

    /** Registry holder classes of 1.19.2 (mojmap, slash form) by registry label; Blocks first (Items refer to it). */
    static final Map<String, String> HOLDERS = new LinkedHashMap<>();

    static {
        HOLDERS.put("Blocks", "net/minecraft/world/level/block/Blocks");
        HOLDERS.put("Items", "net/minecraft/world/item/Items");
        HOLDERS.put("SoundEvents", "net/minecraft/sounds/SoundEvents");
        HOLDERS.put("Enchantments", "net/minecraft/world/item/enchantment/Enchantments");
        HOLDERS.put("MobEffects", "net/minecraft/world/effect/MobEffects");
        HOLDERS.put("Fluids", "net/minecraft/world/level/material/Fluids");
        HOLDERS.put("EntityType", "net/minecraft/world/entity/EntityType");
        HOLDERS.put("BlockEntityType", "net/minecraft/world/level/block/entity/BlockEntityType");
    }

    /** 1.7.10 names of the potion effects whose 1.19.2 id differs (MCP 1.7.10 vocabulary, not Mojang data). */
    static final Map<String, String> POTION_ALIAS = Map.ofEntries(Map.entry("speed", "moveSpeed"),
            Map.entry("slowness", "moveSlowdown"), Map.entry("haste", "digSpeed"), Map.entry("mining_fatigue", "digSlowdown"),
            Map.entry("strength", "damageBoost"), Map.entry("instant_health", "heal"), Map.entry("instant_damage", "harm"),
            Map.entry("jump_boost", "jump"), Map.entry("nausea", "confusion"));

    private static final Pattern FLAT_KEY = Pattern.compile("minecraft:[a-z0-9_]+\\.\\d+");
    private static final Pattern PLAIN = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Pattern STATE = Pattern.compile("\\{Name:'(minecraft:[a-z0-9_]+)'.*");
    private static final Pattern ID = Pattern.compile("[a-z0-9_./]+");

    private Flattening() {
    }

    // ---------------------------------------------------------------- bytecode events

    /** One instruction of interest: I int constant, S string constant, O other constant, C call, P putstatic, G getstatic. */
    record Ev(char t, String a, String b, String c, int i) {
    }

    static void methods(byte[] cls, BiConsumer<String, List<Ev>> sink) {
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                List<Ev> evs = new ArrayList<>();
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int op) {
                        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                            evs.add(new Ev('I', null, null, null, op - Opcodes.ICONST_0));
                        }
                    }

                    @Override
                    public void visitIntInsn(int op, int v) {
                        if (op != Opcodes.NEWARRAY) {
                            evs.add(new Ev('I', null, null, null, v));
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        if (v instanceof String s) {
                            evs.add(new Ev('S', s, null, null, 0));
                        } else if (v instanceof Integer n) {
                            evs.add(new Ev('I', null, null, null, n));
                        } else {
                            evs.add(new Ev('O', null, null, null, 0));
                        }
                    }

                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        evs.add(new Ev('C', owner, n, d, 0));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) {
                        evs.add(new Ev('C', "indy", n, d, 0));
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        if (op == Opcodes.PUTSTATIC) {
                            evs.add(new Ev('P', owner, n, d, 0));
                        } else if (op == Opcodes.GETSTATIC) {
                            evs.add(new Ev('G', owner, n, d, 0));
                        }
                    }

                    @Override
                    public void visitEnd() {
                        sink.accept(name, evs);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Public static field names of a class. */
    static List<String> staticFields(byte[] cls) {
        List<String> out = new ArrayList<>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
                if ((acc & Opcodes.ACC_STATIC) != 0 && (acc & Opcodes.ACC_PUBLIC) != 0) {
                    out.add(name);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return out;
    }

    static byte[] entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream in = z.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    // ---------------------------------------------------------------- 1.19.2 flattening data

    /** The flattening strings of the client jar. */
    public static final class Tables {
        /** {@code minecraft:stone.1 -> minecraft:granite} (item flattening). */
        public final Map<String, String> flatten = new TreeMap<>();
        /** {@code minecraft:sign -> minecraft:oak_sign} (rename fixes, later versions). */
        public final Map<String, String> renames = new TreeMap<>();
        /** Block state id (blockId << 4 | meta) -> new state name. */
        public final Map<Integer, String> states = new TreeMap<>();
        /** State ids whose new state carries Properties. */
        public final Set<Integer> stateProps = new HashSet<>();
        public int renameConflicts;
        public int flattenClasses;
        public int stateClasses;

        String closure(String id) {
            String cur = id;
            for (int i = 0; i < 20 && renames.containsKey(cur); i++) {
                cur = renames.get(cur);
            }
            return cur;
        }
    }

    public static Tables readTables(Path clientJar) {
        Tables t = new Tables();
        try (ZipFile z = new ZipFile(clientJar.toFile())) {
            for (var en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] b = entry(z, e.getName());
                if (!contains(b, "minecraft:")) {
                    continue;
                }
                int[] counts = new int[2];
                methods(b, (m, evs) -> {
                    scanPairs(evs, t, counts);
                    counts[1] += scanStates(evs, t);
                });
                if (counts[0] > 0) {
                    t.flattenClasses++;
                }
                if (counts[1] > 0) {
                    t.stateClasses++;
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return t;
    }

    private static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Runs of string constants before a call are key/value pairs (Map.put, ImmutableMap.of, builder.put). */
    static void scanPairs(List<Ev> evs, Tables t, int[] counts) {
        List<String> run = new ArrayList<>();
        for (Ev e : evs) {
            if (e.t == 'S') {
                run.add(e.a);
                continue;
            }
            if (e.t == 'C' && run.size() >= 2 && run.size() % 2 == 0) {
                for (int i = 0; i < run.size(); i += 2) {
                    String k = run.get(i);
                    String v = run.get(i + 1);
                    if (FLAT_KEY.matcher(k).matches() && PLAIN.matcher(v).matches()) {
                        t.flatten.put(k, v);
                        counts[0]++;
                    } else if (PLAIN.matcher(k).matches() && PLAIN.matcher(v).matches() && !k.equals(v)) {
                        String old = t.renames.put(k, v);
                        if (old != null && !old.equals(v)) {
                            t.renameConflicts++;
                        }
                    }
                }
            }
            run.clear(); // array initialisers (int constants between strings) are not key/value pairs
        }
    }

    /** {@code register(id, "{Name:'minecraft:x'...}", old...)}: an int constant right before the first state string. */
    static int scanStates(List<Ev> evs, Tables t) {
        int n = 0;
        boolean open = false;
        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            if (e.t == 'C') {
                open = false;
            } else if (e.t == 'S' && !open && i > 0 && evs.get(i - 1).t == 'I') {
                Matcher m = STATE.matcher(e.a);
                if (m.matches()) {
                    int id = evs.get(i - 1).i;
                    open = true;
                    t.states.put(id, m.group(1));
                    if (e.a.contains("Properties")) {
                        t.stateProps.add(id);
                    }
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 1.7.10 target

    /** The 1.7.10 registries as the MCP jar registers them. */
    public static final class Target {
        public final Map<Integer, String> blockIds = new TreeMap<>();
        public final Map<String, Integer> itemNames = new TreeMap<>();
        /** registry name -> field of net.minecraft.init.Blocks / Items. */
        public final Map<String, String> blocksField = new TreeMap<>();
        public final Map<String, String> itemsField = new TreeMap<>();
        public final Set<String> enchantFields = new HashSet<>();
        public final Set<String> potionFields = new HashSet<>();
        public final Set<String> fluidFields = new HashSet<>();
    }

    public static Target readTarget(Path targetJar) {
        Target tg = new Target();
        try (ZipFile z = new ZipFile(targetJar.toFile())) {
            byte[] blk = entry(z, "net/minecraft/block/Block.class");
            if (blk != null) {
                methods(blk, (m, evs) -> idNamePairs(evs, tg.blockIds::put));
            }
            byte[] itm = entry(z, "net/minecraft/item/Item.class");
            if (itm != null) {
                methods(itm, (m, evs) -> idNamePairs(evs, (id, n) -> tg.itemNames.put(n, id)));
            }
            initFields(z, "net/minecraft/init/Blocks", tg.blocksField);
            initFields(z, "net/minecraft/init/Items", tg.itemsField);
            fields(z, "net/minecraft/enchantment/Enchantment", tg.enchantFields);
            fields(z, "net/minecraft/potion/Potion", tg.potionFields);
            fields(z, "net/minecraftforge/fluids/FluidRegistry", tg.fluidFields);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return tg;
    }

    private static void fields(ZipFile z, String cls, Set<String> out) throws IOException {
        byte[] b = entry(z, cls + ".class");
        if (b != null) {
            out.addAll(staticFields(b));
        }
    }

    /** {@code registry.addObject(int, String, ...)}: the int constant and string constant that start the call. */
    private static void idNamePairs(List<Ev> evs, BiConsumer<Integer, String> sink) {
        Integer id = null;
        String name = null;
        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            if (e.t == 'S' && i > 0 && evs.get(i - 1).t == 'I' && name == null) {
                id = evs.get(i - 1).i;
                name = e.a;
            } else if (e.t == 'C' && e.c.startsWith("(ILjava/lang/String;")) {
                if (name != null) {
                    sink.accept(id, name);
                }
                id = null;
                name = null;
            }
        }
    }

    private static void initFields(ZipFile z, String cls, Map<String, String> out) throws IOException {
        byte[] b = entry(z, cls + ".class");
        if (b == null) {
            return;
        }
        methods(b, (m, evs) -> {
            if (!m.equals("<clinit>")) {
                return;
            }
            String last = null;
            for (Ev e : evs) {
                if (e.t == 'S') {
                    last = e.a;
                } else if (e.t == 'P' && e.a.equals(cls) && last != null) {
                    out.putIfAbsent(last, e.b);
                    last = null;
                }
            }
        });
    }

    // ---------------------------------------------------------------- 1.19.2 registry holders

    /** Tiny v2 names of the holder classes: mojmap class -> obf class and obf field -> mojmap field. */
    public static final class Names {
        final Map<String, String> classObf = new HashMap<>();
        final Map<String, Map<String, String>> fieldMoj = new HashMap<>();
    }

    public static Names readTiny(Path tiny, Set<String> wantedMojClasses) {
        Names n = new Names();
        try (BufferedReader r = Files.newBufferedReader(tiny, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            String[] h = header.split("\t");
            int obf = indexOf(h, "obf") - 3;
            int moj = indexOf(h, "mojmap") - 3;
            if (obf < 0 || moj < 0) {
                throw new IllegalArgumentException("tiny file lacks obf/mojmap namespaces: " + header);
            }
            String curObf = null;
            boolean want = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("c\t")) {
                    String[] p = line.split("\t");
                    String mojName = p[1 + moj];
                    want = wantedMojClasses.contains(mojName);
                    curObf = p[1 + obf];
                    if (want) {
                        n.classObf.put(mojName, curObf);
                        n.fieldMoj.put(curObf, new HashMap<>());
                    }
                } else if (want && line.startsWith("\tf\t")) {
                    String[] p = line.split("\t");
                    n.fieldMoj.get(curObf).put(p[3 + obf], p[3 + moj]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    /** One 1.19.2 constant: registry label, id (path part), mojmap field. */
    record Const(String registry, String id, String field) {
    }

    /** Reads the id of every field of the holder classes from their static initialisers. */
    static List<Const> readConstants(Path clientJar, Names names) {
        List<Const> out = new ArrayList<>();
        Map<String, String> blockIds = new HashMap<>();
        try (ZipFile z = new ZipFile(clientJar.toFile())) {
            String blocksObf = names.classObf.get(HOLDERS.get("Blocks"));
            for (var h : HOLDERS.entrySet()) {
                String obf = names.classObf.get(h.getValue());
                if (obf == null) {
                    continue;
                }
                byte[] b = entry(z, obf + ".class");
                if (b == null) {
                    continue;
                }
                Map<String, String> fm = names.fieldMoj.get(obf);
                Map<String, String> bm = blocksObf == null ? Map.of() : names.fieldMoj.get(blocksObf);
                methods(b, (m, evs) -> {
                    if (!m.equals("<clinit>")) {
                        return;
                    }
                    String id = null;
                    String viaBlock = null;
                    for (Ev e : evs) {
                        if (e.t == 'S' && id == null && ID.matcher(e.a).matches()) {
                            id = e.a;
                        } else if (e.t == 'G' && viaBlock == null && e.a.equals(blocksObf) && !obf.equals(blocksObf)) {
                            viaBlock = bm.get(e.b);
                        } else if (e.t == 'P' && e.a.equals(obf)) {
                            String f = fm.getOrDefault(e.b, e.b);
                            String use = id != null ? id : viaBlock != null ? blockIds.get(viaBlock) : null;
                            if (use != null) {
                                out.add(new Const(h.getKey(), use, f));
                                if (h.getKey().equals("Blocks")) {
                                    blockIds.put(f, use);
                                }
                            }
                            id = null;
                            viaBlock = null;
                        }
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // ---------------------------------------------------------------- resolution

    public record Row(String registry, String id, String field, String kind, String expr, String meta, double conf, String source) {
        String tsv() {
            return registry + "\t" + id + "\t" + field + "\t" + kind + "\t" + expr + "\t" + meta + "\t"
                    + String.format(Locale.ROOT, "%.2f", conf) + "\t" + source;
        }
    }

    /** A 1.7.10 registry name with meta (-1 none) found for a final 1.19.2 id. */
    record Cand(String name, int meta, double conf, String source) {
    }

    private static void put(Map<String, Cand> m, String id, Cand c) {
        Cand old = m.get(id);
        if (old == null || c.conf > old.conf || (c.conf == old.conf && c.meta < old.meta)) {
            m.put(id, c);
        }
    }

    private static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** Final 1.19.2 id -> 1.7.10 item name + meta. */
    static Map<String, Cand> itemCandidates(Tables t, Target tg) {
        Map<String, Cand> out = new HashMap<>();
        Set<String> names = new TreeSet<>(tg.itemNames.keySet());
        names.addAll(tg.blockIds.values()); // block items are registered through registerItemBlock, without a name constant
        for (String n : names) {
            String base = "minecraft:" + n;
            String fin = t.closure(base);
            put(out, bare(fin), new Cand(n, -1, fin.equals(base) ? 0.95 : 0.75, "identity"));
        }
        for (var e : t.flatten.entrySet()) {
            String key = e.getKey();
            int dot = key.lastIndexOf('.');
            String n = bare(key.substring(0, dot));
            int meta = Integer.parseInt(key.substring(dot + 1));
            if (!tg.itemNames.containsKey(n) && !tg.blockIds.containsValue(n)) {
                continue;
            }
            String fin = t.closure(e.getValue());
            put(out, bare(fin), new Cand(n, meta, fin.equals(e.getValue()) ? 0.9 : 0.85, "flatten"));
        }
        return out;
    }

    /** Final 1.19.2 id -> 1.7.10 block name + meta. */
    static Map<String, Cand> blockCandidates(Tables t, Target tg, Map<String, Cand> items) {
        Map<String, Cand> out = new HashMap<>();
        for (String n : tg.blocksField.keySet()) {
            String base = "minecraft:" + n;
            String fin = t.closure(base);
            put(out, bare(fin), new Cand(n, -1, fin.equals(base) ? 0.9 : 0.65, "identity"));
        }
        for (var c : items.entrySet()) {
            if (c.getValue().source.equals("flatten") && tg.blocksField.containsKey(c.getValue().name)) {
                put(out, c.getKey(), new Cand(c.getValue().name, c.getValue().meta, 0.85, "flatten-item"));
            }
        }
        for (var s : t.states.entrySet()) {
            String name = tg.blockIds.get(s.getKey() >> 4);
            if (name == null || !tg.blocksField.containsKey(name)) {
                continue;
            }
            String fin = t.closure(s.getValue());
            boolean props = t.stateProps.contains(s.getKey());
            double conf = (props ? 0.7 : 0.9) - (fin.equals(s.getValue()) ? 0 : 0.05);
            Cand c = new Cand(name, s.getKey() & 15, conf, props ? "state+props" : "state");
            Cand old = out.get(bare(fin));
            if (old == null || c.conf > old.conf || (c.conf == old.conf && old.source.startsWith("state") && c.meta < old.meta)) {
                out.put(bare(fin), c);
            }
        }
        return out;
    }

    static String norm(String s) {
        return s.replace("_", "").toLowerCase(Locale.ROOT);
    }

    static String metaText(int meta) {
        return meta > 0 ? Integer.toString(meta) : "";
    }

    public static List<Row> resolve(Tables t, Target tg, List<Const> consts) {
        Map<String, Cand> items = itemCandidates(t, tg);
        Map<String, Cand> blocks = blockCandidates(t, tg, items);
        List<Row> rows = new ArrayList<>();
        for (Const c : consts) {
            rows.add(switch (c.registry) {
                case "Blocks" -> blockRow(c, blocks, tg);
                case "Items" -> itemRow(c, items, blocks, tg);
                case "Enchantments" -> nameRow(c, tg.enchantFields, "Enchantment.", Map.of(), "ENCHANTMENT");
                case "MobEffects" -> nameRow(c, tg.potionFields, "Potion.", POTION_ALIAS, "POTION");
                case "Fluids" -> fluidRow(c, tg);
                case "SoundEvents" -> new Row(c.registry, c.id, c.field, "TODO", "", "", 0, "sound names are not in any jar");
                default -> new Row(c.registry, c.id, c.field, "TODO", "", "", 0, "entity/tile names not derived");
            });
        }
        return rows;
    }

    private static Row blockRow(Const c, Map<String, Cand> blocks, Target tg) {
        Cand b = blocks.get(c.id);
        if (b == null) {
            return new Row(c.registry, c.id, c.field, "ABSENT", "", "", 0, "no 1.7.10 block for " + c.id);
        }
        return new Row(c.registry, c.id, c.field, "BLOCK", "Blocks." + tg.blocksField.get(b.name), metaText(b.meta), b.conf, b.source);
    }

    private static Row itemRow(Const c, Map<String, Cand> items, Map<String, Cand> blocks, Target tg) {
        Cand i = items.get(c.id);
        if (i != null) {
            String f = tg.itemsField.get(i.name);
            if (f != null) {
                return new Row(c.registry, c.id, c.field, "ITEM", "Items." + f, metaText(i.meta), i.conf, i.source);
            }
            f = tg.blocksField.get(i.name);
            if (f != null) {
                return new Row(c.registry, c.id, c.field, "BLOCK_ITEM", "Item.getItemFromBlock(Blocks." + f + ")", metaText(i.meta),
                        i.conf, i.source);
            }
        }
        Cand b = blocks.get(c.id);
        if (b != null) {
            return new Row(c.registry, c.id, c.field, "BLOCK_ITEM", "Item.getItemFromBlock(Blocks." + tg.blocksField.get(b.name) + ")",
                    metaText(b.meta), Math.min(b.conf, 0.7), "block-item/" + b.source);
        }
        return new Row(c.registry, c.id, c.field, "ABSENT", "", "", 0, "no 1.7.10 item for " + c.id);
    }

    private static Row nameRow(Const c, Set<String> fields, String prefix, Map<String, String> alias, String kind) {
        String a = alias.get(c.id);
        if (a != null && fields.contains(a)) {
            return new Row(c.registry, c.id, c.field, kind, prefix + a, "", 0.8, "alias");
        }
        for (String f : new TreeSet<>(fields)) {
            if (norm(f).equals(norm(c.id))) {
                return new Row(c.registry, c.id, c.field, kind, prefix + f, "", 0.7, "name");
            }
        }
        return new Row(c.registry, c.id, c.field, "ABSENT", "", "", 0, "no 1.7.10 field for " + c.id);
    }

    private static Row fluidRow(Const c, Target tg) {
        if (c.id.equals("empty")) {
            return new Row(c.registry, c.id, c.field, "NULL", "null", "", 0.6, "empty fluid");
        }
        String f = c.id.toUpperCase(Locale.ROOT);
        if ((c.id.equals("water") || c.id.equals("lava")) && tg.fluidFields.contains(f)) {
            return new Row(c.registry, c.id, c.field, "FLUID", "FluidRegistry." + f, "", 0.9, "name");
        }
        return new Row(c.registry, c.id, c.field, "ABSENT", "", "", 0, "no 1.7.10 fluid object for " + c.id);
    }

    // ---------------------------------------------------------------- driver

    public static List<Row> derive(Path clientJar, Path tiny, Path targetJar, StringBuilder stats) {
        Tables t = readTables(clientJar);
        Target tg = readTarget(targetJar);
        Names names = readTiny(tiny, new HashSet<>(HOLDERS.values()));
        List<Const> consts = readConstants(clientJar, names);
        stats.append("flatten pairs ").append(t.flatten.size()).append(" in ").append(t.flattenClasses).append(" class(es); renames ")
                .append(t.renames.size()).append(" (conflicts ").append(t.renameConflicts).append("); states ").append(t.states.size())
                .append(" in ").append(t.stateClasses).append(" class(es); 1.7.10 blocks ").append(tg.blockIds.size()).append(", items ")
                .append(tg.itemNames.size()).append("; 1.19.2 constants ").append(consts.size()).append('\n');
        return resolve(t, tg, consts);
    }

    public static void write(List<Row> rows, Path out) {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        rows.forEach(r -> lines.add(r.tsv()));
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha(Path... files) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (Path f : files) {
                sb.append(java.util.HexFormat.of().formatHex(md.digest(Files.readAllBytes(f)))).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rows by outcome: {@code registry:KIND -> count}. */
    public static Map<String, Integer> summary(List<Row> rows) {
        Map<String, Integer> m = new TreeMap<>();
        for (Row r : rows) {
            m.merge(r.registry + ":" + r.kind, 1, Integer::sum);
        }
        return m;
    }
}
