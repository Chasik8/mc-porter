package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.MatchTable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter todo-batch}: one batch file per fill worker from shimgen's todo.tsv. */
@Command(name = "todo-batch", description = {
        "Splits todo.tsv into one markdown batch per worker (owners never split, balanced by weight) with",
        "signature, hint, top candidates, use count and call sites; writes <out>/<n>.md and <out>/index.tsv."})
public final class TodoBatchCmd implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--todo", required = true, paramLabel = "<todo.tsv>", description = "The shimgen TODO list.")
    Path todo;

    @Option(names = "--table", paramLabel = "<match-table.json>", description = "Match table; supplies candidates the todo row lacks (optional).")
    Path table;

    @Option(names = "--inventory", paramLabel = "<api-inventory.json>", description = "Inventory with refClasses, for call sites (optional).")
    Path inventory;

    @Option(names = "--src", paramLabel = "<dir>", description = "Source root of the mod (call sites; optional).")
    Path src;

    @Option(names = "--workers", description = "Number of batches (default 8).")
    int workers = 8;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Output directory.")
    Path out;

    @Override
    public String path() {
        return "todo-batch";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("todo-batch", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        try {
            long t0 = System.currentTimeMillis();
            List<TodoEntry> todos = TodoEntry.read(todo);
            TodoBatch.Result r = TodoBatch.split(todos, workers, table == null ? null : MatchTable.read(table),
                    inventory == null ? null : ApiInventory.read(inventory), src);
            TodoBatch.write(r, out);
            System.out.println("todo-batch: " + todos.size() + " entries in " + r.batches().size() + " batches, weight "
                    + String.format(java.util.Locale.ROOT, "%.1f-%.1f", r.minWeight(), r.maxWeight()) + " ("
                    + (System.currentTimeMillis() - t0) + " ms)");
            report.put("entries", todos.size()).put("workers", r.batches().size()).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("porter todo-batch: " + e.getMessage());
            report.put("error", String.valueOf(e.getMessage())).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
    }
}
