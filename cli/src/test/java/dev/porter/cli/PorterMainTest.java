package dev.porter.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.KnownCommands;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PorterMainTest {
    @TempDir
    Path reports;

    private static Path examples() {
        return Path.of(System.getProperty("porter.repoRoot", "..")).resolve("contracts/examples").toAbsolutePath();
    }

    private record Run(int code, String out) {
    }

    private Run run(String... args) {
        CommandLine cl = PorterMain.commandLine();
        StringWriter out = new StringWriter();
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(out, true));
        String[] full = new String[args.length + 2];
        full[0] = "--reports-dir";
        full[1] = reports.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Run(cl.execute(full), out.toString());
    }

    @Test
    void validateAllValidExamples() {
        for (String c : new String[] {"ir", "rule", "symbol-graph", "scenario", "trace", "candidate"}) {
            Run r = run("validate", examples().resolve(c).resolve("valid").toString());
            assertEquals(ExitCodes.OK, r.code(), r.out());
        }
        assertTrue(Files.exists(reports.resolve("runs/validate.json")));
    }

    @Test
    void validateFailsOnInvalidExample() {
        Run r = run("validate", examples().resolve("rule/invalid/no-tests.rule.yaml").toString());
        assertEquals(ExitCodes.FAILED, r.code());
        assertTrue(r.out().contains("INVALID"), r.out());
        assertTrue(r.out().contains("/tests"), r.out());
    }

    @Test
    void validateWithForcedSchema() {
        Run r = run("validate", "--schema", "trace", examples().resolve("ir/valid/minimal-with-unknown.ir.json").toString());
        assertEquals(ExitCodes.FAILED, r.code(), r.out());
        assertEquals(ExitCodes.USAGE, run("validate", "--schema", "nope", "x.json").code());
    }

    @Test
    void everyKnownCommandExistsAndStubsReport() throws Exception {
        CommandLine root = PorterMain.commandLine();
        for (String path : KnownCommands.PATHS.keySet()) {
            CommandLine cl = root;
            for (String part : path.split(" ")) {
                cl = cl.getSubcommands().get(part);
                assertTrue(cl != null, "missing command " + path);
            }
        }
        // extract is implemented (extract module): a missing --src directory is a usage error.
        assertEquals(ExitCodes.USAGE, run("extract", "--src", "x", "--version", "1.19.2", "--out", "ir/").code());
        // graph build is implemented (graph module): a non-contiguous version list is a usage error, reported.
        Run r = run("graph", "build", "--versions", "1.7.10,1.19.2", "--offline");
        assertEquals(ExitCodes.USAGE, r.code(), r.out());
        String report = Files.readString(reports.resolve("runs/graph-build.json"));
        assertTrue(report.contains("\"status\" : \"failed\""), report);
        assertTrue(report.contains("1.7.10,1.19.2"), report);
    }

    @Test
    void providersReplaceStubs() {
        assertEquals(ExitCodes.OK, run("report").code());
    }

    @Test
    void longOutputIsSummarisedAndLogged() throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream e = new java.io.ByteArrayOutputStream();
        String[] args = {"--reports-dir", reports.toString(), "validate", examples().resolve("ir/valid").toString(),
                examples().resolve("rule/valid").toString(), examples().resolve("scenario/valid").toString(),
                examples().resolve("trace/valid").toString(), examples().resolve("candidate/valid").toString(),
                examples().resolve("symbol-graph/valid").toString()};
        int rc = PorterMain.execute(args, new java.io.PrintStream(o, true), new java.io.PrintStream(e, true));
        assertEquals(ExitCodes.OK, rc);
        String text = o.toString(java.nio.charset.StandardCharsets.UTF_8);
        long lines = text.lines().count();
        assertTrue(lines <= dev.porter.core.cli.Compact.MAX_LINES, text);
        // the full output is in the log when the console got a summary
        if (text.contains("full output:")) {
            try (var logs = Files.list(reports.resolve("logs"))) {
                assertTrue(logs.findAny().isPresent());
            }
        }
        // --verbose prints everything and writes no log
        java.io.ByteArrayOutputStream v = new java.io.ByteArrayOutputStream();
        String[] verbose = new String[args.length + 1];
        verbose[0] = "--verbose";
        System.arraycopy(args, 0, verbose, 1, args.length);
        PorterMain.execute(verbose, new java.io.PrintStream(v, true), new java.io.PrintStream(e, true));
        assertTrue(v.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count() >= lines);
    }

    @Test
    void commandWordsSkipOptions() {
        assertEquals("test diff", PorterMain.commandWords(java.util.List.of("--reports-dir", "x", "test", "diff", "--scenario", "a")));
        assertEquals("port", PorterMain.commandWords(java.util.List.of("port", "--config", "c")));
        assertEquals("", PorterMain.commandWords(java.util.List.of("@args")));
    }
}
