package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.rewrite.graph.GraphRuleGenerator;
import dev.porter.rewrite.graph.SymbolGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter match}: the match table (symbol, plan, target, confidence, method) for every inventory symbol. */
@Command(name = "match", description = {"Decides for every API symbol of an inventory what it becomes on the target version: lineage, scored candidates, registry, mirror.",
    "Data-driven by match-tables.yaml; --eval compares the table with the hand rules (precision/recall)."})
public final class MatchCmd implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--inventory", required = true, paramLabel = "<api-inventory.json>")
    Path inventory;
    @Option(names = "--index", required = true, paramLabel = "<api-index dir>")
    Path index;
    @Option(names = "--tables", required = true, paramLabel = "<match-tables.yaml>")
    Path tables;
    @Option(names = "--concepts", paramLabel = "<concepts.yaml>")
    Path concepts;
    @Option(names = "--rules", paramLabel = "<dir|file>", description = "Existing (hand) rules; repeatable.")
    List<Path> rules = new ArrayList<>();
    @Option(names = "--graph", paramLabel = "<dir|file>", description = "Symbol-graph documents: the mechanical rules count as resolvedBy=graph.")
    List<Path> graphs = new ArrayList<>();
    @Option(names = "--eval-scope", paramLabel = "<dir|file>", description = "With --eval: also count, for the rules of this directory (a profile's hand rules), how many the table reproduces by itself; repeatable.")
    List<Path> evalScope = new ArrayList<>();
    @Option(names = "--registry",paramLabel = "<registry.tsv>", description = "Flattening table (porter flattening): REGISTRY rows get its 1.7.10 expression as target.")
    Path registry;
    @Option(names = "--from", defaultValue = "forge-1.19.2")
    String from;
    @Option(names = "--to", defaultValue = "forge-1.7.10")
    String to;
    @Option(names = "--out", required = true, paramLabel = "<dir>")
    Path out;
    @Option(names = "--eval", description = "Compare with the hand rules and write eval.txt.")
    boolean eval;

    @Override
    public String path() {
        return "match";
    }

    @Override
    public Integer call() {
        try {
            MatchTables t = MatchTables.load(tables);
            IndexData idx = IndexData.read(index);
            ConceptDescs c = concepts == null ? ConceptDescs.empty() : ConceptDescs.load(concepts);
            List<Rule> hand = new ArrayList<>();
            for (Path p : rules) {
                for (Rule r : Files.isDirectory(p) ? RuleIo.readAll(p) : RuleIo.read(p)) {
                    if (r.from().equals(from) && r.to().equals(to)) {
                        hand.add(r);
                    }
                }
            }
            List<Rule> gen = new ArrayList<>();
            if (!graphs.isEmpty()) {
                String bf = from.substring(from.indexOf('-') + 1);
                String bt = to.substring(to.indexOf('-') + 1);
                gen.addAll(new GraphRuleGenerator(bf, bt, from, to).generate(SymbolGraph.load(graphs, bf)).rules());
            }
            ApiInventory inv = ApiInventory.read(inventory);
            Matcher m = new Matcher(t, idx, c, hand, gen);
            MatchTable table = m.match(inv);
            if (registry != null) {
                RegistryJoin.Joined j = RegistryJoin.apply(table, registry);
                table = j.table();
                System.out.printf(Locale.ROOT, "registry join: %d registry rows, %d with a flattening target, %d need a damage value, %d without counterpart%n",
                        j.registryRows(), j.withTarget(), j.needsMeta(), j.absent());
            }
            Files.createDirectories(out);
            table.write(out.resolve("match-table.tsv"), out.resolve("match-table.json"));
            Map<String, Integer> plans = new TreeMap<>();
            int resolved = 0;
            for (var r : table.rows()) {
                plans.merge(r.plan().name(), 1, Integer::sum);
                if (!"none".equals(r.resolvedBy())) {
                    resolved++;
                }
            }
            System.out.printf(Locale.ROOT, "match: %d symbols, %d resolved by rules/graph, plans %s -> %s%n", table.rows().size(), resolved, plans,
                    out.resolve("match-table.tsv"));
            if (eval) {
                java.util.Set<String> scope = null;
                if (!evalScope.isEmpty()) {
                    scope = new java.util.TreeSet<>();
                    for (Path p : evalScope) {
                        for (Rule r : Files.isDirectory(p) ? RuleIo.readAll(p) : RuleIo.read(p)) {
                            scope.add(r.id());
                        }
                    }
                }
                MatchEval.Result r = MatchEval.run(table, m, idx, scope);
                Files.writeString(out.resolve("eval.txt"), String.join("\n", r.lines()) + "\n");
                r.lines().stream().limit(8).forEach(System.out::println);
                System.out.printf(Locale.ROOT, "eval: types P %.3f R %.3f, members P %.3f R %.3f, unresolved auto %d -> %s%n", r.types().precision(),
                        r.types().recall(), r.members().precision(), r.members().recall(), r.unresolvedAuto(), out.resolve("eval.txt"));
            }
            new RunReport(path(), spec.commandLine().getParseResult().originalArgs()).put("symbols", table.rows().size())
                    .write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Used by tests: the plans of a table. */
    static Plan planOf(MatchTable t, String key) {
        return t.rows().stream().filter(r -> r.key().toString().equals(key)).findFirst().orElseThrow().plan();
    }
}
