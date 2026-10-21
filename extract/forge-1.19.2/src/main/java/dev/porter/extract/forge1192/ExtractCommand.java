package dev.porter.extract.forge1192;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import dev.porter.extract.forge1192.vanilla.VanillaIndexBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter extract --src <mod-src> --version 1.19.2 --out ir/}. */
@Command(name = "extract", description = "Extract IR from mod sources (Forge 1.19.2)")
public final class ExtractCommand implements Callable<Integer>, PorterSubcommand {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean help;

    @Option(names = "--src", required = true, description = "Mod source root (Gradle project, or java/ + res/ decompile)")
    Path src;

    @Option(names = "--version", required = true, description = "Minecraft version of the sources (1.19.2)")
    String version;

    @Option(names = "--out", required = true, description = "Output directory for *.ir.json and extract-report.json")
    Path out;

    @Option(names = "--mod-id", description = "Mod id (default: from META-INF/mods.toml)")
    String modId;

    @Option(names = "--mc-data", description = "Directory with 1.19.2 mappings/jars (default: $PORTER_MC_1192)")
    Path mcData;

    @Option(names = "--pack", description = "Pattern pack to enable (repeatable; default: packs that apply)")
    List<String> packs = new ArrayList<>();

    @Option(names = "--no-auto-packs", description = "Only use packs given with --pack")
    boolean noAutoPacks;

    @Option(names = "--classpath", split = "[;:]", description = "Optional jars for a typed Spoon model")
    List<Path> classpath = new ArrayList<>();

    @Override
    public String path() {
        return "extract";
    }

    @Override
    public Integer call() {
        RunReport rr = new RunReport("extract", List.of("--src", String.valueOf(src), "--version", version, "--out",
                String.valueOf(out)));
        if (!version.equals("1.19.2") && !version.equals("forge-1.19.2")) {
            System.err.println("porter extract: only --version 1.19.2 is supported by this module");
            rr.put("error", "unsupported version " + version).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        if (!Files.isDirectory(src)) {
            System.err.println("porter extract: --src is not a directory: " + src);
            rr.put("error", "no such directory").write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        try {
            Path data = mcData;
            if (data == null && System.getenv("PORTER_MC_1192") != null) {
                data = Path.of(System.getenv("PORTER_MC_1192"));
            }
            VanillaIndex vi = data != null && Files.isDirectory(data) ? VanillaIndexBuilder.loadOrBuild(data)
                    : VanillaIndex.empty();
            if (data == null) {
                System.err.println("porter extract: no --mc-data / PORTER_MC_1192: SRG names and vanilla ids stay unresolved");
            }
            Extractor.Result res = Extractor.run(new Extractor.Options(src, out, modId, vi, packs, !noAutoPacks,
                    classpath));
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> concepts = (Map<String, Map<String, Object>>) res.report().get("concepts");
            System.out.println("concept        recognised  unknown  unknown%");
            for (var e : concepts.entrySet()) {
                System.out.printf("%-14s %10s %8s %8.1f%n", e.getKey(), e.getValue().get("recognised"),
                        e.getValue().get("unknown"), 100 * (double) e.getValue().get("unknownShare"));
            }
            System.out.println("wrote " + res.files().size() + " files to " + out);
            rr.put("summary", res.report().get("concepts")).put("metrics", res.report().get("metrics"))
                    .put("files", res.files().stream().map(Path::toString).toList());
            rr.write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (RuntimeException e) {
            e.printStackTrace();
            rr.put("error", String.valueOf(e)).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            return ExitCodes.ERROR;
        }
    }
}
