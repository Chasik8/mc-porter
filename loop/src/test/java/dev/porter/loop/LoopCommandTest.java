package dev.porter.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.json.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

class LoopCommandTest {
    @TempDir
    Path tmp;

    private int run(StringWriter out, String... args) {
        CommandLine cl = new CommandLine(new LoopCommand());
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(out, true));
        return cl.execute(args);
    }

    @Test
    void javacRunnerWritesIterationReport() throws Exception {
        Path project = tmp.resolve("demo");
        LoopEngineTest.copyTree(LoopEngineTest.demo().resolve("project"), project);
        String old = System.getProperty("porter.reports.dir");
        System.setProperty("porter.reports.dir", tmp.resolve("reports").toString());
        try {
            StringWriter out = new StringWriter();
            int code = run(out, "--project", project.toString(), "--runner", "javac", "--rules", LoopEngineTest.demo().resolve("rules").toString(),
                    "--graph", LoopEngineTest.demo().resolve("graph").toString(), "--candidates", LoopEngineTest.demo().resolve("candidates").toString());
            assertEquals(ExitCodes.FAILED, code, out.toString()); // Mth is left for an agent
            assertTrue(out.toString().contains("needs-agent"), out.toString());
            JsonNode it = Json.json().readTree(tmp.resolve("reports/loop/demo.iterations.json").toFile());
            assertEquals("needs-agent", it.get("status").asString());
            assertTrue(it.get("iterations").size() <= 3, it.toString());
            assertTrue(Files.exists(tmp.resolve("reports/runs/loop.json")));
            assertTrue(Files.list(project.resolve("build/porter-loop/proposed")).count() == 2);
        } finally {
            if (old == null) {
                System.clearProperty("porter.reports.dir");
            } else {
                System.setProperty("porter.reports.dir", old);
            }
        }
    }

    @Test
    void usageAndQueueCommand() throws Exception {
        String old = System.getProperty("porter.reports.dir");
        System.setProperty("porter.reports.dir", tmp.resolve("reports").toString());
        try {
            assertEquals(ExitCodes.USAGE, run(new StringWriter()));
        } finally {
            if (old == null) {
                System.clearProperty("porter.reports.dir");
            } else {
                System.setProperty("porter.reports.dir", old);
            }
        }
        var runner = new dev.porter.loop.run.GradleCompileRunner(new dev.porter.loop.run.GradleCompileRunner.Config(tmp, "compileJava",
                "/x/queue.sh", "loop", null, "2g", List.of("--offline"), tmp.resolve("w"), 5));
        List<String> cmd = runner.command();
        assertTrue(cmd.get(0).endsWith("bash") || cmd.get(0).endsWith("bash.exe"), cmd.toString());
        assertEquals(List.of("/x/queue.sh", "loop"), cmd.subList(1, 3));
        assertTrue(cmd.get(3).endsWith("/gradlew"), cmd.toString());
        assertTrue(cmd.containsAll(List.of("compileJava", "--no-daemon", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx2g", "--offline")), cmd.toString());
        assertTrue(Files.readString(tmp.resolve("w/porter-loop-init.gradle")).contains("-Xmaxerrs"));
    }
}
