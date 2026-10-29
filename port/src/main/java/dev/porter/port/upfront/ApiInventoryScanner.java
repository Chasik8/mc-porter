package dev.porter.port.upfront;

import dev.porter.port.Text;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The 1.19.2 API a mod references, as distinct symbols (spec 2.2). Bytecode first: every class of the mod jar (the
 * selected ones) is read for class references, field/method references (constant pool, so lambda bodies and
 * invokedynamic handles are included), descriptor type references and the methods it declares that override a
 * platform method. A member reference is attributed to the class that declares it, found by walking the platform
 * hierarchy up from the referenced owner; references through a mod subclass are resolved to the platform declaring
 * class or dropped. Sources add the coverage universe (platform imports and SRG ids, the same text rules as
 * {@code porter coverage}). Anything the jar itself defines is excluded.
 */
public final class ApiInventoryScanner {
    public static final List<String> PLATFORM = List.of("net/minecraft/", "net/minecraftforge/", "com/mojang/");
    private static final Pattern SRG = Pattern.compile("[mf]_\\d+_");
    private static final Pattern SRG_IN_TEXT = Pattern.compile("\\b([mf]_\\d+_)\\b");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern DESC_TYPE = Pattern.compile("L([\\w/$]+);");

    /** Scan settings. {@code platform} entries may be dotted or slashed prefixes. */
    public record Options(Path jar, Path src, List<Path> classLists, List<String> platform, boolean overrides) {
    }

    /** One distinct symbol; {@code kind} is {@code type}, {@code method} or {@code field}. */
    public static final class Sym {
        public final String kind;
        public final String owner;
        public final String name;
        public final String desc;
        public final Set<String> origin = new TreeSet<>();
        public final Set<String> refClasses = new TreeSet<>();
        public int sourceUses;
        public int access = -1;
        boolean srgNamed;
        boolean forgeRef;

        Sym(String kind, String owner, String name, String desc) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        public String key() {
            return switch (kind) {
                case "type" -> "type:" + owner;
                case "method" -> "method:" + owner + "#" + name + desc;
                default -> "field:" + owner + "#" + name + ":" + desc;
            };
        }

        public int uses() {
            return origin.contains("bytecode") ? refClasses.size() : Math.max(sourceUses, origin.contains("override") ? refClasses.size() : 0);
        }

        /** srg | forge | ctor | lib | mcNonSrg (members only). */
        public String category() {
            if (srgNamed) {
                return "srg";
            }
            if (forgeRef || owner.startsWith("net.minecraftforge.")) {
                return "forge";
            }
            if (name.equals("<init>")) {
                return "ctor";
            }
            if (owner.startsWith("com.mojang.")) {
                return "lib";
            }
            return "mcNonSrg";
        }
    }

    public record Result(List<Sym> symbols, int jarClasses, int scannedClasses, int sourceFiles) {
        public int types() {
            return (int) symbols.stream().filter(s -> s.kind.equals("type")).count();
        }

        public int members() {
            return symbols.size() - types();
        }

        public Map<String, Integer> byOrigin() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String k : List.of("srg", "forge", "ctor", "mcNonSrg", "lib")) {
                m.put(k, 0);
            }
            for (Sym s : symbols) {
                if (!s.kind.equals("type")) {
                    m.merge(s.category(), 1, Integer::sum);
                }
            }
            return m;
        }
    }

    private record ModClass(String name, String superName, List<String> interfaces, Set<String> declared) {
    }

    private final PlatformIndex index;
    private final Options opt;
    private final List<String> prefixes = new ArrayList<>();
    private final Map<String, ModClass> mod = new HashMap<>();
    private final Map<String, Sym> syms = new TreeMap<>();

    public ApiInventoryScanner(PlatformIndex index, Options opt) {
        this.index = index;
        this.opt = opt;
        prefixes.addAll(PLATFORM);
        for (String p : opt.platform()) {
            prefixes.add(p.replace('.', '/'));
        }
    }

    private boolean platform(String internal) {
        for (String p : prefixes) {
            if (internal.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public Result scan() throws IOException {
        Set<String> wanted = null;
        if (!opt.classLists().isEmpty()) {
            wanted = new HashSet<>();
            for (Path l : opt.classLists()) {
                for (String c : Text.words(l)) {
                    wanted.add(c.replace('.', '/'));
                }
            }
        }
        // pass 1: every class of the jar (hierarchy and declared members of the mod's own classes)
        Map<String, byte[]> selected = new TreeMap<>();
        try (ZipFile z = new ZipFile(opt.jar().toFile())) {
            for (var en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] b;
                try (InputStream in = z.getInputStream(e)) {
                    b = in.readAllBytes();
                }
                ClassReader r = new ClassReader(b);
                Set<String> decl = new HashSet<>();
                r.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                        decl.add(n + " " + d);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                        decl.add(n + " " + d);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                List<String> itf = new ArrayList<>();
                for (String i : r.getInterfaces()) {
                    itf.add(PlatformIndex.dotted(i));
                }
                String name = PlatformIndex.dotted(r.getClassName());
                mod.put(name, new ModClass(name, r.getSuperName() == null ? null : PlatformIndex.dotted(r.getSuperName()), itf, decl));
                String top = r.getClassName();
                int d = top.indexOf('$');
                if (d > 0) {
                    top = top.substring(0, d);
                }
                if (wanted == null || wanted.contains(top)) {
                    selected.put(r.getClassName(), b);
                }
            }
        }
        for (Map.Entry<String, byte[]> e : selected.entrySet()) {
            scanClass(PlatformIndex.dotted(e.getKey()), e.getValue());
        }
        int files = 0;
        if (opt.src() != null) {
            files = scanSources();
        }
        List<Sym> out = new ArrayList<>(syms.values());
        return new Result(out, mod.size(), selected.size(), files);
    }

    // ---------------------------------------------------------------------------------------- bytecode

    private static int u2(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    /** Element class of a constant-pool class name, or null for primitives. */
    private static String element(String n) {
        int i = 0;
        while (i < n.length() && n.charAt(i) == '[') {
            i++;
        }
        n = n.substring(i);
        if (i > 0) {
            if (n.startsWith("L") && n.endsWith(";")) {
                return n.substring(1, n.length() - 1);
            }
            return null;
        }
        return n;
    }

    private void scanClass(String self, byte[] b) {
        int n = u2(b, 8);
        int p = 10;
        String[] utf = new String[n];
        int[][] ref = new int[n][];
        int[] tag = new int[n];
        for (int k = 1; k < n; k++) {
            int t = b[p] & 0xff;
            tag[k] = t;
            switch (t) {
                case 1 -> {
                    int len = u2(b, p + 1);
                    utf[k] = new String(b, p + 3, len, StandardCharsets.UTF_8);
                    p += 3 + len;
                }
                case 3, 4 -> p += 5;
                case 5, 6 -> {
                    p += 9;
                    k++;
                }
                case 7, 8, 16, 19, 20 -> {
                    ref[k] = new int[] {u2(b, p + 1)};
                    p += 3;
                }
                case 9, 10, 11, 12, 17, 18 -> {
                    ref[k] = new int[] {u2(b, p + 1), u2(b, p + 3)};
                    p += 5;
                }
                case 15 -> p += 4;
                default -> throw new IllegalArgumentException("bad constant pool tag " + t + " in " + self);
            }
        }
        for (int k = 1; k < n; k++) {
            if (tag[k] == 7) {
                String el = element(utf[ref[k][0]]);
                if (el != null) {
                    type(el, self);
                }
            } else if (tag[k] == 1) {
                if (utf[k].indexOf(';') > 0) {
                    Matcher m = DESC_TYPE.matcher(utf[k]);
                    while (m.find()) {
                        type(m.group(1), self);
                    }
                }
            } else if (tag[k] == 9 || tag[k] == 10 || tag[k] == 11) {
                String rawOwner = utf[ref[ref[k][0]][0]];
                int[] nt = ref[ref[k][1]];
                member(rawOwner, utf[nt[0]], utf[nt[1]], tag[k] == 9, self);
            }
        }
        if (opt.overrides()) {
            overrides(self, b);
        }
    }

    private void type(String internal, String self) {
        if (!platform(internal)) {
            return;
        }
        String cls = PlatformIndex.dotted(internal);
        if (mod.containsKey(cls)) {
            return;
        }
        Sym s = syms.computeIfAbsent("type:" + cls, k -> new Sym("type", cls, "", ""));
        s.origin.add("bytecode");
        s.refClasses.add(self);
        s.access = index.classAccess(cls);
    }

    private void member(String rawOwner, String name, String desc, boolean field, String self) {
        String el = element(rawOwner);
        if (el == null) {
            return;
        }
        if (rawOwner.startsWith("[") && name.equals("clone")) {
            return;
        }
        String owner = PlatformIndex.dotted(el);
        PlatformIndex.Decl d = null;
        boolean forge = owner.startsWith("net.minecraftforge.");
        if (mod.containsKey(owner)) {
            d = viaMod(owner, name, desc);
            if (d == null) {
                return;
            }
        } else if (platform(el)) {
            d = index.declaring(owner, name, desc);
        } else {
            return;
        }
        String declIn;
        String moj;
        int access = -1;
        boolean srgName = SRG.matcher(name).matches();
        if (d != null) {
            declIn = d.owner();
            moj = d.member().name();
            access = d.member().access();
        } else {
            declIn = owner;
            moj = name;
            String[] t = srgName ? index.srg(name) : null;
            if (t != null) {
                declIn = t[0];
                moj = t[1];
            }
        }
        boolean isField = field;
        add(isField ? "field" : "method", declIn, moj, desc, access, "bytecode", self, srgName, forge);
    }

    private void add(String kind, String declIn, String name, String desc, int access, String origin, String ref, boolean srgName, boolean forge) {
        Sym s = new Sym(kind, declIn, name, desc);
        Sym old = syms.putIfAbsent(s.key(), s);
        if (old != null) {
            s = old;
        }
        s.origin.add(origin);
        if (ref != null) {
            s.refClasses.add(ref);
        }
        if (access >= 0) {
            s.access = access;
        }
        s.srgNamed |= srgName;
        s.forgeRef |= forge;
    }

    /** A reference whose owner is a mod class: resolved through the mod's supertypes to a platform declaration. */
    private PlatformIndex.Decl viaMod(String owner, String name, String desc) {
        String k = name + " " + desc;
        ModClass m = mod.get(owner);
        if (m != null && m.declared().contains(k)) {
            return null;
        }
        for (String anc : platformAncestors(owner, true)) {
            PlatformIndex.Decl d = index.declaring(anc, name, desc);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** First platform (non-mod) supertypes reached from a mod class, super class first. */
    private Set<String> platformAncestors(String cls, boolean skipSelfDeclared) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        walkMod(cls, out, seen);
        return out;
    }

    private void walkMod(String cls, Set<String> out, Set<String> seen) {
        if (!seen.add(cls)) {
            return;
        }
        ModClass m = mod.get(cls);
        if (m == null) {
            if (cls != null && !cls.startsWith("java.")) {
                out.add(cls);
            }
            return;
        }
        List<String> next = new ArrayList<>();
        if (m.superName() != null) {
            next.add(m.superName());
        }
        next.addAll(m.interfaces());
        for (String s : next) {
            walkMod(s, out, seen);
        }
    }

    private void overrides(String self, byte[] b) {
        ClassReader r = new ClassReader(b);
        List<String[]> methods = new ArrayList<>();
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                boolean skip = (acc & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_BRIDGE)) != 0
                        || n.equals("<init>") || n.equals("<clinit>");
                if (!skip) {
                    methods.add(new String[] {n, d});
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> anc = platformAncestors(self, false);
        for (String[] m : methods) {
            for (String a : anc) {
                PlatformIndex.Decl d = index.declaring(a, m[0], m[1]);
                if (d != null) {
                    int acc = d.member().access();
                    if (acc >= 0 && (acc & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) {
                        break;
                    }
                    add("method", d.owner(), d.member().name(), m[1], acc, "override", self, SRG.matcher(m[0]).matches(),
                            d.owner().startsWith("net.minecraftforge."));
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------- sources

    private int scanSources() {
        List<Path> files = new ArrayList<>();
        if (opt.classLists().isEmpty()) {
            files.addAll(Text.javaFiles(opt.src()));
        } else {
            Set<Path> seen = new HashSet<>();
            for (Path l : opt.classLists()) {
                for (String c : Text.words(l)) {
                    Path f = opt.src().resolve(c.replace('.', '/') + ".java");
                    if (Files.exists(f) && seen.add(f)) {
                        files.add(f);
                    }
                }
            }
        }
        List<String> dotted = prefixes.stream().map(x -> x.replace('/', '.')).toList();
        for (Path f : files) {
            String cls = opt.src().relativize(f).toString().replace('\\', '/');
            cls = cls.substring(0, cls.length() - ".java".length()).replace('/', '.');
            String s = Text.read(f);
            Matcher m = IMPORT.matcher(s);
            while (m.find()) {
                String fq = m.group(2);
                if (m.group(1) != null) {
                    fq = fq.substring(0, Math.max(0, fq.lastIndexOf('.')));
                }
                String x = fq;
                if (dotted.stream().noneMatch(x::startsWith)) {
                    continue;
                }
                String t = nested(fq);
                Sym sym = syms.computeIfAbsent("type:" + t, k -> new Sym("type", t, "", ""));
                sym.origin.add("source");
                sym.sourceUses++;
                sym.refClasses.add(cls);
                if (sym.access < 0) {
                    sym.access = index.classAccess(t);
                }
            }
            Matcher r = SRG_IN_TEXT.matcher(s);
            while (r.find()) {
                String[] v = index.srg(r.group(1));
                if (v == null) {
                    continue;
                }
                PlatformIndex.Decl d = index.declaring(v[0], v[1], v[2]);
                String owner = d != null ? d.owner() : v[0];
                add(v[3].equals("m") ? "method" : "field", owner, v[1], v[2], d != null ? d.member().access() : -1, "source", cls, true, false);
                syms.get(keyOf(v[3], owner, v[1], v[2])).sourceUses++;
            }
        }
        return files.size();
    }

    private static String keyOf(String kind, String owner, String name, String desc) {
        return kind.equals("m") ? "method:" + owner + "#" + name + desc : "field:" + owner + "#" + name + ":" + desc;
    }

    /** An import names nested classes with dots; the key uses {@code $} (the class names the platform index knows). */
    private String nested(String fq) {
        if (index.knowsClass(fq)) {
            return fq;
        }
        String cur = fq;
        for (int i = 0; i < 4; i++) {
            int dot = cur.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            cur = cur.substring(0, dot) + "$" + cur.substring(dot + 1);
            if (index.knowsClass(cur)) {
                return cur;
            }
        }
        return fq;
    }
}
