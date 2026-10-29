package dev.porter.port.upfront;

import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Splits the shimgen TODO list into one batch per fill worker (spec WP8). All entries of an owner go to the same
 * worker; owners are balanced by weight (TODO 1, HINT 0.5) with a preference for keeping an area on one worker.
 * Each entry carries the generated signature, hint, top-3 candidates, use count and up to two call-site lines of
 * the mod, searched only in the source files of the classes that reference the symbol (inventory {@code refClasses}).
 */
public final class TodoBatch {
    public static final double TODO_WEIGHT = 1.0;
    public static final double HINT_WEIGHT = 0.5;
    private static final int MAX_SITES = 2;
    private static final int MAX_LINE = 160;

    /** One worker's batch. */
    public record Batch(int worker, List<String> owners, List<Item> items, double weight, String area) {
    }

    /** One entry with its candidates and call sites. */
    public record Item(TodoEntry todo, List<Alt> candidates, List<String> sites) {
    }

    /** The whole split. */
    public record Result(List<Batch> batches) {
        public double maxWeight() {
            return batches.stream().mapToDouble(Batch::weight).max().orElse(0);
        }

        public double minWeight() {
            return batches.stream().mapToDouble(Batch::weight).min().orElse(0);
        }
    }

    private record Group(String owner, String area, double weight, List<TodoEntry> entries) {
    }

    private TodoBatch() {
    }

    public static double weight(TodoEntry e) {
        return e.plan() == Plan.HINT ? HINT_WEIGHT : TODO_WEIGHT;
    }

    /**
     * @param table optional; its alternatives fill in entries whose todo row has none
     * @param inventory optional; together with {@code srcRoot} it supplies the call sites
     */
    public static Result split(List<TodoEntry> todos, int workers, MatchTable table, ApiInventory inventory, Path srcRoot) {
        if (workers < 1) {
            throw new IllegalArgumentException("--workers must be at least 1");
        }
        Set<SymbolKey> seen = new HashSet<>();
        for (TodoEntry e : todos) {
            if (!seen.add(e.key())) {
                throw new IllegalArgumentException("duplicate key in todo list: " + e.key());
            }
        }
        Map<SymbolKey, MatchRow> rows = new HashMap<>();
        if (table != null) {
            table.rows().forEach(r -> rows.put(r.key(), r));
        }
        Map<SymbolKey, ApiSymbol> syms = new HashMap<>();
        if (inventory != null) {
            inventory.symbols().forEach(s -> syms.put(s.key(), s));
        }
        CallSites sites = new CallSites(srcRoot);

        Map<String, List<TodoEntry>> byOwner = new TreeMap<>();
        for (TodoEntry e : todos) {
            byOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<TodoEntry>> g : byOwner.entrySet()) {
            double w = g.getValue().stream().mapToDouble(TodoBatch::weight).sum();
            groups.add(new Group(g.getKey(), mainArea(g.getValue()), w, g.getValue()));
        }
        groups.sort(Comparator.comparingDouble((Group g) -> -g.weight()).thenComparing(Group::owner));

        double total = groups.stream().mapToDouble(Group::weight).sum();
        double cap = total / workers * 1.15;
        double[] load = new double[workers];
        List<Set<String>> areas = new ArrayList<>();
        List<List<Group>> assigned = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            areas.add(new HashSet<>());
            assigned.add(new ArrayList<>());
        }
        for (Group g : groups) {
            int best = -1;
            if (!g.area().isEmpty()) {
                for (int i = 0; i < workers; i++) {
                    if (areas.get(i).contains(g.area()) && load[i] + g.weight() <= cap && (best < 0 || load[i] < load[best])) {
                        best = i;
                    }
                }
            }
            if (best < 0) {
                best = 0;
                for (int i = 1; i < workers; i++) {
                    if (load[i] < load[best]) {
                        best = i;
                    }
                }
            }
            load[best] += g.weight();
            areas.get(best).add(g.area());
            assigned.get(best).add(g);
        }

        // area affinity may leave the loads uneven (cap 1.15x the mean): move or swap whole owners between the heaviest and the
        // lightest batch while that narrows the gap between them (terminates; deterministic)
        for (int iter = 0; iter < 1000 && workers > 1; iter++) {
            int hi = 0;
            int lo = 0;
            for (int i = 1; i < workers; i++) {
                hi = load[i] > load[hi] ? i : hi;
                lo = load[i] < load[lo] ? i : lo;
            }
            double best = Math.abs(load[hi] - load[lo]);
            Group moveA = null;
            Group swapB = null;
            for (Group a : assigned.get(hi)) {
                double d = Math.abs(load[hi] - a.weight() - (load[lo] + a.weight()));
                if (d < best - 1e-9) {
                    best = d;
                    moveA = a;
                    swapB = null;
                }
                for (Group b : assigned.get(lo)) {
                    double s = Math.abs(load[hi] - a.weight() + b.weight() - (load[lo] + a.weight() - b.weight()));
                    if (s < best - 1e-9) {
                        best = s;
                        moveA = a;
                        swapB = b;
                    }
                }
            }
            if (moveA == null) {
                break;
            }
            assigned.get(hi).remove(moveA);
            assigned.get(lo).add(moveA);
            load[hi] -= moveA.weight();
            load[lo] += moveA.weight();
            if (swapB != null) {
                assigned.get(lo).remove(swapB);
                assigned.get(hi).add(swapB);
                load[lo] -= swapB.weight();
                load[hi] += swapB.weight();
            }
        }

        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            List<Group> mine = assigned.get(i);
            mine.sort(Comparator.comparing(Group::area).thenComparing(Group::owner));
            List<Item> items = new ArrayList<>();
            List<String> owners = new ArrayList<>();
            List<TodoEntry> all = new ArrayList<>();
            for (Group g : mine) {
                owners.add(g.owner());
                for (TodoEntry e : g.entries().stream().sorted(Comparator.comparing(x -> x.key().toString())).toList()) {
                    List<Alt> alts = e.alternatives();
                    MatchRow r = rows.get(e.key());
                    if (alts.isEmpty() && r != null) {
                        alts = r.alternatives();
                    }
                    ApiSymbol s = syms.get(e.key());
                    items.add(new Item(e, alts.stream().limit(3).toList(), s == null ? List.of() : sites.find(e.key(), s)));
                    all.add(e);
                }
            }
            batches.add(new Batch(i + 1, owners, items, load[i], mainArea(all)));
        }
        return new Result(batches);
    }

    /** Heaviest non-empty area (ties: alphabetical); empty if none. */
    static String mainArea(List<TodoEntry> entries) {
        Map<String, Double> w = new TreeMap<>();
        for (TodoEntry e : entries) {
            if (!e.area().isEmpty()) {
                w.merge(e.area(), weight(e), Double::sum);
            }
        }
        String best = "";
        double bw = -1;
        for (Map.Entry<String, Double> e : w.entrySet()) {
            if (e.getValue() > bw) {
                best = e.getKey();
                bw = e.getValue();
            }
        }
        return best;
    }

    /** Name of the bodies file a worker writes: {@code <area>-<n>.yaml} ({@code misc} without an area). */
    public static String bodiesFile(Batch b) {
        return (b.area().isEmpty() ? "misc" : b.area()) + "-" + b.worker() + ".yaml";
    }

    public static String render(Batch b) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Todo batch ").append(b.worker()).append("\n\n");
        sb.append("- entries: ").append(b.items().size()).append(", weight ").append(fmt(b.weight())).append('\n');
        sb.append("- owners: ").append(b.owners().size()).append('\n');
        sb.append("- write your bodies to: `").append(bodiesFile(b)).append("` (only this file)\n\n");
        String owner = null;
        int n = 0;
        for (Item it : b.items()) {
            TodoEntry e = it.todo();
            if (!e.owner().equals(owner)) {
                owner = e.owner();
                sb.append("## ").append(owner).append("\n\n");
            }
            sb.append("### ").append(++n).append(". `").append(e.key()).append("`\n\n");
            sb.append("- signature: `").append(e.signature()).append("`\n");
            sb.append("- plan: ").append(e.plan()).append(", uses ").append(e.uses());
            if (!e.area().isEmpty()) {
                sb.append(", area ").append(e.area());
            }
            sb.append('\n');
            if (!e.hint().isEmpty()) {
                sb.append("- hint: ").append(e.hint()).append('\n');
            }
            if (!it.candidates().isEmpty()) {
                sb.append("- candidates:");
                for (Alt a : it.candidates()) {
                    sb.append(" `").append(a.owner()).append('#').append(a.name()).append(' ').append(a.desc()).append("` ")
                            .append(a.score()).append(';');
                }
                sb.append('\n');
            }
            for (String s : it.sites()) {
                sb.append("- call site: `").append(s.replace('`', '\'')).append("`\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String renderIndex(List<Batch> batches) {
        StringBuilder sb = new StringBuilder("worker\tfile\tentries\tweight\towners\tarea\tbodies\n");
        for (Batch b : batches) {
            sb.append(b.worker()).append('\t').append(b.worker()).append(".md\t").append(b.items().size()).append('\t')
                    .append(fmt(b.weight())).append('\t').append(b.owners().size()).append('\t').append(b.area()).append('\t')
                    .append(bodiesFile(b)).append('\n');
        }
        return sb.toString();
    }

    public static void write(Result r, Path out) throws IOException {
        Files.createDirectories(out);
        for (Batch b : r.batches()) {
            Files.writeString(out.resolve(b.worker() + ".md"), render(b), StandardCharsets.UTF_8);
        }
        Files.writeString(out.resolve("index.tsv"), renderIndex(r.batches()), StandardCharsets.UTF_8);
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    /** Call-site lookup in the mod sources; only the referencing classes' files are read, each once. */
    private static final class CallSites {
        private final Path root;
        private final Map<String, List<String>> cache = new HashMap<>();

        CallSites(Path root) {
            this.root = root;
        }

        List<String> find(SymbolKey key, ApiSymbol s) {
            if (root == null) {
                return List.of();
            }
            Pattern p = pattern(key);
            if (p == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String cls : s.refClasses().stream().sorted().toList()) {
                String top = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
                List<String> lines = cache.computeIfAbsent(top, this::read);
                for (int i = 0; i < lines.size() && out.size() < MAX_SITES; i++) {
                    String l = lines.get(i).trim();
                    if (l.startsWith("import ") || l.startsWith("//") || l.startsWith("*") || l.startsWith("/*")) {
                        continue;
                    }
                    if (p.matcher(l).find()) {
                        String file = top.substring(top.lastIndexOf('.') + 1) + ".java";
                        out.add(file + ":" + (i + 1) + ": " + (l.length() > MAX_LINE ? l.substring(0, MAX_LINE) + "..." : l));
                    }
                }
                if (out.size() >= MAX_SITES) {
                    break;
                }
            }
            return out;
        }

        private List<String> read(String cls) {
            Path f = root.resolve(cls.replace('.', '/') + ".java");
            try {
                return Files.isRegularFile(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        }

        private static Pattern pattern(SymbolKey key) {
            return switch (key.kind()) {
                case METHOD -> key.name().equals("<init>")
                        ? Pattern.compile("\\bnew\\s+" + Pattern.quote(simple(key.owner())) + "\\s*[<(]")
                        : Pattern.compile("\\b" + Pattern.quote(key.name()) + "\\s*\\(");
                case FIELD -> Pattern.compile("\\b" + Pattern.quote(key.name()) + "\\b");
                default -> null;
            };
        }

        private static String simple(String owner) {
            String s = owner.substring(owner.lastIndexOf('.') + 1);
            return s.substring(s.lastIndexOf('$') + 1);
        }
    }
}
