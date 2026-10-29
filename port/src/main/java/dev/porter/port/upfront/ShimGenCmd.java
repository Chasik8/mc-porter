package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.upfront.model.MatchTable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter shimgen}: Java 8 shim skeletons from the match table (bodies from the bodies DB, else typed TODOs). */
@Command(name = "shimgen", description = {
        "Writes shim skeletons for the match-table rows planned MIRROR, HINT or TODO (and MAP/EXPAND rows with a shim):",
        "new mirror classes, <Shim>.members.java fragments for existing shims, and <Simple>Ext helpers for mapped types.",
        "Bodies come from the bodies DB, delegates for rows with a target, else typed TODOs listed in todo.tsv."})
public final class ShimGenCmd implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--table", required = true, paramLabel = "<match-table.json>", description = "The match table (JSON).")
    Path table;

    @Option(names = "--concepts", paramLabel = "<concepts.yaml>", description = "Concept shims (optional).")
    Path concepts;

    @Option(names = "--shims", paramLabel = "<dir>", description = "Shim source root; repeat for several. Members found there are never generated.")
    List<Path> shims = new ArrayList<>();

    @Option(names = "--bodies", paramLabel = "<dir>", description = "Bodies DB directory (optional).")
    Path bodies;

    @Option(names = "--inventory", paramLabel = "<api-inventory.json>", description = "Access flags, so static members are generated static (optional).")
    Path inventory;

    @Option(names = "--hierarchy", paramLabel = "<classes.tsv>", description = "'class <fqn> <super> <itf,itf> <access>' lines: extends/implements of mirrors (optional).")
    Path hierarchy;

    @Option(names = "--target", paramLabel = "<target.tsv>", description = "api-index target.tsv: constructors of the 1.7.10 classes, so a generated class is not "
            + "written with an 'extends' whose constructors it cannot call (optional).")
    Path target;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Output directory (src/ and fragments/ below it).")
    Path out;

    @Option(names = "--todo", paramLabel = "<todo.tsv>", description = "TODO list (default: <out>/todo.tsv).")
    Path todo;

    @Override
    public String path() {
        return "shimgen";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("shimgen", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        try {
            long t0 = System.currentTimeMillis();
            MatchTable mt = MatchTable.read(table);
            ShimGen.Input in = new ShimGen.Input(mt, ShimGen.readConceptShims(concepts), ShimIndex.scan(shims),
                    BodiesDb.load(bodies), ShimGen.readHierarchy(hierarchy), ShimGen.readAccess(inventory));
            ShimGen.Output o = new ShimGen(in).defaultCtor(ShimGen.defaultCtorCheck(in.shims(), target))
                    .interfaceKind(ShimGen.interfaceKindCheck(in.shims(), target)).run();
            ShimGen.write(o, out, todo != null ? todo : out.resolve("todo.tsv"));
            o.warnings().forEach(w -> System.err.println("shimgen: warning: " + w));
            System.out.println("shimgen: " + o.files().size() + " files, " + o.generated() + " members, "
                    + o.todos().size() + " todo, " + o.withBody() + " from the bodies DB, " + o.skippedExisting()
                    + " already in the shims (" + (System.currentTimeMillis() - t0) + " ms)");
            report.put("files", o.files().size()).put("todo", o.todos().size()).put("warnings", o.warnings().size())
                    .write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("porter shimgen: " + e.getMessage());
            report.put("error", String.valueOf(e.getMessage())).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
    }
}
