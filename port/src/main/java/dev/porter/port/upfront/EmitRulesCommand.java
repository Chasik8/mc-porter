package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.port.upfront.model.MatchTable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter emit-rules}: turns the unresolved rows of a match table into rule files (see {@link RuleEmitter}).
 * It runs as the {@code emit-rules} stage of {@code porter upfront}; {@code porter match} only writes the table.
 */
@Command(name = "emit-rules", description = {"Writes rules for the unresolved rows of a match table (map, expand, registry, mirror, ext files).",
    "Rows already resolved by a rule or the graph are skipped; rows that cannot be a rule are listed with the reason."})
public final class EmitRulesCommand implements Callable<Integer>, PorterSubcommand {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--table", required = true, paramLabel = "<match-table.json|tsv>", description = "The match table.")
    Path table;

    @Option(names = "--concepts", paramLabel = "<concepts.yaml>", description = "Concept plans (argument expansion, result wrappers).")
    Path concepts;

    @Option(names = "--rules", paramLabel = "<dir|file>", description = "Existing rules; their type rules say where a moved class's members live. Repeatable.")
    java.util.List<Path> rules = new java.util.ArrayList<>();

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Output directory for the upfront-*.rule.yaml files.")
    Path out;

    @Option(names = "--from", defaultValue = "forge-1.19.2", description = "Source platform.")
    String from;

    @Option(names = "--to", defaultValue = "forge-1.7.10", description = "Target platform.")
    String to;

    @Override
    public String path() {
        return "emit-rules";
    }

    @Override
    public Integer call() {
        try {
            MatchTable t = table.toString().endsWith(".tsv") ? MatchTable.readTsv(table) : MatchTable.read(table);
            Concepts c = concepts == null ? Concepts.empty() : Concepts.load(concepts);
            java.util.List<dev.porter.core.rule.Rule> existing = new java.util.ArrayList<>();
            for (Path p : rules) {
                for (dev.porter.core.rule.Rule r : java.nio.file.Files.isDirectory(p) ? dev.porter.core.rule.RuleIo.readAll(p) : dev.porter.core.rule.RuleIo.read(p)) {
                    if (r.from().equals(from) && r.to().equals(to)) {
                        existing.add(r);
                    }
                }
            }
            RuleEmitter.Options o = new RuleEmitter.Options(RuleEmitter.versionOf(from), RuleEmitter.versionOf(to), from, to, c, Map.of(), RuleEmitter.typeRuleMap(existing));
            RuleEmitter.Result res = new RuleEmitter(o).emit(t);
            RuleEmitter.write(res, out);
            res.files().forEach((f, l) -> System.out.println(f + ": " + l.size() + " rules"));
            System.out.println("rules: " + res.ruleCount() + ", skipped rows: " + res.skipped().size());
            java.util.Map<String, Integer> why = new java.util.TreeMap<>();
            res.skipped().values().forEach(v -> why.merge(v, 1, Integer::sum));
            why.forEach((k, v) -> System.out.println("  skipped " + k + ": " + v));
            return ExitCodes.OK;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
