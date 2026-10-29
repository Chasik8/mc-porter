package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter api-inventory}: every distinct 1.19.2 platform symbol a mod references (upfront translation layer). */
@Command(name = "api-inventory", description = {
        "Every distinct 1.19.2 platform symbol (vanilla, Forge, Mojang libraries) a mod jar references: owner, kind, descriptor,",
        "use count and the declaring class. Bytecode first (sees Forge members, constructors and Forge-patched methods that",
        "'porter coverage' does not), sources second (the coverage universe). Writes api-inventory.json."})
public final class ApiInventoryCmd implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--jar", required = true, paramLabel = "<mod.jar>", description = "The mod jar built for the source version.")
    Path jar;
    @Option(names = "--src", paramLabel = "<dir>", description = "Decompiled sources: adds the coverage universe (imports, SRG ids).")
    Path src;
    @Option(names = "--classes", paramLabel = "<list.txt>", description = "Only these top-level classes, nested ones included (repeatable).")
    List<Path> classes = new ArrayList<>();
    @Option(names = "--srg-tiny", paramLabel = "<1.19.2.tiny>", description = "Tiny v2 with mojmap+srg: SRG ids to mojmap names, descriptors.")
    Path tiny;
    @Option(names = "--hier", paramLabel = "<source-hier.tsv>", description = "Platform hierarchy: class, super, interfaces, access (tab separated).")
    List<Path> hier = new ArrayList<>();
    @Option(names = "--platform-jar", paramLabel = "<jar>", description = "Class jar of the platform (members, access, Forge); repeatable.")
    List<Path> platformJars = new ArrayList<>();
    @Option(names = "--platform", paramLabel = "<prefix>", description = "Extra platform package prefix (repeatable).")
    List<String> platform = new ArrayList<>();
    @Option(names = "--no-overrides", description = "Do not add platform methods the mod overrides.")
    boolean noOverrides;
    @Option(names = "--scope", paramLabel = "<name>", description = "Scope label in the output (default: from the first --classes file name).")
    String scope;
    @Option(names = "--from", defaultValue = "forge-1.19.2")
    String from;
    @Option(names = "--out", required = true, paramLabel = "<api-inventory.json>")
    Path out;

    @Override
    public String path() {
        return "api-inventory";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport(path(), spec.commandLine().getParseResult().originalArgs());
        try {
            PlatformIndex idx = new PlatformIndex();
            for (Path h : hier) {
                idx.loadHier(h);
            }
            for (Path j : platformJars) {
                idx.loadJar(j);
            }
            if (tiny != null) {
                idx.loadTiny(tiny);
            }
            ApiInventoryScanner.Result r = new ApiInventoryScanner(idx,
                    new ApiInventoryScanner.Options(jar, src, classes, platform, !noOverrides)).scan();
            List<ApiSymbol> syms = new ArrayList<>();
            for (ApiInventoryScanner.Sym s : r.symbols()) {
                SymbolKey k = SymbolKey.parse(s.key());
                syms.add(new ApiSymbol(k, s.uses(), s.origin, s.access, s.owner, new ArrayList<>(s.refClasses), null));
            }
            String sc = scope;
            if (sc == null) {
                sc = classes.isEmpty() ? "all" : classes.get(0).getFileName().toString().replaceAll("-?classes\\.txt$", "").replaceAll("\\.txt$", "");
                if (sc.isEmpty()) {
                    sc = "all";
                }
            }
            List<String> lists = classes.stream().map(p -> p.getFileName().toString()).toList();
            ApiInventory inv = new ApiInventory(ApiInventory.SCHEMA_VERSION, from, sc,
                    new ApiInventory.Sources(jar.toString(), src == null ? null : src.toString(), lists), syms,
                    new ApiInventory.Summary(r.types(), r.members(), r.byOrigin()));
            if (out.getParent() != null) {
                java.nio.file.Files.createDirectories(out.getParent());
            }
            inv.write(out);
            long refsOnly = r.symbols().stream().filter(s -> !s.kind.equals("type")
                    && (s.origin.contains("bytecode") || s.origin.contains("source"))).count();
            System.out.printf(java.util.Locale.ROOT, "api-inventory: %d classes scanned (%d in jar), %d sources; types %d, members %d (%d without override-only), byOrigin %s%n",
                    r.scannedClasses(), r.jarClasses(), r.sourceFiles(), r.types(), r.members(), refsOnly, r.byOrigin());
            System.out.println("wrote " + out);
            report.put("summary", java.util.Map.of("types", r.types(), "members", r.members())).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("porter api-inventory: " + e);
            return ExitCodes.FAILED;
        }
    }
}
