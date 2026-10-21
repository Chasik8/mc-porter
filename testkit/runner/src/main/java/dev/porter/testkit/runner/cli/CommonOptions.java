package dev.porter.testkit.runner.cli;

import dev.porter.core.cli.RunReport;
import dev.porter.testkit.runner.Env;
import dev.porter.testkit.runner.IrIndex;
import dev.porter.testkit.runner.McLauncher;
import dev.porter.testkit.runner.Scenario;
import dev.porter.testkit.runner.ScenarioRunner;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import picocli.CommandLine.Option;

/** Options shared by the testkit subcommands. */
public final class CommonOptions {
    @Option(names = "--scenario", required = true, paramLabel = "<file|dir>", split = ",",
            description = "Scenario file(s) or directories (*.scenario.yaml).")
    List<Path> scenarios;

    @Option(names = "--ir", paramLabel = "<file|dir>", split = ",",
            description = "IR files/dirs with the multiblock and menu nodes the scenarios reference.")
    List<Path> ir = new ArrayList<>();

    @Option(names = "--env", paramLabel = "<file>", description = "testkit environment (default testkit/env.local.properties or $PORTER_TESTKIT_ENV).")
    Path env;

    @Option(names = "--work", paramLabel = "<dir>", description = "Run directories (default: <tmp>/porter-testkit).")
    Path work;

    @Option(names = "--out-dir", paramLabel = "<dir>", description = "Where traces and diff reports go (default <reports>/traces).")
    Path outDir;

    @Option(names = "--screens", paramLabel = "<dir>", description = "Screenshot directory (default <reports>/screens).")
    Path screens;

    @Option(names = "--timeout", paramLabel = "<s>", description = "Wall-clock limit per Minecraft launch (default 600).")
    int timeout = 600;

    @Option(names = "--retries", paramLabel = "<n>", description = "Relaunches after a hang/crash (default 1).")
    int retries = 1;

    @Option(names = "--no-batch", description = "One Minecraft launch per scenario (default: all server scenarios of a "
            + "version and mod set run in ONE launch, the world is reset between them).")
    boolean noBatch;

    List<Scenario> loadScenarios() {
        List<Scenario> out = new ArrayList<>();
        for (Path p : scenarios) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    for (Path f : s.filter(x -> x.getFileName().toString().matches(".*\\.scenario\\.(yaml|yml|json)")).sorted().toList()) {
                        out.addAll(Scenario.read(f));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                out.addAll(Scenario.read(p));
            }
        }
        return out;
    }

    Path outDir() {
        return outDir != null ? outDir : RunReport.reportsDir().resolve("traces");
    }

    ScenarioRunner runner(PrintWriter log) {
        Env e = Env.load(env);
        Path w = work != null ? work : Path.of(System.getProperty("java.io.tmpdir"), "porter-testkit");
        // launch notes ("[testkit] ... attempt 1 in <dir>") only with --verbose; failures end up in the trace message
        PrintWriter notes = dev.porter.core.cli.Compact.verbose() ? log : new PrintWriter(java.io.Writer.nullWriter());
        McLauncher l = new McLauncher(e, w, timeout, retries, notes);
        Path sc = screens != null ? screens : RunReport.reportsDir().resolve("screens");
        return new ScenarioRunner(l, IrIndex.read(ir), sc, Path.of("").toAbsolutePath());
    }

    static List<Path> jars(List<Path> in) {
        return in == null ? List.of() : in;
    }
}
