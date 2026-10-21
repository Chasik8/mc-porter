package dev.porter.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.RunReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Placeholder for a subcommand whose module has not registered an implementation yet. */
@Command
final class StubCommand implements Callable<Integer> {
    private final String path;
    private final String description;

    // No mixinStandardHelpOptions: it would claim --version, which several commands use for a Minecraft version.
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Unmatched
    List<String> args = new ArrayList<>();

    StubCommand(String path, String description) {
        this.path = path;
        this.description = description;
    }

    @Override
    public Integer call() {
        Path report = new RunReport(path, args)
                .put("message", "not implemented")
                .put("description", description)
                .write(RunReport.STATUS_NOT_IMPLEMENTED, ExitCodes.NOT_IMPLEMENTED);
        System.err.println("porter " + path + ": not implemented (report: " + report + ")");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
