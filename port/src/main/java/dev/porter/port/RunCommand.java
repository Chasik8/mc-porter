package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.port.config.PortConfig;
import dev.porter.port.run.Proc;
import dev.porter.port.start.Fail;
import dev.porter.port.start.Forge1192To1710;
import dev.porter.port.start.Home;
import dev.porter.port.start.Inputs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * {@code porter run <port-dir>}: the one command a player needs. Checks the original files, does the one-time setup
 * (JDKs and Gradle are fetched by the scripts and Gradle, everything else here), then runs {@code porter port}.
 * See contracts/rfc/007-port-inputs-and-run.md.
 */
@Command(name = "run", description = {
        "Port a mod with one command: check the original files (inputs), do the one-time setup, run the port.",
        "<port-dir> is a directory under ports/ (with <name>.port.yaml) or a port config file."})
public final class RunCommand implements PorterSubcommand, Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Parameters(index = "0", paramLabel = "<port-dir>", description = "Port directory (ports/<mod>) or port config file.")
    Path target;

    @Option(names = "--input", paramLabel = "<name=path>", description = "Where an input file is (repeatable). Without it porter looks next to the port, in ./, and in your Downloads folder.")
    Map<String, Path> given = new LinkedHashMap<>();

    @Option(names = "--home", paramLabel = "<dir>", description = "Where downloads, caches and the workspace go (default: $PORTER_HOME, else ~/.porter). Delete it to remove everything porter fetched.")
    Path homeDir;

    @Option(names = "--out", paramLabel = "<dir>", description = "Where the finished jar is copied (default: ./output).")
    Path outDir;

    @Option(names = "--verify", description = "Also run the differential scenario tests (for contributors: needs the original jar and the testkit servers, see testkit/README.md; set PORTER_TESTKIT_ENV).")
    boolean verify;

    @Option(names = "--restart", description = "Run the port from its first stage again.")
    boolean restart;

    @Option(names = "--check-inputs", description = "Only check the input files and exit.")
    boolean checkOnly;

    @Override
    public String path() {
        return "run";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("run", new ArrayList<>(spec.commandLine().getParseResult().originalArgs()));
        long t0 = System.nanoTime();
        java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(out);
        Home home = new Home(homeDir != null ? homeDir : Home.defaultRoot(), out);
        try {
            int code = go(home, report);
            report.write(code == 0 ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            if (code == 0) {
                System.out.println("Total time " + fmt(Math.round((System.nanoTime() - t0) / 1e9)) + ".");
            }
            return code;
        } catch (Fail e) {
            System.err.println();
            System.err.println("Cannot continue:");
            System.err.println();
            System.err.println(e.getMessage());
            report.put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
            return ExitCodes.FAILED;
        }
    }

    private static String fmt(long secs) {
        return secs < 120 ? secs + " s" : (secs / 60) + " min " + (secs % 60) + " s";
    }

    private Path configFile() {
        if (!Files.exists(target)) {
            throw new Fail("There is no such port: " + target + "\nPorts live in the ports/ directory, for example: porter run ports/torchmaster");
        }
        if (Files.isRegularFile(target)) {
            return target;
        }
        try (Stream<Path> s = Files.list(target)) {
            List<Path> l = s.filter(p -> p.getFileName().toString().endsWith(".port.yaml")).sorted().toList();
            if (l.size() != 1) {
                throw new Fail(target + " must contain exactly one *.port.yaml (found " + l.size() + ").");
            }
            return l.get(0);
        } catch (IOException e) {
            throw new Fail("cannot read " + target + ": " + e.getMessage(), e);
        }
    }

    private int go(Home home, RunReport report) {
        Path cfgFile = configFile().toAbsolutePath().normalize();
        Path portDir = cfgFile.getParent();
        ContractValidator.FileResult v = ContractValidator.get().validateFile(cfgFile, Contract.PORT_CONFIG);
        if (!v.valid()) {
            throw new Fail("The port file " + cfgFile + " is not valid:\n    " + v.issues().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n    ")));
        }
        JsonNode raw = PortConfig.raw(cfgFile);
        String name = raw.path("name").asString();
        String from = raw.path("from").asString();
        String to = raw.path("to").asString();
        if (!Forge1192To1710.supports(from, to)) {
            throw new Fail("`porter run` can set up only Forge 1.19.2 -> Forge 1.7.10 ports so far (this one is " + from + " -> " + to
                    + ").\nYou can still run it step by step with `porter port --config " + cfgFile + "`.");
        }
        System.out.println("Port: " + name + " - " + raw.path("description").asString(""));
        System.out.println("Workspace and downloads: " + home.root());
        Path run = home.run(name);

        if (verify && (System.getenv("PORTER_TESTKIT_ENV") == null || given.get("original") == null)) {
            throw new Fail("--verify runs the scenario tests on real Minecraft servers of both versions. That setup is not automated yet:\n"
                    + "install the testkit servers as described in testkit/README.md, set PORTER_TESTKIT_ENV to the properties file, and give the original jar (--input original=<jar>).\n"
                    + "Without --verify, porter builds the port and stops after the build.");
        }

        // 1. the original files
        System.out.println();
        System.out.println("== Checking your files");
        List<Path> search = new ArrayList<>(List.of(portDir.resolve("inputs"), portDir, Path.of("").toAbsolutePath()));
        search.add(Path.of(System.getProperty("user.home"), "Downloads"));
        Map<String, Inputs.Found> inputs = Inputs.resolve(Inputs.specs(raw), given, search, home.downloads(), run.resolve("inputs"), System.out);
        if (checkOnly) {
            System.out.println("All input files are as expected.");
            return 0;
        }
        Map<String, String> vars = new LinkedHashMap<>(home.vars(name));
        vars.put("PORT_DIR", portDir.toString());
        for (Inputs.Found f : inputs.values()) {
            vars.put(f.spec().variable(), f.file().toString());
            if (f.dir() != null) {
                vars.put(f.spec().variable() + "_DIR", f.dir().toString());
            }
        }
        for (Inputs.Spec s : Inputs.specs(raw)) {
            vars.putIfAbsent(s.variable(), "");
        }

        // 2. one-time setup for the version pair
        System.out.println();
        Forge1192To1710 pair = new Forge1192To1710(home);
        pair.ensure();
        pair.project(portDir, run.resolve("project"));

        // 3. prepare steps and the classpath file
        PortConfig cfg = PortConfig.load(cfgFile, vars);
        prepare(home, cfg, run);
        classpathFile(home, cfg);

        // 4. the port
        System.out.println();
        System.out.println("== Porting " + name);
        PortRunner runner = new PortRunner(cfg, System.out);
        String stop = cfg.has("test") && !verify ? "build" : null;
        List<String> plan;
        try {
            plan = runner.plan(null, stop, null, restart);
        } catch (IllegalArgumentException e) {
            throw new Fail(e.getMessage());
        }
        System.out.println("plan: " + (plan.isEmpty() ? "(already done)" : String.join(" -> ", plan)));
        if (!plan.isEmpty() && !runner.run(plan)) {
            throw new Fail("The port did not finish. See the report: " + cfg.work().resolve("port-report.md")
                    + "\nand the logs in " + cfg.work().resolve("logs") + "\nSee the guide 'Port your own mod' in the README for how to read the report; run the same command again to resume.");
        }
        if (verify && !cfg.has("test")) {
            System.out.println("(this port has no scenario tests to run)");
        }

        // 5. result
        Path jar = result(cfg);
        System.out.println();
        System.out.println("Done. Your ported mod:");
        System.out.println();
        System.out.println("    " + jar);
        System.out.println();
        System.out.println("Put this jar into the mods folder of a Minecraft 1.7.10 Forge instance (Forge 10.13.4.1614).");
        System.out.println("What was and was not ported: " + cfg.work().resolve("port-report.md"));
        report.put("jar", jar.toString());
        return 0;
    }

    private Path result(PortConfig cfg) {
        JsonNode b = cfg.section("build");
        if (!b.has("jar")) {
            return cfg.work();
        }
        Path built = cfg.work().resolve("out").resolve(Path.of(b.get("jar").asString()).getFileName());
        Path dst = (outDir != null ? outDir : Path.of("output")).toAbsolutePath().resolve(built.getFileName());
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(built, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new Fail("cannot copy the jar to " + dst + ": " + e.getMessage(), e);
        }
        return dst;
    }

    /** {@code prepare:} steps, each skipped when it already ran with the same arguments. */
    private void prepare(Home home, PortConfig cfg, Path run) {
        for (JsonNode p : cfg.section("prepare")) {
            String pname = p.path("name").asString();
            List<String> args = PortConfig.strings(p.path("args"));
            Path stamp = run.resolve("stamps").resolve("prepare-" + pname);
            String key = String.join("\n", args);
            try {
                if (Files.exists(stamp) && Files.readString(stamp).equals(key)) {
                    System.out.println("-- " + pname + ": already done");
                    continue;
                }
                System.out.println("== " + pname);
                Path work = cfg.work();
                List<String> cmd = Proc.porter(List.of(), PortConfig.strings(p.path("jvmArgs")), work.resolve("reports"),
                        work.resolve("args/prepare-" + pname + ".args"), args);
                Proc.Result r = Proc.run(cmd, cfg.dir(), Proc.classpathEnv(), work.resolve("logs/prepare-" + pname + ".log"), null);
                if (!r.ok()) {
                    throw new Fail("Step '" + pname + "' failed. Log: " + r.log());
                }
                Files.createDirectories(stamp.getParent());
                Files.writeString(stamp, key);
            } catch (IOException e) {
                throw new Fail("cannot write " + stamp + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * {@code classpath.file}: written by the project's {@code porterClasspath} Gradle task. A config with its own
     * {@code setup.steps} makes the file there instead (it needs libraries copied in first). {@code compileJava} on the still empty
     * project makes Gradle fetch the Java 8 toolchain, Minecraft and the Jabel compiler plugin that the compile stage uses.
     */
    private void classpathFile(Home home, PortConfig cfg) {
        JsonNode cp = cfg.section("classpath");
        if (!cp.has("file") || Files.exists(cfg.project().resolve(cp.get("file").asString())) || cfg.section("setup").has("steps")) {
            return;
        }
        System.out.println("== Minecraft 1.7.10 development environment (Gradle downloads Minecraft, Forge and the Java 8 toolchain)  (about 10 min)");
        List<String> cmd = new ArrayList<>(PortRunner.windowsWrapper(List.of("./gradlew", "--no-daemon", "--console=plain", "-q", "porterClasspath", "compileJava"), cfg.project()));
        Map<String, String> env = Map.of("JAVA_HOME", System.getProperty("java.home"), "GRADLE_USER_HOME", home.gradle().toString());
        Proc.Result r = Proc.run(cmd, cfg.project(), env, home.logs().resolve("porterClasspath.log"), null);
        if (!r.ok()) {
            throw new Fail("Gradle could not set up the 1.7.10 project (needs internet access). Log: " + r.log());
        }
    }
}
