package dev.porter.graph.io;

import dev.porter.graph.model.Edge;
import dev.porter.graph.model.Sym;
import dev.porter.graph.model.VersionData;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layout of a built graph directory. Every {@code *.graph.json} is a complete, contract-valid symbol-graph document
 * ({@code type: graph}):
 * <ul>
 *   <li>{@code symbols-<v>.graph.json}: one version, all its symbols with names in every namespace, no edges;</li>
 *   <li>{@code edges-<newer>__<older>.graph.json}: two adjacent versions, the edges newer -> older;</li>
 *   <li>{@code manifest.json}: build metadata and the version chain (not a contract);</li>
 *   <li>{@code mappings/<v>.tiny}: the merged mappings of each version (Tiny v2, namespaces obf/mojmap/srg/mcp).</li>
 * </ul>
 * Files are written compactly (one line) with a stable order: symbols in jar order, edges in symbol order.
 */
public final class GraphFiles {
    public static final JsonMapper COMPACT = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private GraphFiles() {
    }

    public static Path symbolsFile(Path dir, String version) {
        return dir.resolve("symbols-" + version + ".graph.json");
    }

    public static Path edgesFile(Path dir, String newer, String older) {
        return dir.resolve("edges-" + newer + "__" + older + ".graph.json");
    }

    /** Not a contract: {@code class TAB superclass TAB interface,interface} in canonical binary names, jar order. */
    public static Path hierarchyFile(Path dir, String version) {
        return dir.resolve("hierarchy-" + version + ".tsv");
    }

    public static void writeHierarchy(Path file, VersionData v) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Sym c : v.classes()) {
            sb.append(c.canonicalName(v)).append('\t').append(name(v, c.superObf)).append('\t');
            for (int i = 0; i < c.interfacesObf.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(name(v, c.interfacesObf.get(i)));
            }
            sb.append('\n');
        }
        Files.writeString(file, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String name(VersionData v, String obf) {
        if (obf == null) {
            return "";
        }
        Sym s = v.classByObf(obf);
        return s != null ? s.canonicalName(v) : obf.replace('/', '.');
    }

    public static Path manifestFile(Path dir) {
        return dir.resolve("manifest.json");
    }

    public static Map<String, Object> versionJson(VersionData v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("canonical", v.canonical());
        m.put("namespaces", v.namespaces());
        List<Map<String, Object>> src = new ArrayList<>();
        for (VersionData.Source s : v.sources()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", s.name());
            x.put("version", s.version());
            x.put("url", s.url());
            x.put("sha256", s.sha256());
            src.add(x);
        }
        m.put("sources", src);
        return m;
    }

    public static Map<String, Object> symbolJson(VersionData v, Sym s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", s.ref(v).toJson());
        Map<String, Object> names = new LinkedHashMap<>();
        for (int i = 0; i < v.namespaces().size(); i++) {
            String n = s.names[i];
            names.put(v.namespaces().get(i), s.isClass() ? n.replace('/', '.') : n);
        }
        m.put("names", names);
        if (!s.params.isEmpty()) {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (Sym.Param p : s.params) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("index", p.index());
                x.put("name", p.name());
                x.put("source", p.source());
                ps.add(x);
            }
            m.put("params", ps);
        }
        m.put("access", s.access);
        return m;
    }

    public static void writeSymbols(Path file, VersionData v) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
             JsonGenerator g = COMPACT.createGenerator(out)) {
            g.writeStartObject();
            g.writeNumberProperty("schemaVersion", 1);
            g.writeStringProperty("type", "graph");
            g.writeName("versions");
            g.writePOJO(List.of(versionJson(v)));
            g.writeName("symbols");
            g.writeStartArray();
            for (Sym s : v.symbols()) {
                g.writePOJO(symbolJson(v, s));
            }
            g.writeEndArray();
            g.writeName("edges");
            g.writeStartArray();
            g.writeEndArray();
            g.writeEndObject();
        }
        appendNewline(file);
    }

    public static void writeEdges(Path file, VersionData a, VersionData b, List<Edge> edges) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
             JsonGenerator g = COMPACT.createGenerator(out)) {
            g.writeStartObject();
            g.writeNumberProperty("schemaVersion", 1);
            g.writeStringProperty("type", "graph");
            g.writeName("versions");
            g.writePOJO(List.of(versionJson(a), versionJson(b)));
            g.writeName("edges");
            g.writeStartArray();
            for (Edge e : edges) {
                g.writePOJO(e.toJson());
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        appendNewline(file);
    }

    private static void appendNewline(Path file) throws IOException {
        Files.write(file, new byte[] {'\n'}, java.nio.file.StandardOpenOption.APPEND);
    }
}
