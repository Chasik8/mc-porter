package dev.porter.graph.load;

import dev.porter.core.json.Json;
import dev.porter.graph.model.Sym;
import dev.porter.graph.model.VersionData;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MissingDescFilter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.srg.SrgFileReader;
import net.fabricmc.mappingio.format.srg.TsrgFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import tools.jackson.databind.JsonNode;

/**
 * Loads one version: reads all mappings into a single mapping-io {@link MemoryMappingTree} (source namespace
 * {@code obf}; destination namespaces {@code mojmap}, {@code srg}, {@code mcp} as available), optionally writes it as
 * Tiny v2 (the normalised internal format), then walks the client jar and names every non-synthetic symbol.
 */
public final class VersionLoader {
    public static final String OBF = "obf";
    private static final Pattern SRG_METHOD = Pattern.compile("func_(\\d+)_[a-zA-Z]+_?");
    private static final Pattern MCP_PARAM = Pattern.compile("p_(\\d+)_(\\d+)_");

    private final Fetcher fetcher;
    private final Consumer<String> log;

    public VersionLoader(Fetcher fetcher, Consumer<String> log) {
        this.fetcher = fetcher;
        this.log = log;
    }

    /** Result: the symbols plus the jar path (the matcher re-reads it with code). */
    public record Loaded(VersionData data, Path jar) {
    }

    public Loaded load(Sources.Version v, Path tinyOut) throws IOException {
        List<VersionData.Source> sources = new ArrayList<>();
        Fetcher.Fetched jar = fetcher.fetch(v.jar());
        sources.add(new VersionData.Source(v.jar().name(), v.jar().version(), v.jar().url(), jar.sha256()));

        MemoryMappingTree tree = new MemoryMappingTree();
        Map<String, String> mcpNames = null;
        Map<Integer, Map<Integer, String>> mcpParams = null;
        JsonNode parchment = null;
        // Mojmap first: it is the most complete (descriptors for fields as well).
        List<Sources.Artifact> ordered = new ArrayList<>(v.mappings());
        ordered.sort((a, b) -> Integer.compare(order(a.type()), order(b.type())));
        for (Sources.Artifact m : ordered) {
            Fetcher.Fetched f = fetcher.fetch(m);
            sources.add(new VersionData.Source(m.name(), m.version(), m.url(), f.sha256()));
            switch (m.type()) {
                case "proguard" -> {
                    try (Reader r = Files.newBufferedReader(f.path(), StandardCharsets.UTF_8)) {
                        ProGuardFileReader.read(r, "mojmap", OBF, new MappingSourceNsSwitch(tree, OBF));
                    }
                }
                case "srg" -> {
                    try (Reader r = reader(f.path(), m.entry())) {
                        SrgFileReader.read(r, OBF, "srg", tree);
                    }
                }
                case "tsrg" -> {
                    String text = new String(Fetcher.readEntry(f.path(), m.entry()), StandardCharsets.UTF_8);
                    if (text.startsWith("tsrg2")) {
                        // tsrg2 header: "tsrg2 obf srg id"; keep obf -> srg, drop the numeric id namespace.
                        MemoryMappingTree t2 = new MemoryMappingTree();
                        TsrgFileReader.read(new StringReader(text), t2);
                        t2.accept(new MappingDstNsReorder(tree, List.of("srg")));
                    } else {
                        TsrgFileReader.read(new StringReader(text), OBF, "srg", tree);
                    }
                }
                case "mcp-csv" -> {
                    mcpNames = new HashMap<>();
                    readCsv(f.path(), "methods.csv", mcpNames);
                    readCsv(f.path(), "fields.csv", mcpNames);
                    mcpParams = readParams(f.path());
                }
                case "parchment" -> parchment = Json.json().readTree(Fetcher.readEntry(f.path(), m.entry()));
                default -> throw new IOException("unknown mapping type " + m.type());
            }
        }
        if (mcpNames != null) {
            addMcpNamespace(tree, mcpNames);
        }
        List<String> namespaces = new ArrayList<>();
        namespaces.add(OBF);
        namespaces.addAll(tree.getDstNamespaces());
        VersionData data = new VersionData(v.id(), namespaces, v.canonical(), sources);
        readJar(jar.path(), tree, data);
        if (tinyOut != null) {
            Files.createDirectories(tinyOut.getParent());
            try (Writer w = Files.newBufferedWriter(tinyOut, StandardCharsets.UTF_8)) {
                // Fill names missing in a namespace (unnamed MCP members, members absent from one file).
                Map<String, String> fallback = new LinkedHashMap<>();
                for (String ns : tree.getDstNamespaces()) {
                    fallback.put(ns, ns.equals("mcp") ? "srg" : OBF);
                }
                tree.accept(new MissingDescFilter(new MappingNsCompleter(new Tiny2FileWriter(w, false), fallback, true)));
            }
        }
        if (mcpParams != null) {
            applyMcpParams(data, mcpParams);
        }
        if (parchment != null) {
            applyParchment(data, parchment);
        }
        data.seal();
        log.accept(v.id() + ": " + data.symbols().size() + " symbols, namespaces " + namespaces);
        return new Loaded(data, jar.path());
    }

    private static int order(String type) {
        return switch (type) {
            case "proguard" -> 0;
            case "srg", "tsrg" -> 1;
            default -> 2;
        };
    }

    private static Reader reader(Path zip, String entry) {
        return new StringReader(new String(Fetcher.readEntry(zip, entry), StandardCharsets.UTF_8));
    }

    private static void readCsv(Path zip, String entry, Map<String, String> out) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(Fetcher.readEntry(zip, entry)), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // header: searge,name,side,desc
            while ((line = r.readLine()) != null) {
                String[] cols = line.split(",", 3);
                if (cols.length >= 2 && !cols[1].isEmpty()) {
                    out.put(cols[0], cols[1]);
                }
            }
        }
    }

    /** params.csv: p_<srg number>_<local index>_ -> name, grouped by the srg number of the method. */
    private static Map<Integer, Map<Integer, String>> readParams(Path zip) throws IOException {
        Map<Integer, Map<Integer, String>> out = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(Fetcher.readEntry(zip, "params.csv")), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] cols = line.split(",", 3);
                if (cols.length < 2) {
                    continue;
                }
                Matcher m = MCP_PARAM.matcher(cols[0]);
                if (m.matches()) {
                    out.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new TreeMap<>())
                            .put(Integer.parseInt(m.group(2)), cols[1]);
                }
            }
        }
        return out;
    }

    /** Adds namespace {@code mcp} = MCP name of each srg member (srg name if unnamed); classes keep the srg name. */
    private static void addMcpNamespace(MemoryMappingTree tree, Map<String, String> names) {
        int srg = tree.getNamespaceId("srg");
        List<String> dst = new ArrayList<>(tree.getDstNamespaces());
        dst.add("mcp");
        tree.setDstNamespaces(dst);
        int mcp = tree.getNamespaceId("mcp");
        for (MappingTree.ClassMapping c : tree.getClasses()) {
            c.setDstName(c.getDstName(srg), mcp);
            for (MappingTree.MethodMapping m : c.getMethods()) {
                String s = m.getDstName(srg);
                if (s != null) {
                    m.setDstName(names.getOrDefault(s, s), mcp);
                }
            }
            for (MappingTree.FieldMapping f : c.getFields()) {
                String s = f.getDstName(srg);
                if (s != null) {
                    f.setDstName(names.getOrDefault(s, s), mcp);
                }
            }
        }
    }

    private static void readJar(Path jar, MemoryMappingTree tree, VersionData data) throws IOException {
        int n = data.namespaces().size();
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = tree.getNamespaceId(data.namespaces().get(i));
        }
        List<ClassNode> nodes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().endsWith(".class") && !ze.getName().startsWith("META-INF/")
                        && !ze.getName().endsWith("module-info.class")) {
                    entries.add(ze);
                }
            }
            entries.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (ZipEntry ze : entries) {
                try (InputStream in = zip.getInputStream(ze)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    nodes.add(cn);
                }
            }
        }
        Map<String, ClassNode> byName = new HashMap<>();
        nodes.forEach(cn -> byName.put(cn.name, cn));
        for (ClassNode cn : nodes) {
            Sym c = new Sym(Sym.Kind.CLASS, cn.access, cn.name, null, null, n);
            MappingTree.ClassMapping cm = tree.getClass(cn.name);
            for (int i = 0; i < n; i++) {
                String name = cm == null ? null : (ids[i] == MappingTree.SRC_NAMESPACE_ID ? cm.getSrcName() : cm.getDstName(ids[i]));
                c.names[i] = name != null ? name : unmappedClassName(cn.name, tree, ids[i]);
            }
            c.superObf = cn.superName;
            c.interfacesObf = List.copyOf(cn.interfaces);
            data.addClass(c);
        }
        for (ClassNode cn : nodes) {
            Sym c = data.classByObf(cn.name);
            MappingTree.ClassMapping cm = tree.getClass(cn.name);
            for (MethodNode mn : cn.methods) {
                if ((mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                    continue;
                }
                Sym m = new Sym(Sym.Kind.METHOD, mn.access, mn.name, mn.desc, c, n);
                MappingTree.MethodMapping mm = cm == null ? null : cm.getMethod(mn.name, mn.desc);
                if (mm == null && cm != null) {
                    mm = inheritedMapping(tree, byName, cn, mn.name, mn.desc);
                }
                for (int i = 0; i < n; i++) {
                    String name = mm == null ? null : (ids[i] == MappingTree.SRC_NAMESPACE_ID ? mm.getSrcName() : mm.getDstName(ids[i]));
                    m.names[i] = name != null ? name : mn.name;
                }
                m.desc = tree.mapDesc(mn.desc, data.canonicalIndex() == 0 ? MappingTree.SRC_NAMESPACE_ID : ids[data.canonicalIndex()]);
                data.addMember(m);
            }
            for (FieldNode fn : cn.fields) {
                if ((fn.access & Opcodes.ACC_SYNTHETIC) != 0) {
                    continue;
                }
                Sym f = new Sym(Sym.Kind.FIELD, fn.access, fn.name, fn.desc, c, n);
                MappingTree.FieldMapping fm = cm == null ? null : cm.getField(fn.name, fn.desc);
                if (fm == null && cm != null) {
                    fm = cm.getField(fn.name, null);
                    if (fm != null && fm.getSrcDesc() == null) {
                        fm.setSrcDesc(fn.desc); // SRG/TSRG v1 have no field descriptors; Tiny v2 needs them
                    }
                }
                for (int i = 0; i < n; i++) {
                    String name = fm == null ? null : (ids[i] == MappingTree.SRC_NAMESPACE_ID ? fm.getSrcName() : fm.getDstName(ids[i]));
                    f.names[i] = name != null ? name : fn.name;
                }
                f.desc = tree.mapDesc(fn.desc, data.canonicalIndex() == 0 ? MappingTree.SRC_NAMESPACE_ID : ids[data.canonicalIndex()]);
                data.addMember(f);
            }
        }
    }

    /** Overrides that a mapping lists only on the declaring super type (seen in some SRG files). */
    private static MappingTree.MethodMapping inheritedMapping(MemoryMappingTree tree, Map<String, ClassNode> byName,
                                                              ClassNode cn, String name, String desc) {
        if (name.startsWith("<")) {
            return null;
        }
        List<String> queue = new ArrayList<>();
        if (cn.superName != null) {
            queue.add(cn.superName);
        }
        queue.addAll(cn.interfaces);
        for (int i = 0; i < queue.size() && i < 64; i++) {
            String s = queue.get(i);
            MappingTree.ClassMapping sm = tree.getClass(s);
            if (sm != null) {
                MappingTree.MethodMapping mm = sm.getMethod(name, desc);
                if (mm != null) {
                    return mm;
                }
            }
            ClassNode sn = byName.get(s);
            if (sn != null) {
                if (sn.superName != null) {
                    queue.add(sn.superName);
                }
                queue.addAll(sn.interfaces);
            }
        }
        return null;
    }

    /** Unmapped inner classes inherit the outer class name ({@code Outer$1}); others keep the jar name. */
    private static String unmappedClassName(String obf, MemoryMappingTree tree, int ns) {
        int dollar = obf.lastIndexOf('$');
        if (dollar > 0 && ns != MappingTree.SRC_NAMESPACE_ID) {
            String outer = unmappedOrMapped(obf.substring(0, dollar), tree, ns);
            return outer + obf.substring(dollar);
        }
        return obf;
    }

    private static String unmappedOrMapped(String obf, MemoryMappingTree tree, int ns) {
        MappingTree.ClassMapping cm = tree.getClass(obf);
        String n = cm == null ? null : cm.getDstName(ns);
        return n != null ? n : unmappedClassName(obf, tree, ns);
    }

    private static void applyMcpParams(VersionData data, Map<Integer, Map<Integer, String>> params) {
        int srg = data.ns("srg");
        for (Sym s : data.symbols()) {
            if (s.kind != Sym.Kind.METHOD) {
                continue;
            }
            Matcher m = SRG_METHOD.matcher(s.names[srg]);
            if (m.matches()) {
                Map<Integer, String> p = params.get(Integer.parseInt(m.group(1)));
                if (p != null) {
                    List<Sym.Param> list = new ArrayList<>();
                    p.forEach((idx, name) -> list.add(new Sym.Param(idx, name, "mcp")));
                    s.params = List.copyOf(list);
                }
            }
        }
    }

    /** Parchment: classes[].methods[].parameters[] keyed by mojmap class/name/descriptor. */
    private static void applyParchment(VersionData data, JsonNode root) {
        int moj = data.ns("mojmap");
        Map<String, Sym> methods = new HashMap<>();
        for (Sym s : data.symbols()) {
            if (s.kind == Sym.Kind.METHOD) {
                methods.put(s.owner.names[moj] + "." + s.names[moj] + mojDesc(data, s), s);
            }
        }
        for (JsonNode cls : root.path("classes")) {
            String cname = cls.path("name").asString();
            for (JsonNode m : cls.path("methods")) {
                Sym s = methods.get(cname + "." + m.path("name").asString() + m.path("descriptor").asString());
                if (s == null || !m.has("parameters")) {
                    continue;
                }
                List<Sym.Param> list = new ArrayList<>();
                for (JsonNode p : m.get("parameters")) {
                    if (p.has("name")) {
                        list.add(new Sym.Param(p.path("index").asInt(), p.path("name").asString(), "parchment"));
                    }
                }
                list.sort((a, b) -> Integer.compare(a.index(), b.index()));
                s.params = List.copyOf(list);
            }
        }
    }

    private static String mojDesc(VersionData data, Sym s) {
        // canonical of a parchment version is mojmap
        return data.canonical().equals("mojmap") ? s.desc : s.obfDesc;
    }
}
