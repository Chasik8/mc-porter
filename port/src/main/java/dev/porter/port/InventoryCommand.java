package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

/**
 * {@code porter inventory}: what a port profile ships, file by file, and how much of its text comes from the original
 * mod. Overlap = share of a file's token 8-grams (package/import lines and comments ignored, relocated packages mapped
 * back) that also occur in the reference sources (the original's decompiles); for unified diffs only the added lines
 * count, the only text they carry. Lang files: values identical to a reference lang text (longer than 12 characters).
 * Categories come from {@code --category <prefix>=<name>} (first match; default "D docs / other"). Deterministic: the
 * basis of a publishing check ({@code porter verify}).
 */
@Command(name = "inventory", description = {"What a port profile ships and how much of its text comes from the original mod (token 8-gram overlap).",
    "Writes a TSV (and a Markdown table) per file: category, bytes, lines (diffs: added lines), name references, overlap, identical lang texts."})
public final class InventoryCommand implements Callable<Integer>, PorterSubcommand {
    static final Pattern TOK = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|\\d+|\"(?:\\\\.|[^\"\\\\])*\"|\\S");
    static final int N = 8;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--root", required = true, paramLabel = "<dir>", description = "The profile directory.")
    Path root;

    @Option(names = "--ref", required = true, paramLabel = "<dir>", description = "Reference sources (Java files read recursively). Repeatable.")
    List<Path> refs;

    @Option(names = "--lang-ref", paramLabel = "<dir>", description = "Reference lang files (*.json / *.lang). Repeatable.")
    List<Path> langRefs = new ArrayList<>();

    @Option(names = "--category", paramLabel = "<prefix>=<name>", description = "Category of the files under a path prefix (first match). Repeatable.")
    List<String> categories = new ArrayList<>();

    @Option(names = "--relocate", paramLabel = "<from>=<to>", description = "Package moves of the port (mapped back before comparing). Repeatable.")
    List<String> relocations = new ArrayList<>();

    @Option(names = "--names", paramLabel = "<regex>", description = "Regex counted per file as references to the original's names.")
    String names;

    @Option(names = "--exclude", paramLabel = "<prefix>", description = "Path prefixes not inventoried (e.g. run/). Repeatable.")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--out", required = true, paramLabel = "<file.tsv>")
    Path out;

    @Option(names = "--markdown", paramLabel = "<file.md>", description = "Also write the totals and the per-file table as Markdown (after --head).")
    Path markdown;

    @Option(names = "--head", paramLabel = "<file.md>", description = "Markdown placed before the generated tables.")
    Path head;

    @Override
    public String path() {
        return "inventory";
    }

    record Row(String category, String path, long bytes, int lines, int names, String overlap, String lang) {
    }

    static List<String> tokens(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").replaceAll("(?m)^\\s*(package|import) .*$", "");
        List<String> t = new ArrayList<>();
        Matcher m = TOK.matcher(s);
        while (m.find()) {
            t.add(m.group());
        }
        return t;
    }

    static Set<Long> shingles(List<String> t) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i + N <= t.size(); i++) {
            CRC32 c = new CRC32();
            c.update(String.join("\t", t.subList(i, i + N)).getBytes(StandardCharsets.UTF_8));
            out.add(c.getValue());
        }
        return out;
    }

    /** Share of the text's 8-grams found in the reference; empty text = 0. */
    static double overlap(String text, Set<Long> ref) {
        Set<Long> a = shingles(tokens(text));
        if (a.isEmpty()) {
            return 0;
        }
        long hit = a.stream().filter(ref::contains).count();
        return (double) hit / a.size();
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("inventory", spec.commandLine().getParseResult().originalArgs());
        Set<Long> ref = new HashSet<>();
        for (Path r : refs) {
            for (Path f : files(r)) {
                if (f.toString().endsWith(".java")) {
                    ref.addAll(shingles(tokens(Text.read(f))));
                }
            }
        }
        Set<String> langVals = new HashSet<>();
        for (Path r : langRefs) {
            for (Path f : files(r)) {
                String n = f.toString();
                if (n.endsWith(".json")) {
                    JsonNode j = Json.json().readTree(f.toFile());
                    j.properties().forEach(e -> {
                        String v = e.getValue().asString("");
                        if (v.length() > 12) {
                            langVals.add(v.strip());
                        }
                    });
                } else if (n.endsWith(".lang")) {
                    for (String l : Text.read(f).split("\n")) {
                        int eq = l.indexOf('=');
                        if (eq > 0 && l.length() - eq - 1 > 12) {
                            langVals.add(l.substring(eq + 1).strip());
                        }
                    }
                }
            }
        }
        Pattern namePat = names == null ? null : Pattern.compile(names);
        List<Row> rows = new ArrayList<>();
        for (Path f : files(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (excludes.stream().anyMatch(rel::startsWith)) {
                continue;
            }
            byte[] b;
            try {
                b = Files.readAllBytes(f);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            boolean binary = rel.endsWith(".png") || rel.endsWith(".jar") || indexOfZero(b) >= 0;
            String text = binary ? "" : new String(b, StandardCharsets.UTF_8);
            int lines = binary ? 0 : text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
            int nameRefs = 0;
            if (namePat != null && !binary) {
                Matcher m = namePat.matcher(text);
                while (m.find()) {
                    nameRefs++;
                }
            }
            String ov = "";
            if (rel.endsWith(".java")) {
                ov = String.format(java.util.Locale.ROOT, "%.2f", overlap(unrelocate(text), ref));
            } else if (rel.endsWith(".patch")) {
                StringBuilder added = new StringBuilder();
                int n = 0;
                for (String l : text.split("\n")) {
                    if (l.startsWith("+") && !l.startsWith("+++")) {
                        added.append(l.substring(1)).append('\n');
                        n++;
                    }
                }
                lines = n;
                ov = String.format(java.util.Locale.ROOT, "%.2f", overlap(unrelocate(added.toString()), ref));
            }
            String lang = "";
            if (rel.endsWith(".lang") && !binary) {
                int all = 0;
                int same = 0;
                for (String l : text.split("\n")) {
                    int eq = l.indexOf('=');
                    if (eq > 0 && !l.startsWith("#")) {
                        all++;
                        if (langVals.contains(l.substring(eq + 1).strip())) {
                            same++;
                        }
                    }
                }
                lang = same + "/" + all;
            }
            rows.add(new Row(category(rel), rel, b.length, lines, nameRefs, ov, lang));
        }
        rows.sort((x, y) -> x.category().equals(y.category()) ? x.path().compareTo(y.path()) : x.category().compareTo(y.category()));
        StringBuilder tsv = new StringBuilder("category\tpath\tbytes\tlines\tnames\toverlap\tlang_identical\n");
        Map<String, long[]> sums = new LinkedHashMap<>();
        for (Row r : rows) {
            tsv.append(String.join("\t", r.category(), r.path(), Long.toString(r.bytes()), Integer.toString(r.lines()),
                    Integer.toString(r.names()), r.overlap(), r.lang())).append('\n');
            long[] s = sums.computeIfAbsent(r.category(), k -> new long[3]);
            s[0]++;
            s[1] += r.lines();
            s[2] += r.bytes();
        }
        Text.write(out, tsv.toString());
        if (markdown != null) {
            StringBuilder md = new StringBuilder(head == null ? "" : Text.read(head));
            md.append("\n## Totals by category (generated by `porter inventory`)\n\n| Category | Files | Lines (patches: added lines) | Bytes |\n|---|---:|---:|---:|\n");
            sums.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ").append(v[2]).append(" |\n"));
            md.append("\n## Every file (generated)\n\nColumns: lines (for patches: the added lines, the only text they carry), names = references to the "
                    + "original's names (`--names`), overlap = share of the file's token 8-grams that also occur in the reference "
                    + "decompiles (Java and patches only; package/import lines and comments ignored; ~0.05-0.15 is the baseline of "
                    + "ordinary Forge/Java boilerplate), lang = values identical to a reference lang text longer than 12 characters / entries.\n\n"
                    + "| Category | Path | Bytes | Lines | Names | Overlap | Lang |\n|---|---|---:|---:|---:|---:|---|\n");
            for (Row r : rows) {
                md.append("| ").append(r.category().split(" ")[0]).append(" | `").append(r.path()).append("` | ").append(r.bytes()).append(" | ")
                        .append(r.lines()).append(" | ").append(r.names()).append(" | ").append(r.overlap()).append(" | ").append(r.lang()).append(" |\n");
            }
            Text.write(markdown, md.toString());
        }
        sums.forEach((k, v) -> spec.commandLine().getOut().printf("%-55s files %4d  lines %6d  bytes %8d%n", k, v[0], v[1], v[2]));
        Map<String, Object> totals = new LinkedHashMap<>();
        sums.forEach((k, v) -> totals.put(k, Map.of("files", v[0], "lines", v[1], "bytes", v[2])));
        report.put("totals", totals).put("rows", rows.size()).write(RunReport.STATUS_OK, ExitCodes.OK);
        return ExitCodes.OK;
    }

    private String category(String rel) {
        for (String c : categories) {
            int eq = c.indexOf('=');
            if (eq > 0 && (rel.startsWith(c.substring(0, eq)) || rel.equals(c.substring(0, eq)))) {
                return c.substring(eq + 1);
            }
        }
        return "D docs / other";
    }

    private String unrelocate(String text) {
        for (String r : relocations) {
            int eq = r.indexOf('=');
            if (eq > 0) {
                text = text.replace(r.substring(eq + 1), r.substring(0, eq));
            }
        }
        return text;
    }

    private static int indexOfZero(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 4096); i++) {
            if (b[i] == 0) {
                return i;
            }
        }
        return -1;
    }
}
