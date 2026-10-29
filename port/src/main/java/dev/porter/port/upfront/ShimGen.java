package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.Kind;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Java 8 shim skeletons from the match table (spec 2.7 and WP7).
 *
 * <p>Per 1.19.2 owner, by what the owner is on the 1.7.10 side:
 * <ul>
 *   <li>NEW: a mirror type without a shim becomes {@code dev.porter.shim.gen.<pkg>.<Simple>}.</li>
 *   <li>FRAGMENT: a mirror type that has a shim is not touched; the missing members go to
 *       {@code fragments/<Shim>.members.java} for review.</li>
 *   <li>EXT: an owner that maps to a 1.7.10 class becomes {@code <Simple>Ext} static helpers with {@code self} first.</li>
 * </ul>
 * Rows generated: plan MIRROR, HINT and TODO, and MAP/EXPAND rows whose {@code shim} column is set. Members the shim
 * sources already have are skipped. The body is the bodies DB entry, else a delegate (MAP/EXPAND with a target),
 * else {@code throw new UnsupportedOperationException("porter:todo <name>")}.
 */
public final class ShimGen {

    /** Generated package root. */
    public static final String GEN_PACKAGE = "dev.porter.shim.gen";

    /** Everything shimgen reads. */
    public record Input(MatchTable table, Map<String, String> conceptShims, ShimIndex shims, BodiesDb bodies,
                        Map<String, Hier> hierarchy, Map<SymbolKey, Integer> access) {
    }

    /** A 1.19.2 class's super types ({@code --hierarchy}, optional). */
    public record Hier(String superName, List<String> interfaces, boolean isInterface) {
    }

    /** Result: relative path to file text, the TODO list, warnings. */
    public record Output(Map<String, String> files, List<TodoEntry> todos, List<String> warnings, int generated,
                         int skippedExisting, int withBody) {
    }

    private enum Mode { NEW, FRAGMENT, EXT }

    private record Cls(Mode mode, String owner, String simple, String genFqn, String shimFqn, String selfType) {
    }

    private static final Set<String> OBJECT_FINALS = Set.of("getClass()", "notify()", "notifyAll()", "wait()",
            "wait(long)", "wait(long,int)");

    private final Input in;
    private final Map<String, MatchRow> typeRows = new HashMap<>();
    private final Map<String, Cls> classes = new TreeMap<>();
    private final Set<String> warnings = new TreeSet<>();

    public ShimGen(Input in) {
        this.in = in;
    }

    /** Does the (dotted, source form) class have a constructor a subclass can call without arguments? Default: yes. */
    private java.util.function.Predicate<String> defaultCtor = f -> true;

    /**
     * Sets the check for platform and shim super classes. A generated class whose super class has no no-argument
     * constructor would not compile (its constructors cannot know the super arguments), so it is generated without
     * the {@code extends} (a warning says so); see {@link #defaultCtorCheck}.
     */
    public ShimGen defaultCtor(java.util.function.Predicate<String> p) {
        this.defaultCtor = p;
        return this;
    }

    /**
     * Check from the shim sources and the 1.7.10 api-index {@code target.tsv} ({@code member <class> method <init>
     * <desc> <access>} lines; null file: platform classes are assumed fine).
     */
    public static java.util.function.Predicate<String> defaultCtorCheck(ShimIndex shims, Path targetTsv) throws IOException {
        Map<String, Boolean> hasDefault = new HashMap<>();
        Set<String> hasCtor = new HashSet<>();
        if (targetTsv != null && Files.isRegularFile(targetTsv)) {
            for (String l : Files.readAllLines(targetTsv, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length >= 6 && p[0].equals("member") && p[3].equals("<init>")) {
                    String c = p[1].replace('$', '.');
                    int acc = p[5].matches("\\d+") ? Integer.parseInt(p[5]) : 1;
                    hasCtor.add(c);
                    if (p[4].startsWith("()") && (acc & 0x5) != 0) { // public or protected
                        hasDefault.put(c, true);
                    }
                }
            }
        }
        return f -> {
            ShimIndex.Info s = shims.byFqn(f);
            if (s != null) {
                boolean any = s.methods.stream().anyMatch(m -> m.startsWith("<init>("));
                return !any || s.methods.contains("<init>()");
            }
            return !hasCtor.contains(f) || hasDefault.getOrDefault(f, false);
        };
    }

    /** Is the (dotted, source form) class an interface? null: not known. */
    private java.util.function.Function<String, Boolean> interfaceKind = f -> null;

    public ShimGen interfaceKind(java.util.function.Function<String, Boolean> f) {
        this.interfaceKind = f;
        return this;
    }

    /** Interface or class from the shim sources and the {@code class <fqn> <super> <interfaces> <access>} lines of target.tsv. */
    public static java.util.function.Function<String, Boolean> interfaceKindCheck(ShimIndex shims, Path targetTsv) throws IOException {
        Map<String, Boolean> kinds = new HashMap<>();
        if (targetTsv != null && Files.isRegularFile(targetTsv)) {
            for (String l : Files.readAllLines(targetTsv, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length >= 5 && p[0].equals("class") && p[4].matches("\\d+")) {
                    kinds.put(p[1].replace('$', '.'), (Integer.parseInt(p[4]) & 0x200) != 0);
                }
            }
        }
        return f -> {
            ShimIndex.Info s = shims.byFqn(f);
            return s != null ? Boolean.valueOf(s.isInterface) : kinds.get(f);
        };
    }

    /** Kind of a type as written in the output: shim, generated class or platform class; null if unknown. */
    private Boolean kindOf(String fqn) {
        for (Cls c : classes.values()) {
            if (fqn.equals(c.genFqn())) {
                return isInterface(c);
            }
        }
        return interfaceKind.apply(fqn);
    }

    /** Generated class (dotted source form) has a no-argument constructor, or none at all (then the implicit one). */
    private boolean generatedDefaultCtor(String genFqn) {
        for (Cls c : classes.values()) {
            if (genFqn.equals(c.genFqn())) {
                boolean any = false;
                for (MatchRow r : in.table().rows()) {
                    if (r.key().kind() == dev.porter.port.upfront.model.Kind.METHOD && r.key().name().equals("<init>") && r.key().owner().equals(c.owner())) {
                        any = true;
                        if (r.key().desc().startsWith("()")) {
                            return true;
                        }
                    }
                }
                return !any;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- inputs

    /** Reads the concept shims out of concepts.yaml: dotted 1.19.2 type to shim class (concepts and explicit types). */
    @SuppressWarnings("unchecked")
    public static Map<String, String> readConceptShims(Path concepts) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (concepts == null || !Files.isRegularFile(concepts)) {
            return out;
        }
        Map<String, Object> m = Json.yaml().readValue(Files.readString(concepts, StandardCharsets.UTF_8), Map.class);
        if (m == null) {
            return out;
        }
        if (m.get("concepts") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> c && c.get("type") != null && c.get("shim") != null) {
                    out.put(c.get("type").toString().replace('/', '.'), c.get("shim").toString());
                }
            }
        }
        if (m.get("types") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> v && v.get("shim") != null) {
                    out.put(e.getKey().toString().replace('/', '.'), v.get("shim").toString());
                }
            }
        }
        return out;
    }

    /** Reads {@code class <fqn> <super> <itf,itf> <access>} lines (1.19.2 names, dotted); other lines are ignored. */
    public static Map<String, Hier> readHierarchy(Path file) throws IOException {
        Map<String, Hier> out = new HashMap<>();
        if (file == null) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.startsWith("class") && !line.startsWith("#") && line.indexOf('\t') > 0) {
                // the api-index file source-hier.tsv: class, super, interfaces, access (tab separated, interfaces may be empty)
                String[] p = line.split("\t", -1);
                if (p.length >= 4 && p[3].matches("\\d+")) {
                    List<String> itf = new ArrayList<>();
                    for (String s : p[2].split(",")) {
                        if (!s.isEmpty()) {
                            itf.add(s.replace('/', '.'));
                        }
                    }
                    out.put(p[0].replace('/', '.'), new Hier(p[1].isEmpty() ? null : p[1].replace('/', '.'), itf, (Integer.parseInt(p[3]) & 0x200) != 0));
                }
                continue;
            }
            String[] t = line.trim().split("\\s+");
            if (t.length >= 5 && t[0].equals("class")) {
                List<String> itf = new ArrayList<>();
                for (String s : t[3].split(",")) {
                    if (!s.isEmpty() && !s.equals("-")) {
                        itf.add(s.replace('/', '.'));
                    }
                }
                int access = Integer.parseInt(t[4]);
                out.put(t[1].replace('/', '.'), new Hier(t[2].equals("-") ? null : t[2].replace('/', '.'), itf, (access & 0x200) != 0));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- run

    public Output run() {
        List<MatchRow> members = new ArrayList<>();
        Set<String> owners = new TreeSet<>();
        for (MatchRow r : in.table().sortedRows()) {
            if (r.kind() == Kind.TYPE) {
                typeRows.put(r.key().owner(), r);
                if (r.plan() == Plan.MIRROR || r.plan() == Plan.HINT || r.plan() == Plan.TODO) {
                    owners.add(r.key().owner());
                }
            } else if (eligible(r)) {
                members.add(r);
                owners.add(r.key().owner());
            }
        }
        for (String o : owners) {
            classes.put(o, classify(o));
        }
        for (MatchRow r : members) {
            if (r.key().kind() == Kind.METHOD && !r.key().name().equals("<init>")) {
                String d = r.key().desc();
                int close = d.indexOf(')');
                declaredRet.computeIfAbsent(r.key().owner(), k -> new HashMap<>()).put(r.key().name() + d.substring(0, close + 1), d.substring(close + 1));
            }
        }

        Map<String, String> files = new TreeMap<>();
        List<TodoEntry> todos = new ArrayList<>();
        Map<String, Node> newTops = new TreeMap<>();
        Map<String, List<String>> extBodies = new TreeMap<>();
        Map<String, Set<String>> extImports = new TreeMap<>();
        Map<String, Set<String>> fragImports = new TreeMap<>();
        Map<String, List<String>> fragBodies = new TreeMap<>();
        int[] counters = new int[3];

        Map<String, List<MatchRow>> byOwner = new TreeMap<>();
        for (MatchRow r : members) {
            byOwner.computeIfAbsent(r.key().owner(), k -> new ArrayList<>()).add(r);
        }
        for (String owner : owners) {
            Cls c = classes.get(owner);
            List<Gen> gens = buildMembers(c, byOwner.getOrDefault(owner, List.of()), todos, counters);
            switch (c.mode()) {
                case NEW -> {
                    Node n = nodeFor(newTops, c);
                    for (Gen g : gens) {
                        n.members.add(g.text);
                        n.imports.addAll(g.imports);
                    }
                }
                case EXT -> {
                    if (!gens.isEmpty()) {
                        String path = path(c.genFqn() + "Ext", false);
                        for (Gen g : gens) {
                            extBodies.computeIfAbsent(path, k -> new ArrayList<>()).add(g.text);
                            extImports.computeIfAbsent(path, k -> new TreeSet<>()).addAll(g.imports);
                        }
                    }
                }
                case FRAGMENT -> {
                    if (!gens.isEmpty()) {
                        String path = "fragments/" + c.shimFqn().substring(c.shimFqn().lastIndexOf('.') + 1) + ".members.java";
                        fragBodies.computeIfAbsent(path, k -> new ArrayList<>()).add("// " + c.owner());
                        for (Gen g : gens) {
                            fragBodies.get(path).add(g.text);
                            fragImports.computeIfAbsent(path, k -> new TreeSet<>()).addAll(g.imports);
                        }
                    }
                }
            }
        }
        for (Node top : newTops.values()) {
            files.put(path(top.fqn, false), renderTop(top));
        }
        for (Map.Entry<String, List<String>> e : extBodies.entrySet()) {
            files.put(e.getKey(), renderExt(e.getKey(), e.getValue(), extImports.get(e.getKey())));
        }
        for (Map.Entry<String, List<String>> e : fragBodies.entrySet()) {
            StringBuilder sb = new StringBuilder("// porter:generated (shimgen) - members to add to the shim, for review; the shim itself is not touched.\n");
            for (String imp : fragImports.getOrDefault(e.getKey(), Set.of())) {
                sb.append("// import ").append(imp).append(";\n");
            }
            sb.append('\n');
            e.getValue().forEach(s -> sb.append(s).append(s.startsWith("//") ? "\n" : "\n\n"));
            files.put(e.getKey(), sb.toString());
        }
        return new Output(files, todos, new ArrayList<>(warnings), counters[0], counters[1], counters[2]);
    }

    private boolean eligible(MatchRow r) {
        if (r.kind() == Kind.TYPE) {
            return false;
        }
        return switch (r.plan()) {
            case MIRROR, HINT, TODO -> true;
            case MAP, EXPAND -> r.shim() != null && !r.shim().isBlank();
            case REGISTRY -> false;
        };
    }

    // ---------------------------------------------------------------- classification

    private String mappedClass(String owner) {
        MatchRow t = typeRows.get(owner);
        if (t != null && (t.plan() == Plan.MAP || t.plan() == Plan.EXPAND) && t.target() != null
                && t.target().owner() != null && !t.target().owner().isEmpty()) {
            return t.target().owner().replace('/', '.');
        }
        return null;
    }

    private Cls classify(String owner) {
        String top = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        String simple = simpleOf(owner);
        String mapped = mappedClass(owner);
        String declared = in.conceptShims().get(owner);
        String genFqn = GEN_PACKAGE + "." + pkgOf(top) + (pkgOf(top).isEmpty() ? "" : ".") + nestedName(owner, top);
        if (declared != null && in.shims().byFqn(declared) != null) {
            return new Cls(Mode.FRAGMENT, owner, simple, genFqn, declared, null);
        }
        if (mapped != null) {
            return new Cls(Mode.EXT, owner, simple, ownerGenBase(owner), null, srcName(mapped));
        }
        MatchRow t = typeRows.get(owner);
        if (t != null && t.shim() != null && in.shims().byFqn(t.shim()) != null) {
            return new Cls(Mode.FRAGMENT, owner, simple, genFqn, t.shim(), null);
        }
        ShimIndex.Info byName = in.shims().bySimple(simple);
        if (byName != null) {
            return new Cls(Mode.FRAGMENT, owner, simple, genFqn, byName.fqn, null);
        }
        return new Cls(Mode.NEW, owner, simple, genFqn, null, null);
    }

    /** {@code <gen package>.<Top>_<Nested>}-style base name used for Ext classes (nested owners are flattened). */
    private String ownerGenBase(String owner) {
        String top = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        return GEN_PACKAGE + "." + pkgOf(top) + (pkgOf(top).isEmpty() ? "" : ".") + flat(owner, top);
    }

    private static String nestedName(String owner, String top) {
        String simpleTop = top.substring(top.lastIndexOf('.') + 1);
        return owner.length() > top.length() ? simpleTop + "." + owner.substring(top.length() + 1).replace('$', '.') : simpleTop;
    }

    private static String flat(String owner, String top) {
        String simpleTop = top.substring(top.lastIndexOf('.') + 1);
        return owner.length() > top.length() ? simpleTop + "_" + owner.substring(top.length() + 1).replace('$', '_') : simpleTop;
    }

    private static String pkgOf(String top) {
        int dot = top.lastIndexOf('.');
        String pkg = dot < 0 ? "" : top.substring(0, dot);
        if (pkg.startsWith("net.minecraftforge.")) {
            pkg = pkg.substring("net.minecraftforge.".length());
        } else if (pkg.startsWith("net.minecraft.")) {
            pkg = pkg.substring("net.minecraft.".length());
        }
        return pkg;
    }

    private static String simpleOf(String owner) {
        String s = owner.substring(owner.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    private static String srcName(String binary) {
        return binary.replace('$', '.');
    }

    private static String path(String fqn, boolean unused) {
        return "src/" + fqn.replace('.', '/') + ".java";
    }

    // ---------------------------------------------------------------- types

    /** Source name for a class in a signature; unknown platform classes become Object with a warning. */
    private String typeName(String binary) {
        String r = tryType(binary);
        if (r == null) {
            warnings.add("unmapped type " + binary + " (written as java.lang.Object)");
            return "java.lang.Object";
        }
        return r;
    }

    /** Non-Minecraft types that exist on the 1.7.10 class path (JDK, Forge's libraries, Mojang's authlib, LWJGL 2). */
    private static final java.util.regex.Pattern TARGET_LIBS = java.util.regex.Pattern.compile(
            "^(java\\.|javax\\.|com\\.google\\.(common|gson)\\.|org\\.apache\\.|io\\.netty\\.|org\\.lwjgl\\.|org\\.objectweb\\.|com\\.mojang\\.authlib\\.|cpw\\.mods\\.|net\\.minecraftforge)");

    private String tryType(String binary) {
        if (!binary.startsWith("net.minecraft")) {
            Cls own = classes.get(binary);
            if (own != null) { // a generated mirror of a library type (com.mojang.blaze3d.*): the mirror, not the 1.19.2 name
                return own.mode() == Mode.FRAGMENT ? own.shimFqn() : own.genFqn();
            }
            return TARGET_LIBS.matcher(binary).find() ? srcName(binary) : null; // other 1.19.2-only libraries do not exist on 1.7.10
        }
        String declared = in.conceptShims().get(binary);
        if (declared != null) {
            return declared;
        }
        String mapped = mappedClass(binary);
        if (mapped != null) {
            return srcName(mapped);
        }
        Cls c = classes.get(binary);
        if (c != null) {
            return c.mode() == Mode.FRAGMENT ? c.shimFqn() : c.genFqn();
        }
        MatchRow t = typeRows.get(binary);
        if (t != null && t.shim() != null && !t.shim().isBlank()) {
            return t.shim();
        }
        ShimIndex.Info byName = in.shims().bySimple(simpleOf(binary));
        return byName != null ? byName.fqn : null;
    }

    // ---------------------------------------------------------------- members

    private record Gen(String text, Set<String> imports) {
    }

    private List<Gen> buildMembers(Cls c, List<MatchRow> rows, List<TodoEntry> todos, int[] counters) {
        List<Gen> out = new ArrayList<>();
        Map<String, SymbolKey> taken = new HashMap<>();
        Map<String, List<SymbolKey>> collided = new HashMap<>();
        ShimIndex.Info existing = switch (c.mode()) {
            case FRAGMENT -> in.shims().byFqn(c.shimFqn());
            case EXT -> in.shims().bySimple(c.genFqn().substring(c.genFqn().lastIndexOf('.') + 1) + "Ext");
            case NEW -> null;
        };
        boolean itf = isInterface(c);
        List<MatchRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int x = a.key().name().compareTo(b.key().name());
            return x != 0 ? x : a.key().desc().compareTo(b.key().desc());
        });
        // first pass: signatures, to find collisions
        for (MatchRow r : sorted) {
            Spec s = spec(c, r, itf);
            if (s.sigKey != null && existing != null && (existing.methods.contains(s.sigKey) || existing.fields.contains(s.sigKey))) {
                counters[1]++;
                continue;
            }
            if (s.sigKey != null && c.mode() != Mode.EXT && !s.isStatic && !s.field && OBJECT_FINALS.contains(s.sigKey)) {
                todos.add(todo(r, s.header, "clashes with a final java.lang.Object method; not generated"));
                continue;
            }
            SymbolKey first = taken.putIfAbsent(s.sigKey, r.key());
            if (first != null) {
                collided.computeIfAbsent(s.sigKey, k -> new ArrayList<>()).add(r.key());
                continue;
            }
            Gen g = render(c, r, s, itf, todos, counters, collided);
            out.add(g);
        }
        // collisions: later keys get a todo row each; the generated member lists them
        for (Map.Entry<String, List<SymbolKey>> e : collided.entrySet()) {
            SymbolKey first = taken.get(e.getKey());
            for (SymbolKey k : e.getValue()) {
                MatchRow r = rows.stream().filter(x -> x.key().equals(k)).findFirst().orElseThrow();
                todos.add(todo(r, e.getKey(), "same signature as " + first));
            }
        }
        // second pass for the "also" lines: patch the first member's javadoc
        if (!collided.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                Gen g = out.get(i);
                for (Map.Entry<String, List<SymbolKey>> e : collided.entrySet()) {
                    String marker = "porter:upfront " + taken.get(e.getKey()) + "\n";
                    if (g.text.contains(marker)) {
                        String also = "     *  also: " + e.getValue().stream().map(SymbolKey::toString).reduce((a, b) -> a + ", " + b).orElse("") + "\n";
                        int at = g.text.indexOf("     */");
                        out.set(i, new Gen(g.text.substring(0, at) + also + g.text.substring(at), g.imports));
                    }
                }
            }
        }
        return out;
    }

    private boolean isInterface(Cls c) {
        Hier h = in.hierarchy().get(c.owner());
        if (c.mode() == Mode.FRAGMENT) {
            ShimIndex.Info i = in.shims().byFqn(c.shimFqn());
            return i != null && i.isInterface;
        }
        return c.mode() == Mode.NEW && h != null && h.isInterface();
    }

    private static final class Spec {
        String name;
        String sigKey;
        String header;
        boolean isStatic;
        boolean ctor;
        boolean field;
        List<String> ptypes = new ArrayList<>();
        List<String> pnames = new ArrayList<>();
        String ret;
        String fieldType;
    }

    /** Generated methods by owner: {@code name(params)} to the 1.19.2 return descriptor; used to keep overrides type-compatible. */
    private final Map<String, Map<String, String>> declaredRet = new HashMap<>();

    /**
     * The return type of the nearest generated ancestor method this one overrides, when the 1.19.2 return type is a
     * different (covariant) reference type: the generated override must use the ancestor's type to be a valid override.
     */
    private String overriddenReturn(String owner, String name, String desc) {
        int close = desc.indexOf(')');
        String sig = name + desc.substring(0, close + 1);
        String own = desc.substring(close + 1);
        if (!(own.startsWith("L") || own.startsWith("["))) {
            return null;
        }
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String o = queue.poll();
            if (!seen.add(o)) {
                continue;
            }
            if (!o.equals(owner)) {
                String r = declaredRet.getOrDefault(o, Map.of()).get(sig);
                if (r != null && !r.equals(own) && (r.startsWith("L") || r.startsWith("["))) {
                    return r;
                }
            }
            Hier h = in.hierarchy().get(o);
            if (h != null) {
                if (h.superName() != null) {
                    queue.add(h.superName());
                }
                queue.addAll(h.interfaces());
            }
        }
        return null;
    }

    private Spec spec(Cls c, MatchRow r, boolean itf) {
        Spec s = new Spec();
        SymbolKey k = r.key();
        Target t = r.target();
        boolean ext = c.mode() == Mode.EXT;
        Integer acc = in.access().get(k);
        s.isStatic = t != null ? t.isStatic() : acc != null && (acc & 8) != 0;
        Set<String> used = new HashSet<>();
        if (k.kind() == Kind.FIELD) {
            s.field = true;
            s.fieldType = JavaSig.field(k.desc(), this::typeName);
            s.name = k.name();
            s.ret = s.fieldType;
            if (ext) {
                s.isStatic = false;
                s.ptypes.add(c.selfType());
                s.pnames.add("self");
                s.sigKey = "get" + JavaSig.cap(k.name()) + "(" + ShimIndex.simpleName(c.selfType()) + ")";
                s.header = "public static " + s.ret + " get" + JavaSig.cap(k.name()) + "(" + c.selfType() + " self)";
            } else {
                s.sigKey = k.name();
                s.header = "public " + (s.isStatic ? "static " : "") + s.ret + " " + k.name();
            }
            return s;
        }
        JavaSig.Desc d = JavaSig.method(k.desc(), this::typeName);
        s.ctor = k.name().equals("<init>");
        s.ret = s.ctor ? (ext ? c.selfType() : "void") : d.ret();
        if (!s.ctor && !ext && !s.isStatic) {
            String anc = overriddenReturn(k.owner(), k.name(), k.desc());
            if (anc != null) {
                s.ret = JavaSig.method("()" + anc, this::typeName).ret();
            }
        }
        // a constructor carries the name of the class it is written in: for a fragment that is the existing shim, not the 1.19.2 class
        String ctorName = c.mode() == Mode.FRAGMENT && c.shimFqn() != null ? c.shimFqn().substring(c.shimFqn().lastIndexOf('.') + 1) : c.simple();
        s.name = s.ctor ? (ext ? "new" + c.simple() : ctorName) : k.name();
        if (s.ctor) {
            s.isStatic = ext;
        }
        boolean selfFirst = ext && !s.ctor && !s.isStatic;
        if (selfFirst) {
            s.ptypes.add(c.selfType());
            s.pnames.add("self");
            used.add("self");
        }
        for (String p : d.params()) {
            s.ptypes.add(p);
            s.pnames.add(JavaSig.paramName(p, used));
        }
        List<String> simple = new ArrayList<>();
        for (String p : s.ptypes) {
            simple.add(ShimIndex.simpleName(p));
        }
        s.sigKey = (s.ctor && !ext ? "<init>" : s.name) + "(" + String.join(",", simple) + ")";
        StringBuilder h = new StringBuilder();
        boolean staticOut = ext || s.isStatic;
        if (itf && !staticOut) {
            h.append("default ");
        } else {
            h.append("public ");
            if (staticOut) {
                h.append("static ");
            }
        }
        if (!(s.ctor && !ext)) {
            h.append(s.ret).append(' ');
        }
        h.append(s.name).append('(');
        for (int i = 0; i < s.ptypes.size(); i++) {
            h.append(i > 0 ? ", " : "").append(s.ptypes.get(i)).append(' ').append(s.pnames.get(i));
        }
        h.append(')');
        s.header = h.toString();
        return s;
    }

    private Gen render(Cls c, MatchRow r, Spec s, boolean itf, List<TodoEntry> todos, int[] counters,
                       Map<String, List<SymbolKey>> collided) {
        Set<String> imports = new TreeSet<>();
        String key = r.key().toString();
        StringBuilder sb = new StringBuilder();
        StringBuilder doc = new StringBuilder("    /** porter:upfront ").append(key).append('\n');
        doc.append("     *  plan ").append(r.plan());
        String hint = hint(r);
        if (!hint.isEmpty()) {
            doc.append("; ").append(hint);
        }
        if (!r.alternatives().isEmpty()) {
            List<String> alts = new ArrayList<>();
            for (Alt a : r.alternatives()) {
                alts.add(a.name() + " " + a.score());
            }
            doc.append("; candidates: ").append(String.join(", ", alts));
        }
        doc.append('\n').append("     */\n");
        counters[0]++;

        if (s.field && c.mode() != Mode.EXT) {
            sb.append(doc);
            sb.append("    ").append(s.header);
            if (itf) {
                sb.setLength(sb.length() - s.header.length());
                sb.append(s.ret).append(' ').append(s.name).append(" = ").append(defaultValue(s.ret));
            }
            sb.append(";");
            return new Gen(sb.toString(), imports);
        }

        BodyEntry db = s.field ? null : in.bodies().get(r.key());
        String body;
        boolean isTodo = false;
        if (db != null) {
            body = indent(db.body(), 8);
            imports.addAll(db.imports());
            counters[2]++;
        } else {
            String delegate = delegate(c, r, s);
            if (delegate != null) {
                body = "        " + delegate;
            } else {
                body = "        throw new UnsupportedOperationException(\"porter:todo " + s.name + "\");";
                isTodo = true;
            }
        }
        sb.append(doc);
        sb.append("    ").append(s.header).append(" {\n");
        sb.append("        // porter:body ").append(key).append('\n');
        sb.append(body).append('\n');
        sb.append("        // porter:end\n");
        sb.append("    }");
        if (s.field) {
            // Ext field: matching setter
            sb.append("\n\n    public static void set").append(JavaSig.cap(s.name)).append('(').append(c.selfType()).append(" self, ")
                    .append(s.fieldType).append(" value) {\n");
            sb.append("        // porter:body ").append(key).append(" set\n");
            Target t = r.target();
            if (t != null && !t.name().isEmpty() && (r.plan() == Plan.MAP || r.plan() == Plan.EXPAND)) { // HINT: the 1.7.10 field has another type
                sb.append("        self.").append(t.name()).append(" = value;\n");
            } else {
                sb.append("        throw new UnsupportedOperationException(\"porter:todo set").append(JavaSig.cap(s.name)).append("\");\n");
            }
            sb.append("        // porter:end\n    }");
        }
        if (isTodo) {
            todos.add(todo(r, s.header, null));
        }
        return new Gen(sb.toString(), imports);
    }

    private String hint(MatchRow r) {
        Target t = r.target();
        if (r.plan() == Plan.HINT && t != null) {
            return "hint: lineage target " + t.owner() + "#" + t.name() + t.desc();
        }
        return "";
    }

    private TodoEntry todo(MatchRow r, String signature, String extraHint) {
        String hint = hint(r);
        if (extraHint != null) {
            hint = hint.isEmpty() ? extraHint : hint + "; " + extraHint;
        }
        return new TodoEntry(r.key(), r.key().owner(), signature, r.plan(), hint, r.alternatives(), r.uses(), r.area());
    }

    /** Delegate statement for MAP/EXPAND rows with a 1.7.10 target; null when there is none. */
    private String delegate(Cls c, MatchRow r, Spec s) {
        Target t = r.target();
        if ((r.plan() != Plan.MAP && r.plan() != Plan.EXPAND) || t == null || t.name().isEmpty() || s.ctor) {
            return null;
        }
        String args;
        List<String> names = new ArrayList<>(s.pnames);
        if (c.mode() == Mode.EXT && !s.isStatic && !names.isEmpty() && names.get(0).equals("self")) {
            names.remove(0);
        }
        args = String.join(", ", names);
        String target = t.isStatic() ? srcName(t.owner().replace('/', '.')) : (c.mode() == Mode.EXT ? "self" : "this");
        if (s.field) {
            return "return " + target + "." + t.name() + ";";
        }
        String call = target + "." + t.name() + "(" + args + ");";
        return s.ret.equals("void") ? call : "return " + call;
    }

    private static String defaultValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "byte", "short", "char", "int", "long", "float", "double" -> "0";
            default -> "null";
        };
    }

    private static String indent(String body, int n) {
        String pad = " ".repeat(n);
        StringBuilder sb = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].isEmpty() ? "" : pad + lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- rendering of files

    private static final class Node {
        String fqn;
        String simple;
        boolean isInterface;
        String extendsType;
        List<String> implementsTypes = new ArrayList<>();
        List<String> members = new ArrayList<>();
        Set<String> imports = new TreeSet<>();
        Map<String, Node> children = new TreeMap<>();
    }

    private Node nodeFor(Map<String, Node> tops, Cls c) {
        String owner = c.owner();
        String top = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        String topSimple = top.substring(top.lastIndexOf('.') + 1);
        String pkg = pkgOf(top);
        String topFqn = GEN_PACKAGE + "." + (pkg.isEmpty() ? "" : pkg + ".") + topSimple;
        Node n = tops.computeIfAbsent(topFqn, k -> {
            Node x = new Node();
            x.fqn = topFqn;
            x.simple = topSimple;
            return x;
        });
        applyHier(n, top);
        if (owner.length() > top.length()) {
            String chain = owner.substring(top.length());
            String acc = top;
            for (String part : chain.substring(1).split("\\$")) {
                acc = acc + "$" + part;
                final String full = acc;
                Node parent = n;
                n = parent.children.computeIfAbsent(part, k -> {
                    Node x = new Node();
                    x.fqn = parent.fqn + "." + part;
                    x.simple = part;
                    return x;
                });
                applyHier(n, full);
            }
        }
        return n;
    }

    private void applyHier(Node n, String owner) {
        Hier h = in.hierarchy().get(owner);
        if (h == null || n.extendsType != null || !n.implementsTypes.isEmpty()) {
            if (h != null) {
                n.isInterface = h.isInterface();
            }
            return;
        }
        n.isInterface = h.isInterface();
        if (h.superName() != null && !h.superName().equals("java.lang.Object") && !h.superName().equals("java.lang.Enum")
                && !h.superName().equals("java.lang.Record") && !n.isInterface) { // no enum/record base in a Java 8 shim class
            String s = tryType(h.superName());
            if (s != null && !s.equals(n.fqn)) {
                if (Boolean.TRUE.equals(kindOf(s))) {
                    n.implementsTypes.add(s); // the 1.19.2 super class is an interface on the target side
                } else if (defaultCtor.test(s) && generatedDefaultCtor(s)) {
                    n.extendsType = s;
                } else {
                    warnings.add("generated class " + n.fqn + " is written without 'extends " + s + "' (that class has no no-argument constructor)");
                }
            }
        }
        for (String i : h.interfaces()) {
            String s = tryType(i);
            if (s != null && !s.equals(n.fqn)) {
                if (Boolean.FALSE.equals(kindOf(s))) {
                    warnings.add("generated class " + n.fqn + " is written without 'implements " + s + "' (that is a class on the target side)");
                } else if (!n.implementsTypes.contains(s)) {
                    n.implementsTypes.add(s);
                }
            }
        }
    }

    private static String header(String pkg, Set<String> imports) {
        StringBuilder sb = new StringBuilder("// porter:generated (shimgen) - bodies come from the bodies DB; edit them there.\n");
        sb.append("package ").append(pkg).append(";\n\n");
        for (String i : imports) {
            sb.append("import ").append(i).append(";\n");
        }
        if (!imports.isEmpty()) {
            sb.append('\n');
        }
        sb.append("@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n");
        return sb.toString();
    }

    private String renderTop(Node top) {
        Set<String> imports = new TreeSet<>();
        collectImports(top, imports);
        String pkg = top.fqn.substring(0, top.fqn.lastIndexOf('.'));
        StringBuilder sb = new StringBuilder(header(pkg, imports));
        renderNode(top, sb, "", false);
        return sb.toString();
    }

    private void collectImports(Node n, Set<String> out) {
        out.addAll(n.imports);
        n.children.values().forEach(ch -> collectImports(ch, out));
    }

    private void renderNode(Node n, StringBuilder sb, String ind, boolean nested) {
        // a class with super types may leave abstract methods of them open: written abstract (TODO bodies fill the members, not the type)
        boolean open = !n.isInterface && (n.extendsType != null || !n.implementsTypes.isEmpty());
        sb.append(ind).append("public ").append(nested && !n.isInterface ? "static " : "").append(open ? "abstract " : "")
                .append(n.isInterface ? "interface " : "class ").append(n.simple);
        if (n.extendsType != null) {
            sb.append(" extends ").append(n.extendsType);
        }
        if (!n.implementsTypes.isEmpty()) {
            sb.append(n.isInterface ? " extends " : " implements ").append(String.join(", ", n.implementsTypes));
        }
        sb.append(" {\n");
        List<String> parts = new ArrayList<>(n.members);
        for (String m : parts) {
            sb.append(indentBlock(m, ind)).append("\n\n");
        }
        for (Node ch : n.children.values()) {
            renderNode(ch, sb, ind + "    ", true);
            sb.append('\n');
        }
        sb.append(ind).append("}\n");
    }

    private static String indentBlock(String text, String ind) {
        if (ind.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].isEmpty() ? "" : ind + lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String renderExt(String path, List<String> members, Set<String> imports) {
        String fqn = path.substring("src/".length(), path.length() - ".java".length()).replace('/', '.');
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(header(pkg, imports));
        sb.append("public final class ").append(simple).append(" {\n    private ").append(simple).append("() {}\n\n");
        members.forEach(m -> sb.append(m).append("\n\n"));
        sb.append("}\n");
        return sb.toString();
    }

    /** Writes the output files below {@code out} and the TODO list. */
    public static void write(Output o, Path out, Path todo) throws IOException {
        for (Map.Entry<String, String> e : o.files().entrySet()) {
            Path p = out.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        if (todo != null) {
            if (todo.getParent() != null) {
                Files.createDirectories(todo.getParent());
            }
            TodoEntry.write(todo, o.todos());
        }
    }

    /** Reads the optional access map ({@code api-inventory.json}) so static members are generated static. */
    public static Map<SymbolKey, Integer> readAccess(Path inventory) throws IOException {
        Map<SymbolKey, Integer> out = new LinkedHashMap<>();
        if (inventory == null) {
            return out;
        }
        dev.porter.port.upfront.model.ApiInventory inv = dev.porter.port.upfront.model.ApiInventory.read(inventory);
        for (dev.porter.port.upfront.model.ApiSymbol s : inv.symbols()) {
            if (s.access() >= 0) {
                out.put(s.key(), s.access());
            }
        }
        return out;
    }
}
