package dev.porter.rewrite;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.rewrite.engine.CfrLambdas;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.RuleBook;
import dev.porter.rewrite.engine.RuleTestRunner;
import dev.porter.rewrite.engine.TypeFacts;
import dev.porter.rewrite.graph.GraphRuleGenerator;
import dev.porter.rewrite.graph.SymbolGraph;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter rewrite --src <mod-src> --from 1.19.2 --to 1.7.10 --out rw/}. See rewrite/README.md. */
@Command(name = "rewrite", description = "Rewrite mod logic between versions with YAML rules (Spoon); unrewritten cases get TODO(porter:<reason>) markers.")
public final class RewriteCommand implements PorterSubcommand, Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--src", paramLabel = "<dir>", description = "Mod source root (all .java files form the model).")
    Path src;

    @Option(names = "--from", required = true, paramLabel = "<version>", description = "Source version, e.g. 1.19.2 or forge-1.19.2.")
    String from;

    @Option(names = "--to", required = true, paramLabel = "<version>", description = "Target version, e.g. 1.7.10.")
    String to;

    @Option(names = "--out", paramLabel = "<dir>", description = "Output directory (rewritten files mirror --src).")
    Path out;

    @Option(names = "--rules", paramLabel = "<dir|file>", description = "Rule files/directories (default: ./rules). Repeatable.")
    List<Path> rules = new ArrayList<>();

    @Option(names = "--graph", paramLabel = "<file|dir>", description = "Symbol-graph documents (*.graph.json): SRG aliases + generated mechanical rules. Repeatable.")
    List<Path> graphs = new ArrayList<>();

    @Option(names = "--type-facts", paramLabel = "<file>", description = "Supertype tables (TSV) in addition to the bundled one. Repeatable.")
    List<Path> typeFacts = new ArrayList<>();

    @Option(names = "--include", paramLabel = "<glob>", description = "Only rewrite files matching (relative to --src, e.g. '**/FooTile.java'); others only provide types. Repeatable.")
    List<String> includes = new ArrayList<>();

    @Option(names = "--classpath", paramLabel = "<jar>", description = "Libraries for typing (source version). Repeatable.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--platform", paramLabel = "<prefix>", description = "Extra package prefix whose symbols must be rewritten (e.g. 'com.example.host.'). Repeatable.")
    List<String> platform = new ArrayList<>();

    @Option(names = "--no-shim", description = "Disallow rules that need the porter 1.7.10 shim.")
    boolean noShim;

    @Option(names = "--shim-package", paramLabel = "<pkg>", description = "Relocate the shim (default dev.porter.shim).")
    String shimPackage = RuleBook.SHIM_PACKAGE;

    @Option(names = "--emit-graph-rules", paramLabel = "<dir>", description = "Also write the rules generated from --graph as YAML.")
    Path emitGraphRules;

    @Option(names = "--relocate", paramLabel = "<from>=<to>", description = "Move rewritten classes from a package prefix to another (package declaration, output path, references to ported classes). Repeatable.")
    List<String> relocate = new ArrayList<>();

    @Option(names = "--stub-contract", paramLabel = "<porter-generate.json>", description = "Apply the generated stubs' contract to the rewritten classes that replace them: stub superclass, stub constructor.")
    Path stubContract;

    @Option(names = "--relocate-known", paramLabel = "<file>", description = "Classes (dotted names, whitespace separated) that count as rewritten for --relocate although they are not --include d: a partial rerun of a port relocates references to the rest of it.")
    Path relocateKnown;

    @Option(names = "--check-rules", description = "Only run the before/after tests of the loaded rules.")
    boolean checkRules;

    @Option(names = "--compliance", paramLabel = "<n>", description = "Java language level of the sources (default 17).")
    int compliance = 17;

    @Override
    public String path() {
        return "rewrite";
    }

    static String platformVersion(String v) {
        return v.contains("-") ? v : "forge-" + v;
    }

    static String bare(String v) {
        return v.contains("-") ? v.substring(v.indexOf('-') + 1) : v;
    }

    @Override
    public Integer call() {
        List<String> args = new ArrayList<>();
        RunReport report = new RunReport("rewrite", args);
        try {
            return run(report, args);
        } catch (RuntimeException e) {
            report.put("error", e.toString());
            report.write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            System.err.println("porter rewrite: " + e);
            return ExitCodes.ERROR;
        }
    }

    private int run(RunReport report, List<String> args) {
        String pf = platformVersion(from);
        String pt = platformVersion(to);
        args.addAll(List.of("--from", pf, "--to", pt));
        // ---- rules
        List<Path> ruleSources = rules.isEmpty() && Files.isDirectory(Path.of("rules")) ? List.of(Path.of("rules")) : rules;
        List<Rule> hand = new ArrayList<>();
        for (Path p : ruleSources) {
            List<Rule> rs = Files.isDirectory(p) ? RuleIo.readAll(p) : RuleIo.read(p);
            for (Rule r : rs) {
                if (r.from().equals(pf) && r.to().equals(pt)) {
                    hand.add(r);
                }
            }
        }
        SymbolGraph graph = graphs.isEmpty() ? SymbolGraph.empty() : SymbolGraph.load(graphs, bare(from));
        NameTable names = NameTable.of(graph, bare(from));
        GraphRuleGenerator.Output gen = new GraphRuleGenerator(bare(from), bare(to), pf, pt).generate(graph);
        Set<String> handIds = new java.util.HashSet<>();
        hand.forEach(r -> handIds.add(r.id()));
        List<Rule> all = new ArrayList<>(hand);
        for (Rule r : gen.rules()) {
            if (!handIds.contains(r.id())) {
                all.add(r);
            }
        }
        if (emitGraphRules != null && !gen.rules().isEmpty()) {
            try {
                Files.createDirectories(emitGraphRules);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            RuleIo.write(emitGraphRules.resolve("graph-" + bare(from) + "__" + bare(to) + ".rule.yaml"), gen.rules());
        }
        TypeFacts facts = loadFacts(pf);
        Rewriter.Options opts = new Rewriter.Options(platformPrefixes(), !noShim, shimPackage, true, compliance, classpath);
        Rewriter.Options rewriteOpts = opts.withKeepTargetMarks(true); // stripped after --relocate
        Map<String, Object> ruleInfo = new LinkedHashMap<>();
        ruleInfo.put("hand", hand.size());
        ruleInfo.put("graph", gen.rules().size());
        ruleInfo.put("byKind", GraphRuleGenerator.census(all));
        ruleInfo.put("graphDiagnostics", gen.diagnostics().size());
        report.put("rules", ruleInfo);

        if (checkRules) {
            List<RuleTestRunner.Failure> failures = new RuleTestRunner(names, facts, opts).run(all);
            int tests = all.stream().mapToInt(r -> r.tests().size()).sum();
            failures.forEach(f -> System.out.println("FAIL " + f.message()));
            System.out.println("rule tests: " + (tests - failures.size()) + "/" + tests + " passed (" + all.size() + " rules)");
            report.put("tests", tests).put("failures", failures.stream().map(RuleTestRunner.Failure::message).toList());
            int code = failures.isEmpty() ? ExitCodes.OK : ExitCodes.FAILED;
            report.write(failures.isEmpty() ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            return code;
        }
        if (src == null || out == null) {
            System.err.println("porter rewrite: --src and --out are required (unless --check-rules)");
            return ExitCodes.USAGE;
        }
        args.addAll(List.of("--src", src.toString(), "--out", out.toString()));
        relocate.forEach(r -> args.addAll(List.of("--relocate", r)));
        if (stubContract != null) {
            args.addAll(List.of("--stub-contract", stubContract.toString()));
        }
        List<Rewriter.SourceFile> files = readSources();
        Rewriter rw = new Rewriter(all, names, facts, gen.diagnostics(), rewriteOpts);
        rw.alsoPorted(knownClasses().stream().map(c -> c.replace('.', '/') + ".java").collect(java.util.stream.Collectors.toSet()));
        Rewriter.Result res = rw.rewrite(files);
        Map<String, Object> rep = writeOutput(res, all, rw);
        report.put("totals", rep.get("totals"));
        report.put("output", out.toString());
        report.write(RunReport.STATUS_OK, ExitCodes.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) rep.get("totals");
        System.out.println("rewrote " + totals.get("files") + " files: " + totals.get("applied") + " rewrites, "
                + totals.get("todos") + " TODOs -> " + out.resolve("porter-rewrite.json"));
        return ExitCodes.OK;
    }

    private List<String> knownClasses() {
        if (relocateKnown == null) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(Files.readString(relocateKnown, StandardCharsets.UTF_8).split("[ \t\r\n]+")).filter(s -> !s.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> platformPrefixes() {
        List<String> p = new ArrayList<>(Rewriter.Options.defaults().platformPrefixes());
        p.addAll(platform);
        return p;
    }

    private TypeFacts loadFacts(String platformFrom) {
        return TypeFacts.bundledPlus(platformFrom, typeFacts);
    }

    private List<Rewriter.SourceFile> readSources() {
        List<PathMatcher> matchers = includes.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        List<Rewriter.SourceFile> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).sorted().toList()) {
                Path rel = src.relativize(p);
                String relS = rel.toString().replace('\\', '/');
                boolean target = matchers.isEmpty() || matchers.stream().anyMatch(m -> m.matches(Path.of(relS)) || m.matches(rel));
                files.add(new Rewriter.SourceFile(relS, CfrLambdas.fix(Files.readString(p, StandardCharsets.UTF_8)), target));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private Map<String, Object> writeOutput(Rewriter.Result res, List<Rule> all, Rewriter rw) {
        try {
            Files.createDirectories(out);
            int applied = 0;
            int todos = 0;
            int changed = 0;
            Map<String, Integer> byReason = new TreeMap<>();
            Map<String, Integer> byRule = new TreeMap<>();
            List<Object> fileRows = new ArrayList<>();
            List<Object> todoRows = new ArrayList<>();
            List<PostProcess.Relocation> relocs = relocate.stream().map(PostProcess.Relocation::parse).toList();
            Map<String, String> table = PostProcess.relocationTable(res.files(), relocs, knownClasses());
            Map<String, PostProcess.Stub> stubs = stubContract == null ? Map.of() : PostProcess.loadStubs(stubContract);
            List<Rewriter.FileResult> post = new ArrayList<>();
            for (Rewriter.FileResult f0 : res.files()) {
                post.add(PostProcess.cleanImports(PostProcess.stubContract(PostProcess.stripMarks(PostProcess.relocate(f0, table)), stubs, new java.util.HashSet<>(table.values()))));
            }
            for (Rewriter.FileResult f : post) {
                Path target = out.resolve(f.path());
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.text(), StandardCharsets.UTF_8);
                int a = f.applied().values().stream().mapToInt(Integer::intValue).sum();
                applied += a;
                todos += f.todos().size();
                changed += f.changed() ? 1 : 0;
                Map<String, Integer> fr = new TreeMap<>();
                for (Rewriter.Todo t : f.todos()) {
                    fr.merge(t.reason(), 1, Integer::sum);
                    byReason.merge(t.reason(), 1, Integer::sum);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", t.file());
                    row.put("line", t.line());
                    row.put("reason", t.reason());
                    row.put("detail", t.detail());
                    todoRows.add(row);
                }
                f.applied().forEach((k, v) -> byRule.merge(k, v, Integer::sum));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", f.path());
                row.put("changed", f.changed());
                row.put("applied", a);
                row.put("todos", f.todos().size());
                row.put("todosByReason", fr);
                row.put("rules", f.applied());
                fileRows.add(row);
            }
            List<String> shim = copyShim(res.shimClasses());
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("files", res.files().size());
            totals.put("changed", changed);
            totals.put("applied", applied);
            totals.put("todos", todos);
            totals.put("todosByReason", byReason);
            Map<String, Object> rep = new LinkedHashMap<>();
            rep.put("from", platformVersion(from));
            rep.put("to", platformVersion(to));
            Map<String, Object> rulesRow = new LinkedHashMap<>(); // ordered: Map.of iteration order varies per JVM
            rulesRow.put("loaded", all.size());
            rulesRow.put("unsupported", rw.book().unsupported().stream().map(RuleBook.Prepared::id).sorted().toList());
            rep.put("rules", rulesRow);
            rep.put("totals", totals);
            rep.put("ruleUse", byRule);
            rep.put("shim", shim);
            rep.put("files", fileRows);
            rep.put("todos", todoRows);
            Files.writeString(out.resolve("porter-rewrite.json"),
                    Json.json().writeValueAsString(rep).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
            return rep;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies the shim sources the output needs, relocated to --shim-package. */
    private List<String> copyShim(Set<String> used) throws IOException {
        if (used.isEmpty()) {
            return List.of();
        }
        Map<String, String> sources = new HashMap<>();
        sources.put("BlockPos", "/porter/shim/main/java/dev/porter/shim/BlockPos.java");
        sources.put("Direction", "/porter/shim/main/java/dev/porter/shim/Direction.java");
        sources.put("Worlds", "/porter/shim/world/java/dev/porter/shim/Worlds.java");
        List<String> classes = new ArrayList<>(List.of("BlockPos", "Direction"));
        if (used.contains("Worlds")) {
            classes.add("Worlds");
        }
        String pkg = shimPackage == null ? RuleBook.SHIM_PACKAGE : shimPackage;
        Path dir = out.resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        List<String> written = new ArrayList<>();
        for (String c : classes) {
            try (InputStream in = getClass().getResourceAsStream(sources.get(c))) {
                if (in == null) {
                    throw new IllegalStateException("shim source missing from the rewriter jar: " + c);
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("package " + RuleBook.SHIM_PACKAGE + ";", "package " + pkg + ";");
                Files.writeString(dir.resolve(c + ".java"), text, StandardCharsets.UTF_8);
                written.add(pkg + "." + c);
            }
        }
        return written;
    }
}
