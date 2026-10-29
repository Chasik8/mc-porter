package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.port.config.PortConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter precheck --config port.yaml}: the fast check to run before every full {@code porter port}. */
@Command(name = "precheck", description = {
        "Fast check before a full port run (about a minute instead of six): validates the rule files (schema, duplicate",
        "ids, same-priority conflicts), rewrites only the classes the changed rules/overlays/edits touch, runs the install",
        "passes, the loop and javac+castfix on them against the last build. Exit 1 with the same error summary the full run",
        "prints, plus the normalised error classes (work/precheck/error-classes.tsv). Needs one full run's output."})
public final class PrecheckCommand implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = {"-c", "--config"}, required = true, paramLabel = "<port.yaml>", description = "Port config file.")
    Path config;

    @Option(names = "--changed", split = ",", paramLabel = "<file|dir>",
            description = "Rule files, overlay files/dirs or the edits file that changed (default: found against the baseline the rewrite/install stages write).")
    List<Path> changed = new ArrayList<>();

    @Option(names = "--class", split = ",", paramLabel = "<a.b.C>", description = "Also check these closure classes (source names).")
    List<String> classes = new ArrayList<>();

    @Option(names = "--baseline", description = "Mark the current rules, overlays and edits as built (for runs made before the baseline existed) and exit.")
    boolean baseline;

    @Option(names = "--rules-only", description = "Only validate the rule files (seconds).")
    boolean rulesOnly;

    @Option(names = "--max-classes", defaultValue = "80", description = "Check at most this many affected classes (default ${DEFAULT-VALUE}); the rest is reported as partial.")
    int maxClasses;

    @Override
    public String path() {
        return "precheck";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("precheck", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        ContractValidator.FileResult v = ContractValidator.get().validateFile(config, Contract.PORT_CONFIG);
        if (!v.valid()) {
            System.err.println("porter precheck: invalid config " + config + ":");
            v.issues().forEach(i -> System.err.println("    " + i));
            report.put("issues", v.issues()).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        PortConfig cfg = PortConfig.load(config);
        int code;
        try {
            code = new Precheck(cfg, System.out).run(new Precheck.Options(changed, classes, baseline, rulesOnly, maxClasses));
        } catch (IllegalStateException e) {
            System.err.println("porter precheck: " + e.getMessage());
            code = ExitCodes.USAGE;
        }
        report.put("work", cfg.work().toString()).write(code == ExitCodes.OK ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
        return code;
    }
}
