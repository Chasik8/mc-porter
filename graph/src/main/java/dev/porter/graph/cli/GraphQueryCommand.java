package dev.porter.graph.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.contract.ValidationIssue;
import dev.porter.core.json.Json;
import dev.porter.graph.io.GraphFiles;
import dev.porter.graph.load.Fetcher;
import dev.porter.graph.model.SymbolRef;
import dev.porter.graph.query.QueryResult;
import dev.porter.graph.query.SymbolGraph;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * {@code porter graph query <symbol> --from 1.19.2 --to 1.7.10}: resolves the symbol in the source version and follows
 * the edges through the version chain. Prints one contract {@code queryResult} document per resolved symbol (pretty
 * when there is exactly one, otherwise JSON Lines).
 */
@Command(name = "query", description = "Query a symbol through the version chain; prints symbol-graph queryResult documents.")
public final class GraphQueryCommand implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Parameters(arity = "0..1", paramLabel = "<symbol>",
            description = "a.b.C | a.b.C#name | a.b.C#name(JVM-desc) | a.b.C#name(Type, Type); names in any namespace of --from.")
    String symbol;

    @Option(names = "--batch", paramLabel = "<file>", description = "File with one symbol spec per line (# comments allowed).")
    Path batch;

    @Option(names = "--from", required = true, paramLabel = "<version>")
    String from;

    @Option(names = "--to", required = true, paramLabel = "<version>")
    String to;

    @Option(names = "--graph", paramLabel = "<dir>", description = "Built graph directory (default <cache>/out, see graph build).")
    Path graph;

    @Option(names = "--out", paramLabel = "<file>", description = "Write the documents (JSON Lines) to a file instead of stdout.")
    Path out;

    @Option(names = "--no-validate", description = "Skip contract validation of the output documents.")
    boolean noValidate;

    @Override
    public String path() {
        return "graph query";
    }

    @Override
    public Integer call() {
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport(path(), spec.commandLine().getParseResult().originalArgs());
        if ((symbol == null) == (batch == null)) {
            err.println("porter graph query: give either <symbol> or --batch <file>");
            report.put("error", "symbol or --batch required").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        Path dir = graph != null ? graph : Fetcher.defaultCacheDir().resolve("out");
        if (!Files.isRegularFile(GraphFiles.manifestFile(dir))) {
            err.println("porter graph query: no graph in " + dir + " (run porter graph build, or pass --graph)");
            report.put("error", "no graph at " + dir).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        try {
            SymbolGraph g = SymbolGraph.open(dir);
            if (!g.versions().contains(from) || !g.versions().contains(to)) {
                err.println("porter graph query: graph has versions " + g.versions());
                report.put("error", "unknown version").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
                return ExitCodes.USAGE;
            }
            List<String> specs = new ArrayList<>();
            if (batch != null) {
                for (String line : Files.readAllLines(batch, StandardCharsets.UTF_8)) {
                    String t = line.strip();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        specs.add(t);
                    }
                }
            } else {
                specs.add(symbol);
            }
            List<Map<String, Object>> docs = new ArrayList<>();
            Map<String, Integer> byStatus = new TreeMap<>();
            List<String> unresolved = new ArrayList<>();
            for (String s : specs) {
                List<SymbolRef> refs = g.resolve(from, s);
                if (refs.isEmpty()) {
                    unresolved.add(s);
                    err.println("porter graph query: '" + s + "' not found in " + from);
                    continue;
                }
                for (SymbolRef r : refs) {
                    QueryResult q = g.query(r, to);
                    byStatus.merge(q.missing() != null ? "missing" : q.status().json, 1, Integer::sum);
                    docs.add(q.toJson());
                }
            }
            if (!noValidate) {
                for (Map<String, Object> d : docs) {
                    JsonNode n = Json.json().valueToTree(d);
                    List<ValidationIssue> issues = ContractValidator.get().validate(Contract.SYMBOL_GRAPH, n);
                    if (!issues.isEmpty()) {
                        throw new IllegalStateException("internal: queryResult violates the contract: " + issues);
                    }
                }
            }
            boolean pretty = docs.size() == 1 && out == null && batch == null;
            if (out != null) {
                Path parent = out.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    for (Map<String, Object> d : docs) {
                        w.write(GraphFiles.COMPACT.writeValueAsString(d));
                        w.write('\n');
                    }
                }
            } else {
                PrintWriter o = spec.commandLine().getOut();
                for (Map<String, Object> d : docs) {
                    o.println(pretty ? Json.json().writeValueAsString(d).replace("\r\n", "\n") : GraphFiles.COMPACT.writeValueAsString(d));
                }
                o.flush();
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("specs", specs.size());
            summary.put("documents", docs.size());
            summary.put("unresolved", unresolved.size());
            summary.put("unresolvedSpecs", unresolved.subList(0, Math.min(50, unresolved.size())));
            summary.put("byStatus", byStatus);
            report.put("graph", dir.toAbsolutePath().toString()).put("summary", summary);
            int code = docs.isEmpty() ? ExitCodes.FAILED : ExitCodes.OK;
            report.write(code == 0 ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            return code;
        } catch (IOException | RuntimeException e) {
            err.println("porter graph query: " + e);
            report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            return ExitCodes.ERROR;
        }
    }
}
