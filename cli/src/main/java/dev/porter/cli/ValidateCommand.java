package dev.porter.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.contract.ContractValidator.FileResult;
import dev.porter.core.contract.Documents;
import dev.porter.core.contract.ValidationIssue;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code porter validate <file|dir>...}: validates documents against the contracts.
 *
 * <p>Contract selection per file: {@code --schema}, else the file name suffix ({@code .ir.json}, {@code .rule.yaml},
 * {@code .graph.json}, {@code .scenario.yaml}, {@code .trace.json}, {@code .candidate.json}, {@code .candidates.jsonl}),
 * else characteristic top-level fields. Directories are walked recursively and only files with a contract suffix
 * are validated. Exit code 0 if everything is valid, 1 otherwise.
 */
@Command(name = "validate",
        description = "Validate files against the porter contracts (contracts/*.schema.json + semantic checks).")
final class ValidateCommand implements Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--schema", paramLabel = "<contract>",
            description = "Force the contract: ir, rule, symbol-graph, scenario, trace, candidate, port-config, port-catalog.")
    String schema;

    @Option(names = {"-q", "--quiet"}, description = "Only print invalid files.")
    boolean quiet;

    @Parameters(arity = "1..*", paramLabel = "<path>", description = "Files or directories.")
    List<Path> paths;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport("validate", spec.commandLine().getParseResult().originalArgs());
        Contract forced = null;
        if (schema != null) {
            forced = Contract.byId(schema).orElse(null);
            if (forced == null) {
                err.println("porter validate: unknown contract '" + schema + "' (expected one of ir, rule, symbol-graph, scenario, trace, candidate, port-config, port-catalog)");
                report.put("error", "unknown contract " + schema).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
                return ExitCodes.USAGE;
            }
        }
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    final Contract f = forced;
                    walk.filter(Files::isRegularFile)
                            .filter(x -> f != null ? Documents.isSupported(x) : Contract.bySuffix(x.getFileName().toString()).isPresent())
                            .sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    err.println("porter validate: cannot walk " + p + ": " + e.getMessage());
                    report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
                    return ExitCodes.ERROR;
                }
            } else {
                files.add(p);
            }
        }
        int invalid = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Path file : files) {
            FileResult r = ContractValidator.get().validateFile(file, forced);
            String contract = r.contract().map(Contract::id).orElse("?");
            if (r.valid()) {
                if (!quiet) {
                    out.println("OK       " + file + " (" + contract + ", " + r.documents() + " doc" + (r.documents() == 1 ? "" : "s") + ")");
                }
            } else {
                invalid++;
                out.println("INVALID  " + file + " (" + contract + ")");
                for (ValidationIssue i : r.issues()) {
                    out.println("    " + (r.documents() > 1 ? "doc " + i.document() + " " : "") + i);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", file.toString().replace('\\', '/'));
            entry.put("contract", contract);
            entry.put("valid", r.valid());
            entry.put("issues", r.issues());
            results.add(entry);
        }
        if (files.isEmpty()) {
            err.println("porter validate: no files to validate");
        }
        out.println(files.size() + " file(s), " + invalid + " invalid");
        out.flush();
        int code = invalid == 0 && !files.isEmpty() ? ExitCodes.OK : ExitCodes.FAILED;
        report.put("files", files.size()).put("invalid", invalid).put("results", results)
                .write(code == ExitCodes.OK ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
        return code;
    }
}
