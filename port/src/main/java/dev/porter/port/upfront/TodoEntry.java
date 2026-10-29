package dev.porter.port.upfront;

import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One line of {@code todo.tsv} (spec 2.7), written by shimgen and read by the TODO batch: a generated member whose
 * body is still a typed TODO. Columns: {@code key, owner, signature, plan, hint, alternatives, uses, area}.
 * {@code alternatives} is {@code owner#name desc score} items joined by {@code " | "}; {@code hint} is free text
 * (a lineage target, a collision note); both may be empty. Rows are sorted by area (empty first), owner, key.
 */
public record TodoEntry(SymbolKey key, String owner, String signature, Plan plan, String hint,
                        List<Alt> alternatives, int uses, String area) {

    public static final List<String> COLUMNS = List.of("key", "owner", "signature", "plan", "hint", "alternatives", "uses", "area");

    public TodoEntry {
        hint = hint == null ? "" : hint;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        area = area == null ? "" : area;
    }

    private static final Comparator<TodoEntry> ORDER = Comparator.comparing(TodoEntry::area)
            .thenComparing(TodoEntry::owner)
            .thenComparing(e -> e.key().toString());

    public static String toTsv(List<TodoEntry> entries) {
        StringBuilder sb = new StringBuilder(String.join("\t", COLUMNS)).append('\n');
        for (TodoEntry e : entries.stream().sorted(ORDER).toList()) {
            List<String> alts = new ArrayList<>();
            for (Alt a : e.alternatives()) {
                alts.add(a.owner() + "#" + a.name() + " " + a.desc() + " " + a.score());
            }
            sb.append(String.join("\t", List.of(clean(e.key().toString()), clean(e.owner()), clean(e.signature()),
                    e.plan().name(), clean(e.hint()), clean(String.join(" | ", alts)), Integer.toString(e.uses()),
                    clean(e.area())))).append('\n');
        }
        return sb.toString();
    }

    public static void write(Path file, List<TodoEntry> entries) throws IOException {
        Files.writeString(file, toTsv(entries), StandardCharsets.UTF_8);
    }

    /** Reads a todo.tsv; alternatives are parsed back. */
    public static List<TodoEntry> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(String.join("\t", COLUMNS))) {
            throw new IllegalArgumentException(file + ": not a todo.tsv (bad header)");
        }
        List<TodoEntry> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                continue;
            }
            String[] c = lines.get(i).split("\t", -1);
            if (c.length != COLUMNS.size()) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": expected " + COLUMNS.size() + " columns");
            }
            List<Alt> alts = new ArrayList<>();
            if (!c[5].isEmpty()) {
                for (String a : c[5].split(" \\| ")) {
                    int h = a.indexOf('#');
                    String[] p = a.substring(h + 1).split(" ");
                    alts.add(new Alt(a.substring(0, h), p[0], p[1], Double.parseDouble(p[2])));
                }
            }
            out.add(new TodoEntry(SymbolKey.parse(c[0]), c[1], c[2], Plan.valueOf(c[3]), c[4], alts,
                    Integer.parseInt(c[6]), c[7]));
        }
        return out;
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
