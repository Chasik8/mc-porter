package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The bodies DB (spec 2.7): {@code bodies/<area>-<n>.yaml}, each file one or more YAML documents separated by
 * {@code ---}, one document per key. A key may occur only once in the whole directory.
 */
public final class BodiesDb {
    private final Map<SymbolKey, BodyEntry> entries;

    public BodiesDb(Map<SymbolKey, BodyEntry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static BodiesDb empty() {
        return new BodiesDb(Map.of());
    }

    public BodyEntry get(SymbolKey key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public Iterable<BodyEntry> entries() {
        return entries.values();
    }

    /** Loads every {@code *.yaml} in the directory (not recursive; a missing directory is an empty DB). */
    public static BodiesDb load(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return empty();
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        Map<SymbolKey, BodyEntry> out = new LinkedHashMap<>();
        Map<SymbolKey, Path> from = new LinkedHashMap<>();
        for (Path f : files) {
            for (BodyEntry e : parse(Files.readString(f, StandardCharsets.UTF_8), f.toString())) {
                if (out.put(e.key(), e) != null) {
                    throw new IllegalArgumentException("duplicate body key " + e.key() + " in " + f + " and " + from.get(e.key()));
                }
                from.put(e.key(), f);
            }
        }
        return new BodiesDb(out);
    }

    /** Parses a multi-document YAML text. */
    @SuppressWarnings("unchecked")
    public static List<BodyEntry> parse(String text, String origin) {
        List<BodyEntry> list = new ArrayList<>();
        List<String> doc = new ArrayList<>();
        for (String line : (text + "\n---\n").split("\n", -1)) {
            if (line.equals("---") || line.startsWith("--- ")) {
                String d = String.join("\n", doc);
                doc.clear();
                if (stripComments(d).isBlank()) {
                    continue;
                }
                Map<String, Object> m = Json.yaml().readValue(d, Map.class);
                Object key = m.get("key");
                if (key == null) {
                    throw new IllegalArgumentException(origin + ": document without key");
                }
                List<String> imports = new ArrayList<>();
                if (m.get("imports") instanceof List<?> l) {
                    for (Object o : l) {
                        imports.add(String.valueOf(o));
                    }
                }
                Object body = m.get("body");
                Object verified = m.get("verified");
                list.add(new BodyEntry(SymbolKey.parse(key.toString()), body == null ? "" : body.toString(), imports,
                        verified == null ? null : verified.toString(), m.get("set-body") == null ? "" : m.get("set-body").toString()));
            } else {
                doc.add(line);
            }
        }
        return list;
    }

    private static String stripComments(String d) {
        StringBuilder sb = new StringBuilder();
        for (String l : d.split("\n")) {
            if (!l.trim().startsWith("#")) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** Renders one entry as a YAML document (body as a block scalar), for writers such as the fill loop. */
    public static String render(BodyEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("key: ").append(quote(e.key().toString())).append('\n');
        sb.append("body: |-\n");
        for (String l : e.body().split("\n", -1)) {
            sb.append("  ").append(l).append('\n');
        }
        if (!e.setBody().isBlank()) {
            sb.append("set-body: |-\n");
            for (String l : e.setBody().split("\n", -1)) {
                sb.append("  ").append(l).append('\n');
            }
        }
        sb.append("imports: [").append(String.join(", ", e.imports())).append("]\n");
        sb.append("verified: ").append(e.verified()).append('\n');
        return sb.toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
