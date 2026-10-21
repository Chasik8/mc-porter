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

/** {@code porter port --config port.yaml}: the end-to-end port, resumable by stage. */
@Command(name = "port", description = {
        "Run a whole port from a config file (contracts/port-config.schema.json), resumable by stage.",
        "Stages: generate, closure, rewrite, install, loop, compile, build, test (only those configured).",
        "By default the run resumes at the first stage that has not finished successfully."})
public final class PortCommand implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = {"-c", "--config"}, required = true, paramLabel = "<port.yaml>", description = "Port config file.")
    Path config;

    @Option(names = "--stage", paramLabel = "<stage>", description = "Run only this stage.")
    String only;

    @Option(names = "--from", paramLabel = "<stage>", description = "Start at this stage (default: resume).")
    String from;

    @Option(names = "--to", paramLabel = "<stage>", description = "Stop after this stage.")
    String to;

    @Option(names = "--restart", description = "Ignore the saved progress and start at the first stage.")
    boolean restart;

    @Option(names = "--status", description = "Print the stage status and exit.")
    boolean status;

    @Option(names = "--dry-run", description = "Validate the config and print the plan without running it.")
    boolean dryRun;

    @Override
    public String path() {
        return "port";
    }

    @Override
    public Integer call() {
        List<String> args = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());
        RunReport report = new RunReport("port", args);
        ContractValidator.FileResult v = ContractValidator.get().validateFile(config, Contract.PORT_CONFIG);
        if (!v.valid()) {
            System.err.println("porter port: invalid config " + config + ":");
            v.issues().forEach(i -> System.err.println("    " + i));
            report.put("issues", v.issues()).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        PortConfig cfg = PortConfig.load(config);
        PortRunner runner = new PortRunner(cfg, System.out);
        if (status) {
            for (String s : PortRunner.STAGES) {
                System.out.printf("%-9s %s%n", s, cfg.has(s) ? runner.status(s) : "-");
            }
            return ExitCodes.OK;
        }
        List<String> plan;
        try {
            plan = runner.plan(from, to, only, restart);
        } catch (IllegalArgumentException e) {
            System.err.println("porter port: " + e.getMessage());
            return ExitCodes.USAGE;
        }
        System.out.println("port " + cfg.name() + ": " + cfg.from() + " -> " + cfg.to() + ", project " + cfg.project() + ", work " + cfg.work());
        System.out.println("plan: " + (plan.isEmpty() ? "(nothing to do; --restart or --from <stage> to re-run)" : String.join(" -> ", plan)));
        if (dryRun || plan.isEmpty()) {
            report.put("plan", plan).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
        boolean ok = runner.run(plan);
        report.put("plan", plan).put("work", cfg.work().toString());
        report.write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, ok ? ExitCodes.OK : ExitCodes.FAILED);
        return ok ? ExitCodes.OK : ExitCodes.FAILED;
    }
}
