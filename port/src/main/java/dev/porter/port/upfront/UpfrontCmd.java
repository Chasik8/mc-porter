package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.passes.Adapters;
import dev.porter.port.start.Home;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.MatchTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter upfront}: the whole upfront translation layer in one command. Stages (each one is the command of the same
 * name, run with the same arguments a hand run would use): index, inventory, flattening, match, emit-rules, shimgen,
 * todo-batch; then the report ({@link UpfrontReport}). The output directory holds everything a port run needs:
 * {@code rules/} (rule files for {@code porter rewrite}), {@code gen/src} (shim sources for the install),
 * {@code batches/} (the TODO batch) and {@code upfront-report.txt}. The tables are byte-identical between two runs
 * with the same inputs.
 */
@Command(name = "upfront", description = {
        "Runs index, inventory, flattening, match, emit-rules, shimgen and todo-batch for one mod and writes the report:",
        "how many of the platform symbols the mod uses are resolved before and after upfront, and what is left to fill."})
public final class UpfrontCmd implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--jar", required = true, paramLabel = "<mod.jar>", description = "The mod jar built for the source version.")
    Path jar;
    @Option(names = "--src", paramLabel = "<dir>", description = "Decompiled sources of the mod (the coverage universe, call sites).")
    Path src;
    @Option(names = "--classes", paramLabel = "<list.txt>", description = "Only these top-level classes (repeatable).")
    List<Path> classes = new ArrayList<>();
    @Option(names = "--rules", paramLabel = "<dir|file>", description = "Existing (hand) rules, public packs and profile; repeatable.")
    List<Path> rules = new ArrayList<>();
    @Option(names = "--eval-scope", paramLabel = "<dir>", description = "A profile's own hand rules (a subset of --rules): the report says how many of them upfront reproduces by itself; repeatable.")
    List<Path> evalScope = new ArrayList<>();
    @Option(names = "--shims",paramLabel = "<dir>", description = "Shim source roots besides the bundled adapter pack (repeatable).")
    List<Path> shims = new ArrayList<>();
    @Option(names = "--data", paramLabel = "<dir>", description = "Per version pair data: match-tables.yaml, concepts.yaml, bodies/ (default: rules/upfront/1.19.2__1.7.10).")
    Path data;
    @Option(names = "--tables", paramLabel = "<match-tables.yaml>", description = "Default: <data>/match-tables.yaml.")
    Path tables;
    @Option(names = "--concepts", paramLabel = "<concepts.yaml>", description = "Default: <data>/concepts.yaml.")
    Path concepts;
    @Option(names = "--bodies", paramLabel = "<dir>", description = "Bodies DB. Default: <data>/bodies.")
    Path bodies;
    @Option(names = "--cache", paramLabel = "<dir>", description = "Porter cache (default $PORTER_CACHE, else ~/.porter/cache).")
    Path cache;
    @Option(names = "--index", paramLabel = "<dir>", description = "api-index directory (default <cache>/upfront/1.19.2__1.7.10).")
    Path index;
    @Option(names = "--graph", paramLabel = "<dir>", description = "porter graph output (default <cache>/graph/out); its mechanical rules count as graph rules.")
    Path graph;
    @Option(names = "--platform-jar", paramLabel = "<jar>", description = "1.19.2 platform class jar for the inventory (default: the mojmap compile jar of the cache).")
    List<Path> platformJars = new ArrayList<>();
    @Option(names = "--client-jar", paramLabel = "<client.jar>", description = "1.19.2 client jar for flattening (default <cache>/mojang-1.19.2/client.jar).")
    Path clientJar;
    @Option(names = "--target-jar", paramLabel = "<mcp.jar>", description = "1.7.10 MCP jar (default <cache>/forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar).")
    Path targetJar;
    @Option(names = "--check-bodies", description = "After shimgen, compile all bodies (porter bodies check --no-write) against the default 1.7.10 class path.")
    boolean checkBodies;
    @Option(names = "--cp", paramLabel = "<jar>[;<jar>...]", description = "Class path for --check-bodies (default: MCP jar + Forge 1.7.10 libraries + adapter dirs).")
    List<String> cp = new ArrayList<>();
    @Option(names = "--mc-dir", paramLabel = "<dir>", description = "Minecraft directory for the default class path (default $PORTER_MINECRAFT, %APPDATA%/.minecraft).")
    Path mcDir;
    @Option(names = "--sounds", paramLabel = "<sounds.json>", description = "1.7.10 sounds.json (sound-name table for SoundEvents); optional.")
    Path sounds;
    @Option(names = "--workers", description = "Number of TODO batches (default 8).")
    int workers = 8;
    @Option(names = "--scope", paramLabel = "<name>", description = "Label of the report (default: the jar name).")
    String scope;
    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Output directory.")
    Path out;

    @Override
    public String path() {
        return "upfront";
    }

    private int stageNo;
    private final List<String> summaryLines = new ArrayList<>();

    @Override
    public Integer call() {
        RunReport report = new RunReport("upfront", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        try {
            Path cacheDir = cache != null ? cache : defaultCache();
            Path idx = index != null ? index : cacheDir.resolve("upfront/1.19.2__1.7.10");
            Path g = graph != null ? graph : cacheDir.resolve("graph/out");
            Path dataDir = data != null ? data : Path.of("rules/upfront/1.19.2__1.7.10");
            Path tbl = tables != null ? tables : dataDir.resolve("match-tables.yaml");
            Path con = concepts != null ? concepts : dataDir.resolve("concepts.yaml");
            Path bod = bodies != null ? bodies : dataDir.resolve("bodies");
            Path tiny = g.resolve("mappings/1.19.2.tiny");
            Path cj = clientJar != null ? clientJar : cacheDir.resolve("mojang-1.19.2/client.jar");
            Path tj = targetJar != null ? targetJar : cacheDir.resolve("forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar");
            List<Path> plat = platformJars.isEmpty() ? List.of(cacheDir.resolve("forge-1.19.2-compile/mc-forge-1.19.2-mojmap.jar")) : platformJars;
            String label = scope != null ? scope : jar.getFileName().toString().replaceAll("\\.jar$", "");
            if (!Files.isRegularFile(tbl)) {
                System.err.println("porter upfront: " + tbl + " not found (use --data or --tables)");
                return ExitCodes.USAGE;
            }
            Files.createDirectories(out);
            Path inv = out.resolve("inventory/api-inventory.json");
            Path registry = out.resolve("registry/registry.tsv");
            Path matchDir = out.resolve("match");
            Path table = matchDir.resolve("match-table.json");
            Path rulesOut = out.resolve("rules");
            Path gen = out.resolve("gen");
            Path todo = gen.resolve("todo.tsv");
            Path adapters = out.resolve("adapters-src");
            deleteTree(adapters);
            Adapters.write(adapters, null);

            long t0 = System.currentTimeMillis();
            List<String> a = new ArrayList<>(List.of("--graph", g.toString()));
            a.addAll(List.of("--out", idx.toString()));
            if (!stage("index", new ApiIndexCmd(), a, "api-index")) {
                return fail(report);
            }

            a = new ArrayList<>(List.of("--jar", jar.toString(), "--srg-tiny", tiny.toString(), "--hier", idx.resolve("source-hier.tsv").toString(),
                    "--out", inv.toString()));
            if (src != null) {
                a.addAll(List.of("--src", src.toString()));
            }
            for (Path c : classes) {
                a.addAll(List.of("--classes", c.toString()));
            }
            for (Path p : plat) {
                a.addAll(List.of("--platform-jar", p.toString()));
            }
            if (!stage("inventory", new ApiInventoryCmd(), a, "api-inventory:")) {
                return fail(report);
            }

            a = new ArrayList<>(List.of("--client-jar", cj.toString(), "--tiny", tiny.toString(), "--target-jar", tj.toString(), "--out", registry.toString()));
            if (sounds != null) {
                a.addAll(List.of("--sounds", sounds.toString()));
            }
            Files.createDirectories(registry.getParent());
            if (!stage("flattening", new FlatteningCmd(), a, "rows ")) {
                return fail(report);
            }

            a = new ArrayList<>(List.of("--inventory", inv.toString(), "--index", idx.toString(), "--tables", tbl.toString(), "--registry", registry.toString(),
                    "--graph", g.toString(), "--out", matchDir.toString()));
            if (Files.isRegularFile(con)) {
                a.addAll(List.of("--concepts", con.toString()));
            }
            for (Path r : rules) {
                a.addAll(List.of("--rules", r.toString()));
            }
            if (!rules.isEmpty()) {
                a.add("--eval");
                for (Path p : evalScope) {
                    a.addAll(List.of("--eval-scope", p.toString()));
                }
            }
            if (!stage("match", new MatchCmd(), a, "match:")) {
                return fail(report);
            }

            deleteTree(rulesOut);
            a = new ArrayList<>(List.of("--table", table.toString(), "--out", rulesOut.toString()));
            if (Files.isRegularFile(con)) {
                a.addAll(List.of("--concepts", con.toString()));
            }
            for (Path r : rules) {
                a.addAll(List.of("--rules", r.toString()));
            }
            if (!stage("emit-rules", new EmitRulesCommand(), a, "rules:")) {
                return fail(report);
            }

            deleteTree(gen);
            a = new ArrayList<>(List.of("--table", table.toString(), "--inventory", inv.toString(), "--hierarchy", idx.resolve("source-hier.tsv").toString(),
                    "--shims", adapters.toString(), "--target", idx.resolve("target.tsv").toString(), "--out", gen.toString(), "--todo", todo.toString()));
            if (Files.isRegularFile(con)) {
                a.addAll(List.of("--concepts", con.toString()));
            }
            for (Path s : shims) {
                a.addAll(List.of("--shims", s.toString()));
            }
            if (Files.isDirectory(bod)) {
                a.addAll(List.of("--bodies", bod.toString()));
            }
            if (!stage("shimgen", new ShimGenCmd(), a, "shimgen:")) {
                return fail(report);
            }

            if (checkBodies) {
                a = new ArrayList<>(List.of("--gen", gen.toString(), "--no-write", "--mc-dir",
                        (mcDir != null ? mcDir : TargetClasspath.defaultMinecraftDir()).toString(), "--target-jar", tj.toString()));
                for (String c : cp) {
                    a.addAll(List.of("--cp", c));
                }
                a.addAll(List.of("--bodies", bod.toString()));
                if (!stage("bodies-check", new BodiesCheckCmd(), a, "bodies check:")) {
                    return fail(report);
                }
            }

            deleteTree(out.resolve("batches"));
            a = new ArrayList<>(List.of("--todo", todo.toString(), "--table", table.toString(), "--inventory", inv.toString(), "--workers", Integer.toString(workers),
                    "--out", out.resolve("batches").toString()));
            if (src != null) {
                a.addAll(List.of("--src", src.toString()));
            }
            if (!stage("todo-batch", new TodoBatchCmd(), a, "todo-batch:")) {
                return fail(report);
            }

            MatchEval.Result byItself = readByItself(matchDir.resolve("eval.txt"));
            List<String> lines = UpfrontReport.write(label, MatchTable.read(table), ApiInventory.read(inv), todo, rulesOut.resolve("upfront-skipped.tsv"), byItself, out);
            lines.forEach(System.out::println);
            System.out.println("upfront done in " + (System.currentTimeMillis() - t0) / 1000 + " s -> " + out);
            report.put("out", out.toString()).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("porter upfront: " + e);
            report.put("error", String.valueOf(e)).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
            return ExitCodes.FAILED;
        }
    }

    private int fail(RunReport report) {
        report.put("error", "a stage failed").write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
        return ExitCodes.FAILED;
    }

    private static final Pattern SCOPE = Pattern.compile("scope: (\\d+) rules, (\\d+) used by the inventory, (\\d+) by itself");
    private static final Pattern BY_ITSELF =Pattern.compile("by itself: (\\d+) of (\\d+) hand-resolved symbols, (\\d+) of (\\d+) hand rules");

    private static MatchEval.Result readByItself(Path evalTxt) throws IOException {
        if (!Files.exists(evalTxt)) {
            return null;
        }
        int[] v = null;
        int[] sc = {0, 0, 0};
        for (String l : Files.readAllLines(evalTxt, StandardCharsets.UTF_8)) {
            Matcher m = BY_ITSELF.matcher(l);
            if (m.find()) {
                v = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
            }
            Matcher s = SCOPE.matcher(l);
            if (s.find()) {
                sc = new int[] {Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)), Integer.parseInt(s.group(3))};
            }
        }
        return v == null ? null : new MatchEval.Result(null, null, v[1], 0, 0, List.of(), v[3], v[2], v[0], sc[0], sc[1], sc[2]);
    }

    /** Runs one stage command, keeps its output in {@code logs/<stage>.log} and prints one summary line. */
    private boolean stage(String name, Object cmd, List<String> args, String summaryPrefix) throws IOException {
        long t = System.currentTimeMillis();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        PrintStream oldErr = System.err;
        int code;
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            System.setErr(ps);
            code = new CommandLine(cmd).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(old);
            System.setErr(oldErr);
        }
        String text = buf.toString(StandardCharsets.UTF_8);
        Files.createDirectories(out.resolve("logs"));
        Files.writeString(out.resolve("logs/" + name + ".log"), text, StandardCharsets.UTF_8);
        String line = "";
        for (String l : text.split("\\R")) {
            if (l.startsWith(summaryPrefix) || (line.isEmpty() && !l.isBlank())) {
                line = l;
            }
        }
        if (code != 0 && line.isEmpty()) {
            line = "failed (exit " + code + ")";
        }
        System.out.printf(Locale.ROOT, "[%d/7] %-10s %s (%d ms)%n", ++stageNo, name, code == 0 ? line : "FAILED exit " + code + ": " + line,
                System.currentTimeMillis() - t);
        if (code != 0) {
            System.out.println(text);
        }
        return code == 0;
    }

    static Path defaultCache() {
        String env = System.getenv("PORTER_CACHE");
        return env != null && !env.isBlank() ? Path.of(env) : Home.defaultRoot().resolve("cache");
    }

    private static void deleteTree(Path d) throws IOException {
        if (!Files.exists(d)) {
            return;
        }
        try (var w = Files.walk(d)) {
            for (Path p : w.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
