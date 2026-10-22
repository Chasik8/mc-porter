package dev.porter.testkit.runner.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.json.Json;
import dev.porter.testkit.runner.IdMap;
import dev.porter.testkit.runner.Scenario;
import dev.porter.testkit.runner.ScenarioRunner;
import dev.porter.testkit.runner.TraceDiff;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code porter test run | diff | roundtrip} (testkit). */
public final class TestCommands {
    private TestCommands() {
    }

    /** One side given on the command line: a mod jar to run, or an existing trace to reuse. */
    static final class SideSpec {
        Path jarOrTrace;
        String version;
        List<Path> mods = List.of();
        Path ids;

        boolean isTrace() {
            return jarOrTrace != null && jarOrTrace.getFileName().toString().endsWith(".trace.json");
        }

        String version(String def) {
            if (version != null) {
                return version;
            }
            if (jarOrTrace != null && !isTrace()) {
                String g = ScenarioRunner.guessVersion(jarOrTrace);
                if (g != null) {
                    return g;
                }
            }
            return def;
        }
    }

    /** Trace file a side of a scenario uses (the given trace, or the one a run writes). */
    static Path traceFile(Scenario sc, SideSpec spec, String version, Path outDir, String label) {
        return spec.isTrace() ? spec.jarOrTrace : outDir.resolve(sc.id() + "-" + version + (label == null ? "" : "-" + label) + ".trace.json");
    }

    /** GUI captures of a client scenario side (exported next to its trace), or null. */
    static dev.porter.testkit.runner.gui.GuiCaptures.Set guiSet(Scenario sc, SideSpec spec, String version, Path outDir, String label) {
        if (!sc.side().equals("client")) {
            return null;
        }
        Path dir = dev.porter.testkit.runner.gui.GuiCaptures.dirFor(traceFile(sc, spec, version, outDir, label));
        return Files.isRegularFile(dir.resolve("gui.json")) ? dev.porter.testkit.runner.gui.GuiCaptures.load(dir) : null;
    }

    /** Writes the GUI report (if any client scenario produced captures); returns true if a GUI is broken. */
    static boolean guiReport(PrintWriter out, Path outDir, dev.porter.testkit.runner.gui.GuiReport rep, String a, String b, RunReport report) {
        if (rep.results().isEmpty()) {
            return false;
        }
        Path html = rep.write("GUI check", a, b);
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (var r : rep.results()) {
            counts.merge(r.verdict(), 1, Integer::sum);
            out.println("  GUI " + r.name() + ": " + r.verdict() + " — " + dev.porter.testkit.runner.gui.GuiReport.mainFinding(r));
        }
        out.println("GUI verdicts " + counts + ", report " + html);
        report.put("guiReport", html.toString()).put("guiVerdicts", counts);
        return rep.anyBroken();
    }

    static JsonNode obtain(Scenario sc, SideSpec spec, String version, ScenarioRunner runner, Path outDir, String label) {
        if (spec.isTrace()) {
            return ContractIo.readValidated(spec.jarOrTrace, Contract.TRACE).get(0);
        }
        Path traceFile = traceFile(sc, spec, version, outDir, label);
        ScenarioRunner.Result r = runner.run(sc, new ScenarioRunner.Side(version, spec.jarOrTrace, spec.mods, IdMap.read(spec.ids)), traceFile);
        return r.trace();
    }

    /** Traces of all scenarios on one side; server scenarios share one launch unless {@code --no-batch}. */
    static List<JsonNode> obtainAll(List<Scenario> scs, SideSpec spec, String version, ScenarioRunner runner, Path outDir,
            String label, boolean batch) {
        List<JsonNode> res = new ArrayList<>();
        if (spec.isTrace()) {
            for (Scenario sc : scs) {
                res.add(obtain(sc, spec, version, runner, outDir, label));
            }
            return res;
        }
        List<Path> files = new ArrayList<>();
        for (Scenario sc : scs) {
            files.add(traceFile(sc, spec, version, outDir, label));
        }
        for (ScenarioRunner.Result r : runner.runAll(scs, new ScenarioRunner.Side(version, spec.jarOrTrace, spec.mods, IdMap.read(spec.ids)),
                files, batch)) {
            res.add(r.trace());
        }
        return res;
    }

    static String line(JsonNode t) {
        return t.path("version").asString() + " " + t.path("status").asString()
                + (t.has("message") ? " — " + t.path("message").asString() : "");
    }

    static void writeJson(Path f, JsonNode n) {
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, Json.json().writeValueAsString(n).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ test run

    @Command(name = "run", description = "Run scenarios on one platform version and write traces.")
    public static final class Run implements Callable<Integer>, PorterSubcommand {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Mixin
        CommonOptions common;

        @Option(names = "--version", paramLabel = "<platform>", description = "forge-1.19.2 | forge-1.7.10 (default: from --mod).")
        String version;

        @Option(names = "--mod", paramLabel = "<jar>", description = "Mod under test (omit for vanilla scenarios).")
        Path mod;

        @Option(names = "--mods", paramLabel = "<jar>", split = ",", description = "Extra mods (dependencies).")
        List<Path> mods;

        @Option(names = "--ids", paramLabel = "<file>", description = "Id map of this version (IR id -> registry name).")
        Path ids;

        @Override
        public String path() {
            return "test run";
        }

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            RunReport report = new RunReport("test run", spec.commandLine().getParseResult().originalArgs());
            try {
                SideSpec s = new SideSpec();
                s.jarOrTrace = mod;
                s.version = version;
                s.mods = CommonOptions.jars(mods);
                s.ids = ids;
                String v = s.version(null);
                if (v == null) {
                    spec.commandLine().getErr().println("porter test run: --version is required without --mod");
                    report.put("error", "no version").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
                    return ExitCodes.USAGE;
                }
                ScenarioRunner runner = common.runner(out);
                List<Map<String, Object>> results = new ArrayList<>();
                boolean ok = true;
                var gui = new dev.porter.testkit.runner.gui.GuiReport(common.outDir().resolve("gui-report"));
                List<Scenario> all = common.loadScenarios();
                List<JsonNode> traces = obtainAll(all, s, v, runner, common.outDir(), null, !common.noBatch);
                for (int i = 0; i < all.size(); i++) {
                    Scenario sc = all.get(i);
                    JsonNode t = traces.get(i);
                    var set = guiSet(sc, s, v, common.outDir(), null);
                    if (set != null) {
                        gui.compare(null, set);
                    }
                    out.println(sc.id() + ": " + line(t));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("scenario", sc.id());
                    r.put("status", t.path("status").asString());
                    r.put("trace", common.outDir().resolve(sc.id() + "-" + v + ".trace.json").toString());
                    results.add(r);
                    ok &= t.path("status").asString().equals("passed");
                }
                ok &= !guiReport(out, common.outDir(), gui, "-", v, report);
                report.put("version", v).put("results", results);
                report.write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, ok ? ExitCodes.OK : ExitCodes.FAILED);
                return ok ? ExitCodes.OK : ExitCodes.FAILED;
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("porter test run: " + e.getMessage());
                report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
                return ExitCodes.ERROR;
            }
        }
    }

    // ------------------------------------------------------------------ test diff

    @Command(name = "diff", description = "Differential scenario test: run (or reuse traces of) two versions and compare the traces.")
    public static final class Diff implements Callable<Integer>, PorterSubcommand {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Mixin
        CommonOptions common;

        @Option(names = "--a", paramLabel = "<jar|trace>", description = "Side A: mod jar (usually 1.19.2) or an existing *.trace.json.")
        Path a;

        @Option(names = "--b", paramLabel = "<jar|trace>", description = "Side B: mod jar (usually 1.7.10) or an existing *.trace.json.")
        Path b;

        @Option(names = "--a-version", paramLabel = "<platform>", description = "Default: from the jar, else forge-1.19.2.")
        String aVersion;

        @Option(names = "--b-version", paramLabel = "<platform>", description = "Default: from the jar, else forge-1.7.10.")
        String bVersion;

        @Option(names = "--a-mods", split = ",", paramLabel = "<jar>")
        List<Path> aMods;

        @Option(names = "--b-mods", split = ",", paramLabel = "<jar>")
        List<Path> bMods;

        @Option(names = "--a-ids", paramLabel = "<file>")
        Path aIds;

        @Option(names = "--b-ids", paramLabel = "<file>")
        Path bIds;

        @Override
        public String path() {
            return "test diff";
        }

        @Override
        public Integer call() {
            RunReport report = new RunReport("test diff", spec.commandLine().getParseResult().originalArgs());
            try {
                SideSpec sa = side(a, aVersion, aMods, aIds);
                SideSpec sb = side(b, bVersion, bMods, bIds);
                int rc = diff(spec, common, sa, sa.version("forge-1.19.2"), null, sb, sb.version("forge-1.7.10"), null, report);
                return rc;
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("porter test diff: " + e.getMessage());
                report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
                return ExitCodes.ERROR;
            }
        }
    }

    static SideSpec side(Path jar, String version, List<Path> mods, Path ids) {
        SideSpec s = new SideSpec();
        s.jarOrTrace = jar;
        s.version = version;
        s.mods = CommonOptions.jars(mods);
        s.ids = ids;
        return s;
    }

    static int diff(CommandLine.Model.CommandSpec spec, CommonOptions common, SideSpec sa, String va, String la,
            SideSpec sb, String vb, String lb, RunReport report) {
        PrintWriter out = spec.commandLine().getOut();
        ScenarioRunner runner = (sa.isTrace() && sb.isTrace()) ? null : common.runner(out);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean ok = true;
        int equalCount = 0;
        List<Scenario> scenarios = common.loadScenarios();
        var gui = new dev.porter.testkit.runner.gui.GuiReport(common.outDir().resolve("gui-report"));
        boolean parallel = !common.sequential && dev.porter.core.cli.Jobs.get() > 1 && !va.equals(vb) && !sa.isTrace() && !sb.isTrace();
        List<JsonNode> tracesA;
        List<JsonNode> tracesB;
        if (parallel) {
            // both sides at once: separate run dirs (<scenario|batch>-<version>), ports (per version) and trace files
            var fb = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> obtainAll(scenarios, sb, vb, runner, common.outDir(), lb, !common.noBatch));
            try {
                tracesA = obtainAll(scenarios, sa, va, runner, common.outDir(), la, !common.noBatch);
            } catch (RuntimeException e) {
                fb.cancel(true);
                throw e;
            }
            try {
                tracesB = fb.join();
            } catch (java.util.concurrent.CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        } else {
            tracesA = obtainAll(scenarios, sa, va, runner, common.outDir(), la, !common.noBatch);
            tracesB = obtainAll(scenarios, sb, vb, runner, common.outDir(), lb, !common.noBatch);
        }
        for (int si = 0; si < scenarios.size(); si++) {
            Scenario sc = scenarios.get(si);
            JsonNode ta = tracesA.get(si);
            JsonNode tb = tracesB.get(si);
            var ga = guiSet(sc, sa, va, common.outDir(), la);
            var gb = guiSet(sc, sb, vb, common.outDir(), lb);
            if (ga != null && gb == null) {
                gui.failedSide(ga, false, line(tb));
            } else if (gb != null && ga == null) {
                gui.failedSide(gb, true, line(ta));
            } else if (ga != null) {
                gui.compare(ga, gb);
            }
            TraceDiff.Result d = TraceDiff.compare(sc, ta, tb);
            ObjectNode rep = d.report();
            Path diffFile = common.outDir().resolve(sc.id() + ".diff.json");
            writeJson(diffFile, rep);
            boolean passed = ta.path("status").asString().equals("passed") && tb.path("status").asString().equals("passed");
            if (dev.porter.core.cli.Compact.verbose()) {
                out.println(sc.id() + ":");
                out.println("  A " + line(ta));
                out.println("  B " + line(tb));
                out.println("  diff: " + rep.path("matched").asInt() + "/" + rep.path("samples").asInt() + " samples match"
                        + (d.equal() ? " (equal)" : " (DIFFERENT, see " + diffFile + ")"));
            } else {
                // one line per scenario; the details of a problem follow it
                out.println(sc.id() + ": " + rep.path("matched").asInt() + "/" + rep.path("samples").asInt() + " samples match"
                        + (d.equal() ? " (equal)" : " (DIFFERENT, see " + diffFile + ")")
                        + (ta.path("status").asString().equals("passed") ? "" : "; A " + dev.porter.core.cli.Compact.clip(line(ta), 160))
                        + (tb.path("status").asString().equals("passed") ? "" : "; B " + dev.porter.core.cli.Compact.clip(line(tb), 160)));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scenario", sc.id());
            r.put("statusA", ta.path("status").asString());
            r.put("statusB", tb.path("status").asString());
            r.put("samples", rep.path("samples").asInt());
            r.put("matched", rep.path("matched").asInt());
            r.put("equal", d.equal());
            r.put("diff", diffFile.toString());
            results.add(r);
            ok &= passed && d.equal();
            if (passed && d.equal()) {
                equalCount++;
            }
        }
        out.println("scenarios matching in both versions: " + equalCount + "/" + scenarios.size());
        ok &= !guiReport(out, common.outDir(), gui, va + (la == null ? "" : " " + la), vb + (lb == null ? "" : " " + lb), report);
        report.put("a", va).put("b", vb).put("results", results).put("matching", equalCount).put("scenarios", scenarios.size());
        report.write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, ok ? ExitCodes.OK : ExitCodes.FAILED);
        return ok ? ExitCodes.OK : ExitCodes.FAILED;
    }

    // ------------------------------------------------------------------ test roundtrip

    @Command(name = "roundtrip", description = "Round-trip test in one version: original mod vs. its extract->generate rebuild, same scenarios, traces compared.")
    public static final class Roundtrip implements Callable<Integer>, PorterSubcommand {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Mixin
        CommonOptions common;

        @Option(names = "--mod", required = true, paramLabel = "<jar|trace>", description = "Original mod jar (or its trace).")
        Path mod;

        @Option(names = "--roundtrip", paramLabel = "<jar|trace>", description = "Rebuilt mod jar (pipeline output) or its trace.")
        Path roundtrip;

        @Option(names = "--version", paramLabel = "<platform>", description = "Default: from --mod, else forge-1.19.2.")
        String version;

        @Option(names = "--mods", split = ",", paramLabel = "<jar>", description = "Dependencies for both runs.")
        List<Path> mods;

        @Option(names = "--ids", paramLabel = "<file>")
        Path ids;

        @Override
        public String path() {
            return "test roundtrip";
        }

        @Override
        public Integer call() {
            RunReport report = new RunReport("test roundtrip", spec.commandLine().getParseResult().originalArgs());
            if (roundtrip == null) {
                spec.commandLine().getErr().println("porter test roundtrip: --roundtrip <jar> is required; building it "
                        + "(extract -> generate -> compile) is the extract-generate-compile pipeline, not part of testkit yet");
                report.put("error", "no --roundtrip jar").write(RunReport.STATUS_NOT_IMPLEMENTED, ExitCodes.NOT_IMPLEMENTED);
                return ExitCodes.NOT_IMPLEMENTED;
            }
            try {
                SideSpec o = side(mod, version, mods, ids);
                SideSpec r = side(roundtrip, version, mods, ids);
                String v = o.version("forge-1.19.2");
                return diff(spec, common, o, v, "original", r, v, "roundtrip", report);
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("porter test roundtrip: " + e.getMessage());
                report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
                return ExitCodes.ERROR;
            }
        }
    }

    @Command(name = "obtainable", description = {"Survival check between two builds of a mod: its items that nothing makes.",
        "Inputs: dumps of a dedicated server with the whole pack (testkit mod, -Dporter.dumpObtainable=<file>): registered items, "
            + "crafting and smelting outputs. Results of the IR's custom (machine) recipes count as made. Fails when build B "
            + "leaves more items unobtainable than build A."})
    public static final class Obtainable implements Callable<Integer>, PorterSubcommand {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Option(names = "--a", required = true, paramLabel = "<dump.json>", description = "Dump of the reference build.")
        Path a;

        @Option(names = "--b", required = true, paramLabel = "<dump.json>", description = "Dump of the new build.")
        Path b;

        @Option(names = "--mod", required = true, paramLabel = "<modid>", description = "Mod whose items are checked.")
        String mod;

        @Option(names = "--ir", paramLabel = "<dir|file>", description = "IR whose custom recipe results count as made (ids ns:path -> <modid>:path). Repeatable.")
        List<Path> ir = new ArrayList<>();

        @Override
        public String path() {
            return "test obtainable";
        }

        @Override
        public Integer call() {
            RunReport report = new RunReport("test obtainable", spec.commandLine().getParseResult().originalArgs());
            PrintWriter out = spec.commandLine().getOut();
            java.util.Set<String> machine = new java.util.TreeSet<>();
            for (Path p : ir) {
                List<Path> files = new ArrayList<>();
                if (Files.isDirectory(p)) {
                    try (java.util.stream.Stream<Path> s = Files.walk(p)) {
                        s.filter(f -> f.toString().endsWith(".ir.json")).sorted().forEach(files::add);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                } else {
                    files.add(p);
                }
                for (Path f : files) {
                    for (JsonNode n : Json.json().readTree(f.toFile()).path("nodes")) {
                        if (!"recipe".equals(n.path("kind").asString("")) || !"custom".equals(n.path("recipeType").asString(""))) {
                            continue;
                        }
                        List<JsonNode> res = new ArrayList<>();
                        n.path("results").forEach(res::add);
                        if (n.has("result")) {
                            res.add(n.get("result"));
                        }
                        for (JsonNode r : res) {
                            String id = r.path("item").asString(null);
                            if (id != null && id.contains(":")) {
                                machine.add(mod + ":" + id.substring(id.indexOf(':') + 1));
                            }
                        }
                    }
                }
            }
            List<String> ua = unobtainable(a, machine);
            List<String> ub = unobtainable(b, machine);
            out.printf("porter test obtainable: %s items made by nothing: A %d, B %d%n", mod, ua.size(), ub.size());
            List<String> onlyB = new ArrayList<>(ub);
            onlyB.removeAll(ua);
            List<String> onlyA = new ArrayList<>(ua);
            onlyA.removeAll(ub);
            out.println("  only in B: " + onlyB);
            out.println("  only in A: " + onlyA);
            report.put("a", ua).put("b", ub).put("onlyB", onlyB).put("onlyA", onlyA);
            boolean ok = ub.size() <= ua.size();
            report.write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, ok ? ExitCodes.OK : ExitCodes.FAILED);
            return ok ? ExitCodes.OK : ExitCodes.FAILED;
        }

        private List<String> unobtainable(Path dump, java.util.Set<String> machine) {
            JsonNode d = Json.json().readTree(dump.toFile());
            java.util.Set<String> made = new java.util.TreeSet<>(machine);
            for (String k : List.of("crafting", "smelting")) {
                for (JsonNode x : d.path(k)) {
                    String s = x.asString();
                    made.add(s.contains("@") ? s.substring(0, s.indexOf('@')) : s);
                }
            }
            List<String> res = new ArrayList<>();
            for (JsonNode x : d.path("items")) {
                String s = x.asString();
                if (s.startsWith(mod + ":") && !made.contains(s)) {
                    res.add(s);
                }
            }
            return res;
        }
    }
}
