package dev.porter.port.select;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.node.ObjectNode;

/** {@code porter select}: one port unit's IR from a whole-mod extract (see {@link IrSelect}). */
@Command(name = "select", description = {"Select a subsystem's IR from a whole-mod extract: seed ids, references to a fixpoint, recipes, unknowns, class relocation, overlay.",
        "--make-overlay writes the overlay that turns the plain selection into an existing IR (hand edits recorded as data)."})
public final class SelectCommand implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--ir", required = true, paramLabel = "<dir>", description = "Extracted IR (*.ir.json of the whole mod).")
    Path ir;

    @Option(names = "--spec", required = true, paramLabel = "<yaml>", description = "Selection spec (paths in it are relative to the spec file).")
    Path specFile;

    @Option(names = "--sources", paramLabel = "<dir>", description = "Source-version sources for the screen-class convention (overrides screenConvention.sources).")
    Path sources;

    @Option(names = "--out", paramLabel = "<dir>", description = "Write <unit>.<kind>.ir.json + select-report.json here.")
    Path out;

    @Option(names = "--make-overlay", paramLabel = "<dir>", description = "IR the selection should become (<unit>.<kind>.ir.json): write the overlay instead of the IR.")
    Path makeFrom;

    @Option(names = "--overlay-out", paramLabel = "<file>", description = "Where --make-overlay writes the overlay (YAML).")
    Path overlayOut;

    @Override
    public String path() {
        return "select";
    }

    @Override
    public Integer call() throws Exception {
        RunReport report = new RunReport("select", spec.commandLine().getParseResult().originalArgs());
        Path base = specFile.toAbsolutePath().getParent();
        IrSelect.Spec s0 = IrSelect.readSpec(specFile, p -> base.resolve(p).normalize(), makeFrom != null);
        IrSelect.Spec s = sources == null ? s0 : new IrSelect.Spec(s0.unit(), s0.modId(), s0.seed(), s0.customTypes(), s0.unknownFiles(), s0.hostIds(),
                s0.hostKeep(), s0.relocate(), s0.generic(), s0.legacyFrom(), s0.legacyTo(), s0.legacySource(), s0.screenReplace(), s0.screenSuffix(),
                sources, s0.idKeys(), s0.overlay());
        if (makeFrom != null) {
            IrSelect.Spec plain = new IrSelect.Spec(s.unit(), s.modId(), s.seed(), s.customTypes(), s.unknownFiles(), s.hostIds(), s.hostKeep(),
                    s.relocate(), s.generic(), s.legacyFrom(), s.legacyTo(), s.legacySource(), s.screenReplace(), s.screenSuffix(),
                    s.screenSources(), s.idKeys(), null);
            IrSelect.Result r = IrSelect.select(ir, plain);
            Map<String, ObjectNode> target = new LinkedHashMap<>(IrSelect.load(makeFrom));
            ObjectNode ov = Overlay.make(r.docs(), target);
            Map<String, ObjectNode> check = new LinkedHashMap<>();
            r.docs().forEach((k, v) -> check.put(k, v.deepCopy()));
            Overlay.apply(check, ov, null);
            check.keySet().retainAll(target.keySet());
            boolean same = Overlay.same(check, target);
            if (overlayOut == null) {
                throw new CommandLine.ParameterException(spec.commandLine(), "--make-overlay needs --overlay-out");
            }
            if (overlayOut.toAbsolutePath().getParent() != null) {
                Files.createDirectories(overlayOut.toAbsolutePath().getParent());
            }
            Files.writeString(overlayOut, Json.yaml().writeValueAsString(ov));
            spec.commandLine().getOut().println("porter select: overlay with " + ov.path("nodes").size() + " node operations -> " + overlayOut
                    + (same ? " (reproduces the target IR)" : " (WARNING: node order differs from the target IR)"));
            report.put("operations", ov.path("nodes").size()).put("reproduces", same).write(RunReport.STATUS_OK, ExitCodes.OK);
            return same ? ExitCodes.OK : ExitCodes.FAILED;
        }
        if (out == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--out is required");
        }
        IrSelect.Result r = IrSelect.select(ir, s);
        IrSelect.write(r, s.unit(), out);
        spec.commandLine().getOut().println("porter select " + s.unit() + ": " + r.report().get("counts") + " -> " + out);
        report.put("counts", r.report().get("counts")).write(RunReport.STATUS_OK, ExitCodes.OK);
        return ExitCodes.OK;
    }
}
