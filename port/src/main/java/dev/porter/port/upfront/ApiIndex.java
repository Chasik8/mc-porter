package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.start.TypeTable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import tools.jackson.databind.JsonNode;

/**
 * The cached per-version-pair indexes of the upfront translation layer: lineage, declared API of the target jar,
 * source class hierarchy, target srg-to-mcp names and a manifest of the input hashes (the index is rebuilt when they
 * change). Files: {@code lineage.tsv}, {@code target.tsv}, {@code source-hier.tsv}, {@code srg-1.7.10.tsv},
 * {@code manifest.json}.
 */
public final class ApiIndex {
    public static final List<String> PAIRS = List.of("1.19.2__1.18.2", "1.18.2__1.16.5", "1.16.5__1.12.2", "1.12.2__1.7.10");
    static final String[] FILES = {"lineage.tsv", "target.tsv", "source-hier.tsv", "srg-1.7.10.tsv"};

    /** Inputs of one build. */
    public record Inputs(Path graphDir, Path targetJar, List<Path> sourceJars, Path tiny, List<String> sourcePrefixes) {
    }

    /** Counts of one build (for the log and the acceptance check). */
    public record Stats(int lineageRows, int classes, int members, int sourceClasses, int srgNames, boolean cached) {
    }

    private ApiIndex() {
    }

    public static Path edgeFile(Path graphDir, String pair) {
        return graphDir.resolve("edges-" + pair + ".graph.json");
    }

    /** Builds the index into {@code out}, or reports a cache hit when the manifest matches and all files exist. */
    public static Stats build(Inputs in, Path out, boolean force) {
        List<Path> edges = new ArrayList<>();
        for (String p : PAIRS) {
            Path f = edgeFile(in.graphDir(), p);
            if (!Files.isRegularFile(f)) {
                throw new IllegalArgumentException("missing graph file " + f + " - run `porter graph build` for 1.19.2, 1.18.2, "
                        + "1.16.5, 1.12.2 and 1.7.10 first (or point --graph at its output)");
            }
            edges.add(f);
        }
        List<Path> others = concat(List.of(in.targetJar(), in.tiny()), in.sourceJars());
        for (Path f : others) {
            if (!Files.isRegularFile(f)) {
                throw new IllegalArgumentException("missing input " + f);
            }
        }
        Map<String, String> hashes = new TreeMap<>();
        for (Path f : concat(edges, others)) {
            hashes.put(f.getFileName().toString(), sha256(f));
        }
        Path manifest = out.resolve("manifest.json");
        if (!force && Files.isRegularFile(manifest) && allExist(out)) {
            try {
                JsonNode m = Json.json().readTree(manifest.toFile());
                Map<String, String> old = new TreeMap<>();
                m.path("inputs").properties().forEach(e -> old.put(e.getKey(), e.getValue().asString()));
                if (old.equals(hashes)) {
                    JsonNode c = m.path("counts");
                    return new Stats(c.path("lineageRows").asInt(), c.path("classes").asInt(), c.path("members").asInt(),
                            c.path("sourceClasses").asInt(), c.path("srgNames").asInt(), true);
                }
            } catch (RuntimeException e) {
                // unreadable manifest: rebuild
            }
        }
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<LineageTable.Row> rows = LineageTable.compose(edges, PAIRS);
        LineageTable.write(out.resolve("lineage.tsv"), rows);
        int[] tm = writeTarget(in.targetJar(), out.resolve("target.tsv"));
        int sc = TypeTable.write(out.resolve("source-hier.tsv"), in.sourceJars(), in.sourcePrefixes());
        int srg = writeSrg(in.tiny(), out.resolve("srg-1.7.10.tsv"));
        Stats s = new Stats(rows.size(), tm[0], tm[1], sc, srg, false);
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("schemaVersion", 1);
        mf.put("pair", "1.19.2__1.7.10");
        mf.put("inputs", hashes);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("lineageRows", s.lineageRows());
        counts.put("classes", s.classes());
        counts.put("members", s.members());
        counts.put("sourceClasses", s.sourceClasses());
        counts.put("srgNames", s.srgNames());
        mf.put("counts", counts);
        Json.json().writeValue(manifest.toFile(), mf);
        return s;
    }

    private static boolean allExist(Path out) {
        for (String f : FILES) {
            if (!Files.isRegularFile(out.resolve(f))) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    static String sha256(Path f) {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash " + f, e);
        }
    }

    /**
     * Declared API of a jar: {@code class <fqn> <super> <itf,itf> <access>} then {@code member <owner> method|field <name>
     * <desc> <access>} (fields before methods, classes sorted by name). Returns {classes, members}.
     */
    static int[] writeTarget(Path jar, Path out) {
        TreeMap<String, List<String>> byClass = new TreeMap<>();
        int[] count = new int[2];
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/") || e.getName().endsWith("module-info.class")) {
                    continue;
                }
                byte[] b;
                try (InputStream in = z.getInputStream(e)) {
                    b = in.readAllBytes();
                }
                List<String> fields = new ArrayList<>();
                List<String> methods = new ArrayList<>();
                String[] head = new String[2];
                new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                        head[0] = n.replace('/', '.');
                        head[1] = "class\t" + head[0] + "\t" + (sup == null ? "" : sup.replace('/', '.')) + "\t"
                                + (itf == null ? "" : String.join(",", Arrays.stream(itf).map(s -> s.replace('/', '.')).toList()))
                                + "\t" + access;
                    }

                    @Override
                    public FieldVisitor visitField(int access, String n, String desc, String sig, Object v) {
                        fields.add("member\t" + head[0] + "\tfield\t" + n + "\t" + desc + "\t" + access);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String n, String desc, String sig, String[] ex) {
                        methods.add("member\t" + head[0] + "\tmethod\t" + n + "\t" + desc + "\t" + access);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                List<String> lines = new ArrayList<>();
                lines.add(head[1]);
                lines.addAll(fields);
                lines.addAll(methods);
                if (byClass.putIfAbsent(head[0], lines) == null) {
                    count[0]++;
                    count[1] += lines.size() - 1;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (List<String> l : byClass.values()) {
                for (String s : l) {
                    w.write(s);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + out, e);
        }
        return count;
    }

    /** srg to mcp names of the methods and fields of a tiny v2 file ({@code srg mcp}, sorted, deduplicated). */
    static int writeSrg(Path tiny, Path out) {
        TreeMap<String, String> m = new TreeMap<>();
        try (var lines = Files.lines(tiny, StandardCharsets.UTF_8)) {
            int srgCol = -1;
            int mcpCol = -1;
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("tiny\t")) {
                    List<String> h = List.of(line.split("\t"));
                    srgCol = h.indexOf("srg") - 3;
                    mcpCol = h.indexOf("mcp") - 3;
                    if (srgCol < 0 || mcpCol < 0) {
                        throw new IllegalArgumentException(tiny + ": header has no srg and mcp namespaces");
                    }
                    continue;
                }
                if (!line.startsWith("\tm\t") && !line.startsWith("\tf\t")) {
                    continue;
                }
                // "", m|f, desc, ns0, ns1, ...
                String[] c = line.split("\t");
                int base = 3;
                if (c.length > base + Math.max(srgCol, mcpCol)) {
                    String srg = c[base + srgCol];
                    String mcp = c[base + mcpCol];
                    if (!srg.isEmpty() && !mcp.isEmpty() && (srg.startsWith("func_") || srg.startsWith("field_"))) {
                        m.putIfAbsent(srg, mcp);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + tiny, e);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (var e : m.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + out, e);
        }
        return m.size();
    }
}
