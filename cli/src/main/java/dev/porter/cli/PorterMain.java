package dev.porter.cli;

import dev.porter.core.cli.Compact;
import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.KnownCommands;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point of the single {@code porter} binary. Subcommands come from {@link PorterSubcommand} providers on the
 * classpath; every path of {@link KnownCommands#PATHS} without a provider is a stub ({@link StubCommand}).
 */
@Command(name = "porter", mixinStandardHelpOptions = true, version = "porter 0.1.0",
        description = "Ports Minecraft Forge mods between versions via IR, rewrite rules and automatic checks.")
public final class PorterMain implements Runnable {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = "--reports-dir", scope = CommandLine.ScopeType.INHERIT, paramLabel = "<dir>",
            description = "Where JSON run reports go (default: reports, or $PORTER_REPORTS_DIR).")
    void reportsDir(String dir) {
        System.setProperty("porter.reports.dir", dir);
    }

    @Option(names = "--verbose", scope = CommandLine.ScopeType.INHERIT,
            description = "Print the full output (default: a summary of at most ~20 lines; the full output goes to "
                    + "<reports-dir>/logs/). Also PORTER_VERBOSE=1.")
    void verbose(boolean on) {
        System.setProperty("porter.verbose", Boolean.toString(on));
    }

    @Option(names = "--jobs", scope = CommandLine.ScopeType.INHERIT, paramLabel = "<n>",
            description = "Parallel jobs for independent work (default: available cores, or $PORTER_JOBS). 1 = sequential.")
    void jobs(int n) {
        System.setProperty("porter.jobs", Integer.toString(n));
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    /**
     * The CLI entry: runs the command with its output captured; unless {@code --verbose} the console gets a compact
     * summary ({@link Compact}) and the full output goes to {@code <reports-dir>/logs/<command>-<time>.log}. Help,
     * version, short outputs and {@code porter port} (which prints one line per stage itself) pass through unchanged.
     */
    public static int execute(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        installPlugins(System.getenv("PORTER_PLUGINS"));
        List<String> a = List.of(args);
        boolean verbose = a.contains("--verbose") || Compact.verbose();
        String cmd = commandWords(a);
        if (verbose || cmd.isEmpty() || cmd.equals("port") || a.contains("-h") || a.contains("--help") || a.contains("-V")
                || a.contains("--version")) {
            CommandLine cl = commandLine();
            cl.setOut(new java.io.PrintWriter(out, true));
            cl.setErr(new java.io.PrintWriter(err, true));
            return cl.execute(args);
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream cap = new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
        java.io.PrintStream oldOut = System.out;
        java.io.PrintStream oldErr = System.err;
        int rc;
        try {
            System.setOut(cap);
            System.setErr(cap);
            CommandLine cl = commandLine();
            cl.setOut(new java.io.PrintWriter(cap, true));
            cl.setErr(new java.io.PrintWriter(cap, true));
            rc = cl.execute(args);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        cap.flush();
        String text = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> lines = new java.util.ArrayList<>(List.of(text.split("\\R", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        Path log = null;
        if (lines.size() > Compact.MAX_LINES) {
            log = writeLog(cmd, text);
        }
        for (String l : Compact.summarize(lines, rc, log == null ? "(log not written)" : log.toAbsolutePath().toString())) {
            (rc == 0 ? out : err).println(l);
        }
        return rc;
    }

    /** Subcommand path of an argument list (options and their values skipped), e.g. "test diff". */
    static String commandWords(List<String> args) {
        List<String> words = new java.util.ArrayList<>();
        for (int i = 0; i < args.size() && words.size() < 2; i++) {
            String x = args.get(i);
            if (x.equals("--reports-dir") || x.equals("--jobs")) {
                i++;
            } else if (x.startsWith("-") || x.startsWith("@")) {
                if (!words.isEmpty()) {
                    break;
                }
            } else {
                words.add(x);
                if (!x.equals("test") && !x.equals("graph") && !x.equals("patch")) {
                    break;
                }
            }
        }
        return String.join(" ", words);
    }

    private static Path writeLog(String cmd, String text) {
        try {
            Path dir = RunReport.reportsDir().resolve("logs");
            Files.createDirectories(dir);
            Path f = dir.resolve(cmd.replace(' ', '-') + "-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
            Files.writeString(f, text, java.nio.charset.StandardCharsets.UTF_8);
            return f;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Plugins: jars (or class directories) from {@code PORTER_PLUGINS} (separated by the platform path separator)
     * join the context class loader, so their {@code META-INF/services} providers (subcommands, extract pattern
     * packs) are found like built-in ones. Child porter JVMs inherit the variable.
     */
    static void installPlugins(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        List<java.net.URL> urls = new java.util.ArrayList<>();
        for (String e : spec.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (e.isBlank()) {
                continue;
            }
            Path p = Path.of(e.strip());
            if (!Files.exists(p)) {
                continue; // not built yet (a port's setup stage creates it)
            }
            try {
                urls.add(p.toUri().toURL());
            } catch (java.net.MalformedURLException ex) {
                throw new IllegalStateException(ex);
            }
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]),
                parent != null ? parent : PorterMain.class.getClassLoader()));
    }

    /** Runs the CLI without exiting the JVM (used by tests). */
    public static int run(String... args) {
        return commandLine().execute(args);
    }

    static CommandLine commandLine() {
        Map<String, Object> commands = new LinkedHashMap<>();
        commands.put("validate", new ValidateCommand());
        for (PorterSubcommand p : ServiceLoader.load(PorterSubcommand.class)) {
            if (!KnownCommands.PATHS.containsKey(p.path())) {
                throw new IllegalStateException("unknown command path from provider " + p.getClass().getName() + ": " + p.path());
            }
            if (commands.containsKey(p.path()) && !(commands.get(p.path()) instanceof StubCommand)) {
                throw new IllegalStateException("two providers for command '" + p.path() + "'");
            }
            commands.put(p.path(), p.command());
        }
        CommandLine root = new CommandLine(new PorterMain());
        for (Map.Entry<String, String> known : KnownCommands.PATHS.entrySet()) {
            String path = known.getKey();
            Object cmd = commands.computeIfAbsent(path, x -> new StubCommand(x, known.getValue()));
            List<String> parts = List.of(path.split(" "));
            CommandLine parent = root;
            for (String group : parts.subList(0, parts.size() - 1)) {
                CommandLine existing = parent.getSubcommands().get(group);
                if (existing == null) {
                    existing = new CommandLine(new GroupCommand());
                    existing.getCommandSpec().usageMessage().description(GROUPS.getOrDefault(group, ""));
                    parent.addSubcommand(group, existing);
                }
                parent = existing;
            }
            CommandLine sub = new CommandLine(cmd);
            if (sub.getCommandSpec().usageMessage().description().length == 0) {
                sub.getCommandSpec().usageMessage().description(known.getValue());
            }
            parent.addSubcommand(parts.get(parts.size() - 1), sub);
        }
        root.setExecutionExceptionHandler((ex, cl, parseResult) -> {
            cl.getErr().println("porter: internal error: " + ex);
            return ExitCodes.ERROR;
        });
        return root;
    }

    private static final Map<String, String> GROUPS = Map.of(
            "graph", "Symbol graph across Minecraft versions (graph)",
            "test", "Round-trip and differential scenario tests (testkit)",
            "patch", "Unified diffs between trees (port)");

    /** Parent of nested commands ({@code graph}, {@code test}); prints usage. */
    @Command
    static final class GroupCommand implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return ExitCodes.USAGE;
        }
    }
}
