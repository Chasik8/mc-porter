package dev.porter.loop;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.loop.candidates.CandidateAggregator;
import dev.porter.loop.candidates.HandportImporter;
import dev.porter.loop.graph.GraphSource;
import dev.porter.loop.rules.RuleEngine;
import dev.porter.loop.rules.RewriterRuleEngine;
import dev.porter.loop.rules.TextRuleEngine;
import dev.porter.loop.run.CompileRunner;
import dev.porter.loop.run.GradleCompileRunner;
import dev.porter.loop.run.JavacCompileRunner;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter loop}: compile, classify javac errors, apply rules, escalate the rest. Two extra modes
 * work on candidate logs only: {@code --aggregate} and {@code --import-handport}.
 */
@Command(name = "loop", description = {
        "Compile/classify/fix loop: runs `gradlew compileJava` (through the heavy-job queue), classifies javac errors,",
        "applies matching rules, writes agent task files for the rest, logs candidates and drafts rules (count >= 3).",
        "Other modes: --aggregate <logs...> (aggregate + draft only), --import-handport <api.jsonl> --out <file>."})
public final class LoopCommand implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--project", paramLabel = "<dir>", description = "Gradle project (or source project with --runner javac).")
    Path project;

    @Option(names = "--max-iter", defaultValue = "10", description = "Maximum number of compiles (default ${DEFAULT-VALUE}).")
    int maxIter;

    @Option(names = "--runner", defaultValue = "gradle", description = "gradle (default) or javac (in-process, no Gradle).")
    String runner;

    @Option(names = "--task", defaultValue = "compileJava", description = "Gradle task (default ${DEFAULT-VALUE}).")
    String task;

    @Option(names = "--queue-wrapper", paramLabel = "<script>",
            description = "Job queue wrapper for Gradle: <wrapper> <agent> <command...> (default $PORTER_QUEUE_WRAPPER, else none).")
    String queueWrapper;

    @Option(names = "--no-queue", description = "Run Gradle directly, without the queue wrapper.")
    boolean noQueue;

    @Option(names = "--queue-agent", defaultValue = "loop", description = "Owner name given to the queue (default ${DEFAULT-VALUE}).")
    String queueAgent;

    @Option(names = "--java-home", paramLabel = "<jdk>", description = "JAVA_HOME for Gradle.")
    Path javaHome;

    @Option(names = "--gradle-heap", defaultValue = "2g", description = "Gradle JVM heap (default ${DEFAULT-VALUE}; 'none' keeps the project's).")
    String gradleHeap;

    @Option(names = "--gradle-arg", paramLabel = "<arg>", description = "Extra Gradle argument (repeatable).")
    List<String> gradleArgs = new ArrayList<>();

    @Option(names = "--timeout-min", defaultValue = "30", description = "Gradle timeout per compile, minutes (default ${DEFAULT-VALUE}).")
    int timeoutMin;

    @Option(names = "--src", paramLabel = "<dir>", description = "javac runner: source roots (default <project>/src/main/java).")
    List<Path> src = new ArrayList<>();

    @Option(names = "--classpath", paramLabel = "<jar|dir>", description = "javac runner: classpath entry (repeatable).")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--javac-opt", paramLabel = "<opt>", description = "javac runner: extra option (repeatable).")
    List<String> javacOpts = new ArrayList<>();

    @Option(names = "--rules", paramLabel = "<dir|file>", description = "Rule YAML (repeatable; default ./rules when present).")
    List<Path> rules = new ArrayList<>();

    @Option(names = "--shim", description = "The porter shim is part of the project: rules with shim:* preconditions may fire (default off).")
    boolean shim;

    @Option(names = "--engine", defaultValue = "rewriter",
            description = "rewriter (default: the rewriter's Spoon engine, text engine as fallback) or text (text engine only).")
    String engine;

    @Option(names = "--graph", paramLabel = "<dir|file>", description = "Symbol-graph edge documents (graph module output) for task context.")
    Path graph;

    @Option(names = "--from", defaultValue = "forge-1.19.2", description = "Source platform version (default ${DEFAULT-VALUE}).")
    String from;

    @Option(names = "--to", defaultValue = "forge-1.7.10", description = "Target platform version (default ${DEFAULT-VALUE}).")
    String to;

    @Option(names = "--work", paramLabel = "<dir>", description = "Work dir: tasks, candidates, backups, logs (default <project>/build/porter-loop).")
    Path work;

    @Option(names = "--candidates", paramLabel = "<file|dir>", description = "More candidate logs for similarity and aggregation (repeatable).")
    List<Path> candidates = new ArrayList<>();

    @Option(names = "--proposed", paramLabel = "<dir>",
            description = "Where draft rules go (default <work>/proposed; pass rules/_proposed for public, non-ARR projects).")
    Path proposed;

    @Option(names = "--aggregate", paramLabel = "<file|dir>", arity = "1..*", description = "Mode: only aggregate these candidate logs.")
    List<Path> aggregate;

    @Option(names = "--import-handport", paramLabel = "<api.jsonl>", description = "Mode: convert a hand-port api.jsonl to candidates.")
    Path importHandport;

    @Option(names = "--out", paramLabel = "<file>", description = "Output *.candidates.jsonl for --import-handport.")
    Path out;

    @Override
    public String path() {
        return "loop";
    }

    @Override
    public Integer call() throws Exception {
        PrintWriter o = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport("loop", spec.commandLine().getParseResult().originalArgs());
        if (importHandport != null) {
            return importHandport(o, err, report);
        }
        if (aggregate != null) {
            return aggregateOnly(o, report);
        }
        if (project == null) {
            err.println("porter loop: --project is required (or --aggregate / --import-handport)");
            report.put("error", "missing --project").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        Path proj = project.toAbsolutePath().normalize();
        Path workDir = (work != null ? work : proj.resolve("build/porter-loop")).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        CompileRunner cr;
        if ("javac".equals(runner)) {
            List<Path> roots = src.isEmpty() ? List.of(proj.resolve("src/main/java")) : src.stream().map(p -> proj.resolve(p)).toList();
            cr = new JavacCompileRunner(roots, classpath, workDir.resolve("classes"), javacOpts);
        } else if ("gradle".equals(runner)) {
            String q = noQueue ? null : (queueWrapper != null ? queueWrapper : GradleCompileRunner.defaultQueue());
            if (q != null && q.endsWith(".sh") && !Files.isRegularFile(Path.of(q))) {
                err.println("porter loop: queue wrapper not found: " + q + " (use --queue-wrapper or --no-queue)");
                report.put("error", "queue wrapper not found").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
                return ExitCodes.USAGE;
            }
            cr = new GradleCompileRunner(new GradleCompileRunner.Config(proj, task, q, queueAgent, javaHome,
                    "none".equals(gradleHeap) ? null : gradleHeap, gradleArgs, workDir, timeoutMin));
        } else {
            err.println("porter loop: unknown --runner " + runner + " (gradle|javac)");
            report.put("error", "unknown runner").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        List<Path> rulePaths = new ArrayList<>(rules);
        if (rulePaths.isEmpty() && Files.isDirectory(Path.of("rules"))) {
            rulePaths.add(Path.of("rules"));
        }
        TextRuleEngine text = TextRuleEngine.load(rulePaths, from, to, shim);
        RuleEngine ruleEngine = "text".equals(engine) ? text : new RewriterRuleEngine(text.rules(), from, shim, text);
        Path candDir = workDir.resolve("candidates");
        List<Path> candSources = new ArrayList<>();
        candSources.add(candDir);
        candSources.addAll(candidates);
        String gFrom = bare(from);
        String gTo = bare(to);
        LoopEngine.Config cfg = new LoopEngine.Config(proj, cr, ruleEngine, maxIter, workDir,
                candDir.resolve("loop.candidates.jsonl"), candSources, proposed != null ? proposed : workDir.resolve("proposed"),
                graph == null ? null : () -> GraphSource.open(graph, gFrom, gTo), from, to);
        Map<String, Object> result = new LoopEngine(cfg).run();
        Path iterFile = RunReport.reportsDir().resolve("loop").resolve(result.get("project") + ".iterations.json");
        Files.createDirectories(iterFile.getParent());
        Files.writeString(iterFile, Json.json().writeValueAsString(result).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        o.println("porter loop: " + result.get("status") + " after " + result.get("compiles") + " compile(s); errors "
                + totals.get("errorsInitial") + " -> " + totals.get("errorsFinal") + ", fixes applied " + totals.get("fixesApplied")
                + " (verified " + totals.get("fixesVerified") + ", persisted " + totals.get("fixesPersisted") + "), tasks " + totals.get("tasks"));
        o.println("  iterations: " + iterFile.toString().replace('\\', '/'));
        o.println("  tasks:      " + workDir.resolve("tasks").toString().replace('\\', '/'));
        o.flush();
        boolean ok = "compiled".equals(result.get("status"));
        report.put("iterationReport", iterFile.toString().replace('\\', '/')).put("result", result)
                .write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, ok ? ExitCodes.OK : ExitCodes.FAILED);
        return ok ? ExitCodes.OK : ExitCodes.FAILED;
    }

    private Integer importHandport(PrintWriter o, PrintWriter err, RunReport report) {
        if (out == null) {
            err.println("porter loop: --import-handport needs --out <file>.candidates.jsonl");
            report.put("error", "missing --out").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        List<Candidate> cs = HandportImporter.importTo(importHandport, out, "porting-log/api.jsonl");
        List<CandidateAggregator.Group> groups = CandidateAggregator.aggregate(cs);
        Map<String, Object> summary = summary(cs, groups);
        if (proposed != null) {
            summary.put("drafts", CandidateAggregator.writeDrafts(groups, proposed, CandidateAggregator.DRAFT_THRESHOLD, from, to)
                    .stream().map(Rule::id).toList());
        }
        o.println("porter loop: imported " + cs.size() + " hand-port record(s) -> " + out + "; " + groups.size()
                + " distinct normalized form(s), " + summary.get("draftable") + " with count >= " + CandidateAggregator.DRAFT_THRESHOLD);
        o.flush();
        report.put("summary", summary).write(RunReport.STATUS_OK, ExitCodes.OK);
        return ExitCodes.OK;
    }

    private Integer aggregateOnly(PrintWriter o, RunReport report) {
        List<Candidate> cs = CandidateAggregator.readAll(aggregate);
        List<CandidateAggregator.Group> groups = CandidateAggregator.aggregate(cs);
        Map<String, Object> summary = summary(cs, groups);
        if (proposed != null) {
            summary.put("drafts", CandidateAggregator.writeDrafts(groups, proposed, CandidateAggregator.DRAFT_THRESHOLD, from, to)
                    .stream().map(Rule::id).toList());
        }
        o.println("porter loop: " + cs.size() + " candidate(s), " + groups.size() + " group(s), " + summary.get("draftable")
                + " with count >= " + CandidateAggregator.DRAFT_THRESHOLD + (proposed == null ? " (no --proposed: nothing written)" : ""));
        o.flush();
        report.put("summary", summary).write(RunReport.STATUS_OK, ExitCodes.OK);
        return ExitCodes.OK;
    }

    private static Map<String, Object> summary(List<Candidate> cs, List<CandidateAggregator.Group> groups) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidates", cs.size());
        m.put("occurrences", cs.stream().mapToInt(Candidate::count).sum());
        m.put("groups", groups.size());
        m.put("draftable", groups.stream().filter(g -> g.draftable(CandidateAggregator.DRAFT_THRESHOLD)).count());
        m.put("multiRecordGroups", groups.stream().filter(g -> g.candidates().size() > 1).count());
        return m;
    }

    private static String bare(String platform) {
        return platform.substring(platform.indexOf('-') + 1);
    }
}
