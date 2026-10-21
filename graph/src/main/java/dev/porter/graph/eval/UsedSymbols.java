package dev.porter.graph.eval;

import dev.porter.graph.model.Sym;
import dev.porter.graph.model.SymbolRef;
import dev.porter.graph.query.SymbolGraph;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Which Minecraft symbols does a compiled mod use? Scans a mod jar's bytecode for method calls, field accesses and
 * overrides of Minecraft members and resolves them to graph symbols of the given version (member names in the jar
 * may be in any namespace of that version: srg for production jars, mcp/mojmap for dev jars).
 */
public final class UsedSymbols {
    private static final Pattern SRG = Pattern.compile("(m|f)_\\d+_|(func|field)_\\d+_[a-zA-Z]+_?");

    /** One resolved use: the graph symbol and the mod classes that use it. */
    public record Use(SymbolRef ref, boolean override, Set<String> usedBy) {
    }

    /** Scan result: resolved uses plus Minecraft-looking references that could not be resolved. */
    public record Result(Map<String, Use> uses, Set<String> unresolved) {
    }

    private final SymbolGraph graph;
    private final String version;
    private final Map<String, SymbolGraph.SymbolInfo> classes = new HashMap<>();
    private final Map<String, List<SymbolGraph.SymbolInfo>> membersByOwner = new HashMap<>();
    private final Map<String, List<SymbolGraph.SymbolInfo>> membersByName = new HashMap<>();

    public UsedSymbols(SymbolGraph graph, String version) {
        this.graph = graph;
        this.version = version;
        for (SymbolGraph.SymbolInfo s : graph.symbols(version)) {
            if (s.ref().kind() == Sym.Kind.CLASS) {
                for (String n : s.names().values()) {
                    classes.putIfAbsent(n.replace('.', '/'), s);
                }
            } else {
                membersByOwner.computeIfAbsent(s.ref().owner(), k -> new ArrayList<>()).add(s);
                for (String n : deobfNames(s)) {
                    membersByName.computeIfAbsent(n, k -> new ArrayList<>()).add(s);
                }
            }
        }
    }

    public Result scan(Path jar) throws IOException {
        Map<String, ClassNode> mod = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (!ze.getName().endsWith(".class") || ze.getName().contains("module-info")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(ze)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    mod.put(cn.name, cn);
                }
            }
        }
        Map<String, Use> uses = new TreeMap<>();
        Set<String> unresolved = new TreeSet<>();
        for (ClassNode cn : mod.values()) {
            for (MethodNode mn : cn.methods) {
                if ((mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0 && !mn.name.startsWith("<")) {
                    SymbolGraph.SymbolInfo s = resolve(mod, cn.name, mn.name, mn.desc, false, true);
                    if (s != null) {
                        add(uses, s, true, cn.name);
                    }
                }
                if (mn.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : mn.instructions) {
                    String owner;
                    String name;
                    String desc;
                    boolean field;
                    if (insn instanceof MethodInsnNode mi) {
                        owner = mi.owner;
                        name = mi.name;
                        desc = mi.desc;
                        field = false;
                    } else if (insn instanceof FieldInsnNode fi) {
                        owner = fi.owner;
                        name = fi.name;
                        desc = fi.desc;
                        field = true;
                    } else {
                        continue;
                    }
                    if (owner.startsWith("[")) {
                        continue;
                    }
                    boolean mcOwner = isMinecraft(owner);
                    if (!mcOwner && !(mod.containsKey(owner) && (SRG.matcher(name).matches() || hasMcAncestor(mod, owner)))) {
                        continue;
                    }
                    SymbolGraph.SymbolInfo s = resolve(mod, owner, name, desc, field, false);
                    if (s != null) {
                        add(uses, s, false, cn.name);
                    } else if (mcOwner || SRG.matcher(name).matches()) {
                        unresolved.add(owner + "." + name + desc);
                    }
                }
            }
        }
        return new Result(uses, unresolved);
    }

    private static boolean isMinecraft(String internal) {
        return internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/blaze3d/")
                || internal.startsWith("com/mojang/math/") || internal.startsWith("com/mojang/realmsclient/");
    }

    private void add(Map<String, Use> uses, SymbolGraph.SymbolInfo s, boolean override, String by) {
        String k = SymbolGraph.key(s.ref());
        Use u = uses.computeIfAbsent(k, x -> new Use(s.ref(), override, new TreeSet<>()));
        u.usedBy().add(by);
    }

    private boolean hasMcAncestor(Map<String, ClassNode> mod, String cls) {
        return !mcAncestors(mod, cls).isEmpty();
    }

    /** Minecraft classes reachable from a mod class through super classes and interfaces (nearest first). */
    private List<SymbolGraph.SymbolInfo> mcAncestors(Map<String, ClassNode> mod, String cls) {
        List<SymbolGraph.SymbolInfo> out = new ArrayList<>();
        ArrayDeque<String> q = new ArrayDeque<>(List.of(cls));
        Set<String> seen = new LinkedHashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) {
                continue;
            }
            SymbolGraph.SymbolInfo mc = classes.get(c);
            if (mc != null && isMinecraft(c)) {
                out.add(mc);
                continue;
            }
            ClassNode cn = mod.get(c);
            if (cn != null) {
                if (cn.superName != null) {
                    q.add(cn.superName);
                }
                q.addAll(cn.interfaces);
            }
        }
        return out;
    }

    private SymbolGraph.SymbolInfo resolve(Map<String, ClassNode> mod, String owner, String name, String desc,
                                           boolean field, boolean overrideOnly) {
        List<SymbolGraph.SymbolInfo> starts = new ArrayList<>();
        SymbolGraph.SymbolInfo direct = isMinecraft(owner) ? classes.get(owner) : null;
        if (direct != null) {
            starts.add(direct);
        } else {
            starts.addAll(mcAncestors(mod, owner));
        }
        Sym.Kind kind = field ? Sym.Kind.FIELD : Sym.Kind.METHOD;
        // walk the Minecraft hierarchy upwards from each start class (nearest declaration wins)
        ArrayDeque<String> q = new ArrayDeque<>();
        starts.forEach(c -> q.add(c.ref().name()));
        Set<String> seen = new LinkedHashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) {
                continue;
            }
            for (SymbolGraph.SymbolInfo m : membersByOwner.getOrDefault(c, List.of())) {
                if (m.ref().kind() == kind && deobfNames(m).contains(name) && (field || desc.equals(m.ref().desc()))) {
                    return m;
                }
            }
            q.addAll(graph.supertypes(version, c));
        }
        if (!SRG.matcher(name).matches()) {
            return null; // MCP/mojmap names are not unique: only the owner's hierarchy can resolve them
        }
        // SRG ids are unique per member (overrides share them): any declaration with that id and descriptor.
        SymbolGraph.SymbolInfo any = null;
        for (SymbolGraph.SymbolInfo m : membersByName.getOrDefault(name, List.of())) {
            if (m.ref().kind() != kind) {
                continue;
            }
            if (field || desc.equals(m.ref().desc())) {
                return m;
            }
            if (any == null) {
                any = m;
            }
        }
        return overrideOnly ? null : any;
    }

    /** Names of a symbol in every namespace except obf (obfuscated names collide with anything). */
    private static Set<String> deobfNames(SymbolGraph.SymbolInfo s) {
        Set<String> out = new TreeSet<>();
        s.names().forEach((ns, n) -> {
            if (!ns.equals("obf")) {
                out.add(n);
            }
        });
        return out;
    }

    public String version() {
        return version;
    }

    public SymbolGraph graph() {
        return graph;
    }
}
