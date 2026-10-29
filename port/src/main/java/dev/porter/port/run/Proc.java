package dev.porter.port.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs external steps (child porter JVMs, javac, Gradle) with the output teed to a log file. */
public final class Proc {
    private Proc() {
    }

    /** Result of a step: exit code and the log file. */
    public record Result(int exitCode, Path log) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Gives the heap this JVM no longer uses back to the OS before a child (rewrite, decompile, javac, Gradle) starts:
     * the child does the heavy work while this process only waits, and without a full GC the parent kept its peak
     * heap (about 1.4 GB after closure/install) next to the child's. With the launcher's
     * {@code -XX:MaxHeapFreeRatio=30} the collector uncommits the free part.
     */
    static void releaseHeap() {
        System.gc();
    }

    /**
     * @param command full command (queue prefix included)
     * @param cwd     working directory
     * @param env     extra environment variables
     * @param log     log file (overwritten)
     * @param echo    also print the output (null = quiet)
     */
    public static Result run(List<String> command, Path cwd, Map<String, String> env, Path log, PrintStream echo) {
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            pb.environment().putAll(env);
            releaseHeap();
            Process p = pb.start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    w.write(line);
                    w.write('\n');
                    if (echo != null) {
                        echo.println("    " + line);
                    }
                }
            }
            return new Result(p.waitFor(), log);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run " + command.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + command.get(0), e);
        }
    }

    /** {@code java} of the running JDK. */
    public static String javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /**
     * Writes a picocli/javac argument file (every argument double-quoted, backslashes and quotes escaped) and
     * returns {@code @path}.
     */
    public static String argFile(Path file, List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            sb.append('"').append(a.replace("\\", "\\\\").replace("\"", "\\\"")).append('"').append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "@" + file;
    }

    /**
     * {@code cmd} behind the queue prefix: {@code queue... java dev.porter.port.run.Exec <cmdFile>} (classpath from the
     * CLASSPATH environment variable), or {@code cmd} itself when there is no queue.
     */
    public static List<String> queued(List<String> queue, List<String> cmd, Path cmdFile) {
        if (queue.isEmpty()) {
            return cmd;
        }
        try {
            Files.createDirectories(cmdFile.getParent());
            Files.writeString(cmdFile, String.join("\n", cmd) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> out = new ArrayList<>(queue);
        out.addAll(List.of(javaExe(), "-Xmx64m", Exec.class.getName(), cmdFile.toString()));
        return out;
    }

    /** Environment that lets {@link #queued} and child porter JVMs find porter's classes. */
    public static Map<String, String> classpathEnv() {
        return Map.of("CLASSPATH", System.getProperty("java.class.path"));
    }

    /** Child porter JVM: {@code java <jvmArgs> dev.porter.cli.PorterMain --reports-dir <dir> @argfile}, queued. */
    public static List<String> porter(List<String> queue, List<String> jvmArgs, Path reportsDir, Path argFile, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe());
        cmd.addAll(jvmArgs);
        cmd.add("-Djdk.attach.allowAttachSelf=true");
        cmd.add("dev.porter.cli.PorterMain");
        cmd.add("--verbose"); // the step log keeps the full output
        cmd.add("--reports-dir");
        cmd.add(reportsDir.toString());
        cmd.add(argFile(argFile, args));
        return queued(queue, cmd, Path.of(argFile + ".cmd"));
    }
}
