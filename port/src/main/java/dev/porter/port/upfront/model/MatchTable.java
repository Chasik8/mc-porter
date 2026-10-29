package dev.porter.port.upfront.model;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The central match table (spec section 2.6). Written as a TSV (one row per symbol, the columns below) and as
 * JSON ({@code contracts/upfront-match-table.schema.json}, rows also carry features and alternatives).
 *
 * <p>Both writers sort rows by area (null first), owner, name, descriptor and kind, so runs are diffable. Null
 * text cells are written as empty cells and read back as null; tabs and newlines in text are written as spaces.
 */
public record MatchTable(int schemaVersion, List<MatchRow> rows) {

    public static final int SCHEMA_VERSION = 1;

    /** TSV columns, in order. */
    public static final List<String> TSV_COLUMNS = List.of(
            "key", "kind", "uses", "area", "resolvedBy", "plan", "method", "confidence",
            "t_owner", "t_name", "t_desc", "t_static", "shim", "ruleId", "note");

    public MatchTable {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** Rows in the deterministic write order. */
    public List<MatchRow> sortedRows() {
        return rows.stream().sorted(ORDER).toList();
    }

    private static final Comparator<MatchRow> ORDER = Comparator
            .comparing(MatchRow::area, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
            .thenComparing(r -> r.key().owner())
            .thenComparing(r -> r.key().name())
            .thenComparing(r -> r.key().desc())
            .thenComparing(r -> r.key().kind());

    public static MatchTable read(Path json) throws IOException {
        return Json.json().readValue(Files.readString(json, StandardCharsets.UTF_8), MatchTable.class);
    }

    /** Writes the JSON document (sorted rows, schemaVersion first). */
    public void writeJson(Path json) throws IOException {
        MatchTable out = new MatchTable(SCHEMA_VERSION, sortedRows());
        Files.writeString(json, Json.json().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
    }

    /** Writes the TSV (header line, then one line per sorted row). */
    public void writeTsv(Path tsv) throws IOException {
        Files.writeString(tsv, toTsv(), StandardCharsets.UTF_8);
    }

    /** Writes both files. */
    public void write(Path tsv, Path json) throws IOException {
        writeTsv(tsv);
        writeJson(json);
    }

    public String toTsv() {
        StringBuilder sb = new StringBuilder(String.join("\t", TSV_COLUMNS)).append('\n');
        for (MatchRow r : sortedRows()) {
            Target t = r.target();
            List<String> cells = new ArrayList<>(List.of(
                    r.key().toString(),
                    r.kind().name(),
                    Integer.toString(r.uses()),
                    cell(r.area()),
                    cell(r.resolvedBy()),
                    r.plan().name(),
                    cell(r.method()),
                    Double.toString(r.confidence()),
                    t == null ? "" : cell(t.owner()),
                    t == null ? "" : cell(t.name()),
                    t == null ? "" : cell(t.desc()),
                    t == null ? "" : Boolean.toString(t.isStatic()),
                    cell(r.shim()),
                    cell(r.ruleId()),
                    cell(r.note())));
            sb.append(String.join("\t", cells)).append('\n');
        }
        return sb.toString();
    }

    /** Reads a TSV written by {@link #writeTsv}. Features and alternatives are not in the TSV and come back empty. */
    public static MatchTable readTsv(Path tsv) throws IOException {
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(String.join("\t", TSV_COLUMNS))) {
            throw new IllegalArgumentException(tsv + ": not a match table (bad header)");
        }
        List<MatchRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length != TSV_COLUMNS.size()) {
                throw new IllegalArgumentException(tsv + ":" + (i + 1) + ": expected " + TSV_COLUMNS.size()
                        + " columns, found " + c.length);
            }
            SymbolKey key = SymbolKey.parse(c[0]);
            if (!key.kind().name().equals(c[1])) {
                throw new IllegalArgumentException(tsv + ":" + (i + 1) + ": kind " + c[1] + " does not match key");
            }
            Target target = c[8].isEmpty() && c[9].isEmpty() && c[10].isEmpty() && c[11].isEmpty()
                    ? null
                    : new Target(c[8], c[9], c[10], Boolean.parseBoolean(c[11]));
            rows.add(new MatchRow(key, Integer.parseInt(c[2]), nul(c[3]), nul(c[4]), Plan.valueOf(c[5]),
                    nul(c[6]), Double.parseDouble(c[7]), target, nul(c[12]), nul(c[13]), null, null, nul(c[14])));
        }
        return new MatchTable(SCHEMA_VERSION, rows);
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String nul(String s) {
        return s.isEmpty() ? null : s;
    }
}
