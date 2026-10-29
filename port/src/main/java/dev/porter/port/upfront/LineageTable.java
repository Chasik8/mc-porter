package dev.porter.port.upfront;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lineage of every symbol of the first version of a chain through all later versions, composed hop by hop. Every hop's
 * status is recorded and the confidence is the product; the walk of a symbol stops at the first hop that removed it
 * ({@code removed@<pair>}) or has no edge for its current key ({@code nokey@<pair>}). (The graph rule generator keeps
 * the worst status of the chain instead; this table needs the per-hop statuses.) Edge files are streamed and only one
 * hop's edges are in memory at a time.
 */
public final class LineageTable {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LineageTable() {
    }

    /** One symbol of the first version: kind class|method|field, owner (a class: its own name), name, desc. */
    record Key(String kind, String owner, String name, String desc) {
        String id() {
            return kind + '\t' + owner + '\t' + name + '\t' + desc;
        }
    }

    /** Target of one edge; {@code to == null} for a removed symbol. */
    record Edge(Key to, String status, double confidence) {
    }

    /** One output row. */
    public record Row(Key from, Key to, List<String> statuses, double confidence, String brokenAt) {
        String line() {
            Key t = to == null ? new Key("", "", "", "") : to;
            return String.join("\t", from.kind(), from.owner(), from.name(), from.desc(), t.owner(), t.name(), t.desc(),
                    String.join(",", statuses), String.format(java.util.Locale.ROOT, "%.3f", confidence), brokenAt);
        }
    }

    /** Streams one edge file: from-key id to edge (the first edge of a split/merged symbol wins), in file order. */
    static Map<String, Edge> readEdges(Path file, List<Key> order) {
        Map<String, Edge> m = new HashMap<>();
        try (InputStream in = Files.newInputStream(file); JsonParser p = MAPPER.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("not a JSON object: " + file);
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String f = p.currentName();
                JsonToken v = p.nextToken();
                if (!"edges".equals(f) || v != JsonToken.START_ARRAY) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    readEdge(p, m, order);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return m;
    }

    private static void readEdge(JsonParser p, Map<String, Edge> m, List<Key> order) throws IOException {
        Key from = null;
        Key to = null;
        String status = "";
        double conf = 0.0;
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String f = p.currentName();
            JsonToken v = p.nextToken();
            switch (f) {
                case "from" -> from = readKey(p);
                case "to" -> to = readKey(p);
                case "status" -> status = p.getString();
                case "confidence" -> conf = p.getDoubleValue();
                default -> {
                    if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                        p.skipChildren();
                    }
                }
            }
        }
        if (from == null) {
            return;
        }
        if (m.putIfAbsent(from.id(), new Edge(to, status, conf)) == null && order != null) {
            order.add(from);
        }
    }

    private static Key readKey(JsonParser p) throws IOException {
        String kind = "";
        String owner = "";
        String name = "";
        String desc = "";
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String f = p.currentName();
            p.nextToken();
            switch (f) {
                case "kind" -> kind = p.getString();
                case "owner" -> owner = p.getString();
                case "name" -> name = p.getString();
                case "desc" -> desc = p.getString();
                default -> p.skipChildren();
            }
        }
        // a class is keyed by its own name in the owner column
        return "class".equals(kind) ? new Key(kind, name, "", "") : new Key(kind, owner, name, desc);
    }

    /**
     * Composes the chain. {@code edgeFiles} are in version order (1.19.2 to 1.18.2, 1.18.2 to 1.16.5, ...); {@code pairs}
     * name them in the {@code brokenAt} column. Rows are in the order of the first file.
     */
    public static List<Row> compose(List<Path> edgeFiles, List<String> pairs) {
        List<Key> order = new ArrayList<>();
        Map<String, Edge> first = readEdges(edgeFiles.get(0), order);
        int n = order.size();
        Key[] cur = new Key[n];
        List<List<String>> sts = new ArrayList<>(n);
        double[] conf = new double[n];
        String[] broken = new String[n];
        for (int i = 0; i < n; i++) {
            sts.add(new ArrayList<>());
            conf[i] = 1.0;
            broken[i] = "";
        }
        Map<String, Edge> hop = first;
        for (int h = 0; h < edgeFiles.size(); h++) {
            if (h > 0) {
                hop = readEdges(edgeFiles.get(h), null);
            }
            for (int i = 0; i < n; i++) {
                if (!broken[i].isEmpty()) {
                    continue;
                }
                Key k = h == 0 ? order.get(i) : cur[i];
                Edge e = hop.get(k.id());
                if (e == null) {
                    broken[i] = "nokey@" + pairs.get(h);
                    cur[i] = null;
                    continue;
                }
                sts.get(i).add(e.status());
                conf[i] *= e.confidence();
                if (e.to() == null) {
                    broken[i] = "removed@" + pairs.get(h);
                    cur[i] = null;
                    continue;
                }
                cur[i] = e.to();
            }
            hop = null;
        }
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(order.get(i), cur[i], sts.get(i), conf[i], broken[i]));
        }
        return rows;
    }

    public static void write(Path out, List<Row> rows) {
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Row r : rows) {
                w.write(r.line());
                w.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + out, e);
        }
    }
}
