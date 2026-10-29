package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter bodies check}: compile the bodies DB against the 1.7.10 jar and the adapter pack, one javac. */
@Command(name = "check", description = {
        "Splices the bodies into shimgen's output and compiles them all in one javac (--release 8) against --cp.",
        "Passing bodies get 'verified: compile' in their YAML file; failing keys are listed with the first error.",
        "Exit 0 when every filled body compiles, 1 otherwise."})
public final class BodiesCheckCmd implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--bodies", required = true, paramLabel = "<dir|file>", description = "Bodies DB directory or one YAML file; repeat for several.")
    List<Path> bodies = new ArrayList<>();

    @Option(names = "--gen", required = true, paramLabel = "<dir>", description = "The shimgen output directory (src/, fragments/, todo.tsv).")
    Path gen;

    @Option(names = "--cp", paramLabel = "<jar>[;<jar>...]",
            description = "Class path (repeatable, separator-joined). Default: --target-jar, the Forge 1.7.10 libraries from the launcher version json and the adapter source dirs.")
    List<String> cp = new ArrayList<>();

    @Option(names = "--target-jar", paramLabel = "<mcp.jar>", description = "1.7.10 MCP jar for the default class path (default <cache>/forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar).")
    Path targetJar;

    @Option(names = "--cache", paramLabel = "<dir>", description = "Porter cache (default $PORTER_CACHE, else ~/.porter/cache).")
    Path cache;

    @Option(names = "--mc-dir", paramLabel = "<dir>", description = "Minecraft directory with versions/ and libraries/ (default $PORTER_MINECRAFT, %APPDATA%/.minecraft).")
    Path mcDir;

    @Option(names = "--version-id", paramLabel = "<id>", description = "Forge version folder/json name (default 'Forge 1.7.10').")
    String versionId = "Forge 1.7.10";

    @Option(names = "--adapters", paramLabel = "<dir>", description = "Adapter source dir for the default class path; repeatable (default rewrite/shim/src/{adapters,main}/java).")
    List<Path> adapters = new ArrayList<>();

    @Option(names = "--helpers", paramLabel = "<dir>", description = "Generic helper sources compiled with the bodies (default: <first bodies dir>/helpers).")
    Path helpers;

    @Option(names = "--todo", paramLabel = "<todo.tsv>", description = "TODO list for the todo count (default: <gen>/todo.tsv).")
    Path todo;

    @Option(names = "--work", paramLabel = "<dir>", description = "Scratch directory for the spliced sources (default: <gen>/check).")
    Path work;

    @Option(names = "--no-write", description = "Do not update 'verified:' in the YAML files.")
    boolean noWrite;

    @Option(names = "--report", paramLabel = "<file.tsv>", description = "Write 'key status message' lines (optional).")
    Path report;

    @Override
    public String path() {
        return "bodies check";
    }

    @Override
    public Integer call() {
        RunReport rr = new RunReport("bodies check", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        try {
            Map<SymbolKey, Path> fileOf = new HashMap<>();
            Map<SymbolKey, BodyEntry> db = BodiesCheck.load(bodies, fileOf);
            List<Path> classpath = new ArrayList<>();
            for (String s : cp) {
                for (String p : s.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (!p.isBlank()) {
                        classpath.add(Path.of(p));
                    }
                }
            }
            if (classpath.isEmpty()) {
                Path jar = targetJar != null ? targetJar
                        : (cache != null ? cache : UpfrontCmd.defaultCache()).resolve("forge-1.7.10-compile/mc-forge-1.7.10-mcp.jar");
                classpath = TargetClasspath.build(jar, mcDir != null ? mcDir : TargetClasspath.defaultMinecraftDir(), versionId,
                        adapters.isEmpty() ? TargetClasspath.adapterDirs(Path.of("")) : adapters);
            }
            Path h = helpers;
            if (h == null && !bodies.isEmpty() && Files.isDirectory(bodies.get(0))) {
                h = bodies.get(0).resolve("helpers");
            }
            Path t = todo != null ? todo : gen.resolve("todo.tsv");
            BodiesCheck.Result r = BodiesCheck.check(db, gen, classpath, h, t, work != null ? work : gen.resolve("check"));
            if (!noWrite && r.genErrors().isEmpty()) {
                BodiesCheck.markVerified(fileOf, db, r.ok(), r.failed().keySet());
            }
            for (Map.Entry<SymbolKey, String> f : r.failed().entrySet()) {
                System.out.println("FAIL " + f.getKey() + "\n     " + f.getValue());
            }
            for (String g : r.genErrors()) {
                System.out.println("GENERATED-CODE ERROR " + g);
            }
            for (SymbolKey o : r.orphans()) {
                System.out.println("warning: body for a key that shimgen did not generate: " + o);
            }
            System.out.println("bodies check: " + r.bodies() + " bodies, " + r.ok().size() + " ok, " + r.failed().size()
                    + " failed, " + r.orphans().size() + " orphan, " + r.todo() + " todo (no body), "
                    + r.genErrors().size() + " generated-code errors (" + r.javacMillis() + " ms)");
            if (report != null) {
                StringBuilder sb = new StringBuilder("key\tstatus\tmessage\n");
                r.ok().forEach(k -> sb.append(k).append("\tok\t\n"));
                r.failed().forEach((k, m) -> sb.append(k).append("\tfail\t").append(m).append('\n'));
                r.orphans().forEach(k -> sb.append(k).append("\torphan\t\n"));
                Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
            }
            int code = r.success() ? ExitCodes.OK : ExitCodes.FAILED;
            rr.put("bodies", r.bodies()).put("ok", r.ok().size()).put("failed", r.failed().size()).put("todo", r.todo())
                    .write(r.success() ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            return code;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            System.err.println("porter bodies check: " + e.getMessage());
            rr.put("error", String.valueOf(e.getMessage())).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
    }
}
