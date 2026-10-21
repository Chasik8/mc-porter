package dev.porter.generate;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.ContractViolationException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter generate --ir ir/ --version 1.7.10 --out gen/}: IR → source tree of a mod for the target version.
 *
 * <p>The output is a Gradle-agnostic tree ({@code src/main/java}, {@code src/main/resources}) plus
 * {@code porter-generate.json} (files, stubs, allocated ids, decisions). Logic classes named by the IR are not
 * generated: they get stubs that the rewriter output replaces.
 */
@Command(name = "generate", description = "Generate code/assets for a version from IR.")
public final class GenerateCommand implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--ir", required = true, description = "IR file or directory (repeatable; *.ir.json / *.ir.yaml).")
    List<Path> ir;

    @Option(names = "--version", required = true, description = "Target Minecraft version (1.7.10, 1.19.2).")
    String version;

    @Option(names = "--out", required = true, description = "Output directory.")
    Path out;

    @Option(names = "--package", description = "Base Java package (default: <mod main class package>.gen).")
    String basePackage;

    @Option(names = "--modid", description = "Target mod id (default: IR mod id).")
    String modId;

    @Option(names = "--mod-name", description = "Mod display name (default: IR mod name).")
    String modName;

    @Option(names = "--mod-version", description = "Mod version (default: IR mod version).")
    String modVersion;

    @Option(names = "--embed", description = "No @Mod class: the host mod calls <Mod>Content.preInit/init/postInit.")
    boolean embed;

    @Option(names = "--gui-ids", defaultValue = "0-99", description = "GUI id range for menus (1.7.10), default ${DEFAULT-VALUE}.")
    String guiIds;

    @Option(names = "--potion-ids", defaultValue = "24-31", description = "Potion id range for effects (1.7.10), default ${DEFAULT-VALUE}.")
    String potionIds;

    @Option(names = "--channel", description = "Network channel name (default: mod id, max 20 chars on 1.7.10).")
    String channel;

    @Option(names = "--assets", description = "Output directory of `porter assets` (assets/ is copied, meta/blocks.textures.ir.json merged).")
    Path assets;

    @Option(names = "--asset-namespace", description = "Rename the mod's asset namespace (texture domain), e.g. exampleaddon.")
    String assetNamespace;

    @Option(names = "--legacy-names", defaultValue = "PATH",
            description = "Unlocalized-name style when IR has no porter:legacyName: ${COMPLETION-CANDIDATES} (default ${DEFAULT-VALUE}); must match `porter assets`.")
    GenOptions.LegacyNames legacyNames;

    @Option(names = "--no-stubs", description = "Do not emit stubs for logic classes (compile together with the rewriter output).")
    boolean noStubs;

    @Option(names = "--id-map", description = "Id map (JSON/YAML, sections blocks/items/fluids: IR id -> 'modid:name[@meta]' or {id, meta}) for referenced objects that are not generated (host mod, renamed vanilla ids). Same format as testkit id maps.")
    Path idMap;

    @Option(names = "--no-nei", description = "1.7.10: no NotEnoughItems integration (default: generated; NEI is a compileOnly dependency).")
    boolean noNei;

    @Option(names = "--dry-run", description = "Generate in memory and report, write nothing.")
    boolean dryRun;

    @Override
    public String path() {
        return "generate";
    }

    @Override
    public Integer call() {
        PrintWriter outW = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport("generate", spec.commandLine().getParseResult().originalArgs());
        Optional<TargetGenerator> gen = Generate.generator(version);
        if (gen.isEmpty()) {
            String known = String.join(", ", Generate.generators().stream().map(TargetGenerator::version).toList());
            err.println("porter generate: no generator for version " + version + " (available: " + known + ")");
            report.put("error", "no generator for " + version).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        GenOptions options;
        try {
            options = new GenOptions(gen.get().version(), basePackage, modId, modName, modVersion, embed,
                    GenOptions.IntRange.parse(guiIds), GenOptions.IntRange.parse(potionIds), channel, assets,
                    assetNamespace, legacyNames, !noStubs, idMap == null ? Map.of() : IdMaps.load(idMap), !noNei);
        } catch (IllegalArgumentException e) {
            err.println("porter generate: " + e.getMessage());
            report.put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        try {
            IrIndex index = IrIndex.load(ir);
            Generate.Result r = Generate.run(gen.get(), index, options);
            GenContext ctx = r.context();
            int removed = dryRun ? 0 : ctx.out.write(out, r.manifest());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("generator", gen.get().platform());
            details.put("out", out.toString().replace('\\', '/'));
            details.put("irFiles", index.files());
            details.put("irCounts", index.counts());
            details.put("files", ctx.out.files().size());
            details.put("javaFiles", ctx.out.javaFiles().size());
            details.put("stubs", ctx.out.stubs().size());
            details.put("staleRemoved", removed);
            details.put("decisions", ctx.decisionSummary());
            details.forEach(report::put);
            report.write(RunReport.STATUS_OK, ExitCodes.OK);
            outW.printf("porter generate: %s -> %s: %d files (%d java, %d stubs), %d decisions%n", gen.get().platform(),
                    out, ctx.out.files().size(), ctx.out.javaFiles().size(), ctx.out.stubs().size(), ctx.decisions().size());
            return ExitCodes.OK;
        } catch (ContractViolationException e) {
            err.println("porter generate: invalid IR: " + e.getMessage());
            report.put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
            return ExitCodes.FAILED;
        } catch (GenerationException e) {
            err.println("porter generate: " + e.getMessage());
            report.put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
            return ExitCodes.FAILED;
        } catch (RuntimeException e) {
            err.println("porter generate: internal error: " + e);
            report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            return ExitCodes.ERROR;
        }
    }
}
