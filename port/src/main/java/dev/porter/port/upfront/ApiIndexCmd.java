package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.port.start.Home;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter api-index}: builds the cached lineage / target API / source hierarchy / srg indexes for 1.19.2 to 1.7.10
 * (see {@link ApiIndex}). Defaults come from the porter cache ({@code $PORTER_CACHE}, else {@code ~/.porter/cache}).
 */
@Command(name = "api-index", description = {"Build the per-version-pair API indexes for the upfront translation layer: composed lineage "
    + "1.19.2 -> 1.7.10, declared API of the 1.7.10 jar, 1.19.2 class hierarchy, srg names. Cached by input hash."})
public final class ApiIndexCmd implements Callable<Integer>, PorterSubcommand {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--graph", paramLabel = "<dir>", description = "Output of `porter graph build` (edges-*.graph.json). Default: <cache>/graph/out.")
    Path graph;

    @Option(names = "--target-jar", paramLabel = "<jar>", description = "1.7.10 Minecraft+Forge jar with MCP names. Default: <cache>/forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar.")
    Path targetJar;

    @Option(names = "--source-jars", paramLabel = "<jar>", split = ",", arity = "1..*",
            description = "1.19.2 Minecraft (mojmap) and Forge jars. Default: the compile jar and the Forge universal jar in the cache.")
    List<Path> sourceJars;

    @Option(names = "--tiny-1710", paramLabel = "<file>", description = "1.7.10 tiny mapping (obf srg mcp). Default: <graph>/mappings/1.7.10.tiny.")
    Path tiny;

    @Option(names = "--forge-version", paramLabel = "<v>", description = "Forge version of the default source jars.", defaultValue = "1.19.2-43.5.2")
    String forge;

    @Option(names = "--out", paramLabel = "<dir>", description = "Default: <cache>/upfront/1.19.2__1.7.10.")
    Path out;

    @Option(names = "--force", description = "Rebuild although the input hashes match the manifest.")
    boolean force;

    @Override
    public String path() {
        return "api-index";
    }

    @Override
    public Integer call() {
        String env = System.getenv("PORTER_CACHE");
        Path cache = env != null && !env.isBlank() ? Path.of(env) : Home.defaultRoot().resolve("cache");
        Path g = graph != null ? graph : cache.resolve("graph/out");
        Path tj = targetJar != null ? targetJar : cache.resolve("forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar");
        List<Path> sj = sourceJars;
        if (sj == null) {
            sj = new ArrayList<>();
            sj.add(cache.resolve("forge-1.19.2-compile/mc-forge-1.19.2-client-mojmap.jar"));
            sj.add(cache.resolve("forge-1.19.2-client/libraries/net/minecraftforge/forge/" + forge + "/forge-" + forge + "-universal.jar"));
        }
        Path t = tiny != null ? tiny : g.resolve("mappings/1.7.10.tiny");
        Path o = out != null ? out : cache.resolve("upfront/1.19.2__1.7.10");
        long t0 = System.nanoTime();
        try {
            ApiIndex.Stats s = ApiIndex.build(new ApiIndex.Inputs(g, tj, sj, t, List.of("net.minecraft.", "net.minecraftforge.", "com.mojang.")), o, force);
            System.out.println("api-index " + (s.cached() ? "up to date" : "built") + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms: lineage "
                    + s.lineageRows() + " rows, target " + s.classes() + " classes / " + s.members() + " members, source "
                    + s.sourceClasses() + " classes, srg " + s.srgNames() + " names -> " + o);
            return ExitCodes.OK;
        } catch (IllegalArgumentException e) {
            System.err.println("api-index: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }
}
