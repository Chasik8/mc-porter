package dev.porter.graph.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.graph.GraphBuilder;
import dev.porter.graph.load.Fetcher;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter graph build [--versions 1.19.2,1.18.2,...] [--cache dir] [--out dir]}. */
@Command(name = "build", description = "Build the symbol graph: download/verify jars and mappings, match adjacent versions, write *.graph.json.")
public final class GraphBuildCommand implements Callable<Integer>, PorterSubcommand {
    public static final List<String> ALL = List.of("1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.7.10");

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--versions", split = ",", paramLabel = "<v>",
            description = "Versions to include (adjacent ones are matched). Default: ${DEFAULT-VALUE}.",
            defaultValue = "1.19.2,1.18.2,1.16.5,1.12.2,1.7.10")
    List<String> versions;

    @Option(names = "--cache", paramLabel = "<dir>", description = "Download cache (default $PORTER_CACHE/graph or ~/.cache/porter/graph).")
    Path cache;

    @Option(names = "--out", paramLabel = "<dir>", description = "Output graph directory (default <cache>/out).")
    Path out;

    @Option(names = "--offline", description = "Fail instead of downloading missing inputs.")
    boolean offline;

    @Option(names = "--no-matcher", description = "Name anchors only (skip FabricMC Matcher bytecode matching).")
    boolean noMatcher;

    @Override
    public String path() {
        return "graph build";
    }

    @Override
    public Integer call() {
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport(path(), spec.commandLine().getParseResult().originalArgs());
        Path cacheDir = cache != null ? cache : Fetcher.defaultCacheDir();
        Path outDir = out != null ? out : cacheDir.resolve("out");
        long t0 = System.nanoTime();
        try {
            var stats = GraphBuilder.build(new GraphBuilder.Options(cacheDir, outDir, versions, offline, !noMatcher), msg -> {
                err.println("[graph] " + msg);
                err.flush();
            });
            long secs = (System.nanoTime() - t0) / 1_000_000_000L;
            report.put("out", outDir.toAbsolutePath().toString()).put("seconds", secs).put("stats", stats);
            spec.commandLine().getOut().println("graph written to " + outDir.toAbsolutePath() + " in " + secs + " s");
            report.write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IllegalArgumentException e) {
            err.println("porter graph build: " + e.getMessage());
            report.put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        } catch (Exception e) {
            err.println("porter graph build: " + e);
            e.printStackTrace(err);
            report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            return ExitCodes.ERROR;
        }
    }
}
