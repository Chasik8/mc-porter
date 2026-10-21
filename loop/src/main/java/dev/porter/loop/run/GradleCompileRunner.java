package dev.porter.loop.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code gradlew <task>} in the target project, optionally through a machine-wide job queue
 * ({@code <wrapper> <agent> <command...>}, e.g. a script that lets only one build run at a time).
 *
 * <p>Always passes {@code --no-daemon --console=plain}, a JVM heap cap ({@code -Dorg.gradle.jvmargs=-Xmx<heap>}) and an
 * init script that raises javac's error limit ({@code -Xmaxerrs}), so that one iteration sees every error.
 */
public final class GradleCompileRunner implements CompileRunner {

    /**
     * @param project      Gradle project directory (contains gradlew)
     * @param task         e.g. compileJava
     * @param queueWrapper wrapper script/command, or null to run Gradle directly
     * @param queueAgent   owner name passed to the wrapper
     * @param javaHome     JAVA_HOME for Gradle, or null to inherit
     * @param heap         Gradle JVM heap, e.g. 2g (null = leave the project's setting)
     * @param extraArgs    extra Gradle arguments
     * @param workDir      where the init script and the build log go
     * @param timeoutMin   kill the build after this many minutes
     */
    public record Config(Path project, String task, String queueWrapper, String queueAgent, Path javaHome, String heap,
            List<String> extraArgs, Path workDir, int timeoutMin) {
    }

    private final Config cfg;
    private int runs;

    public GradleCompileRunner(Config cfg) {
        this.cfg = cfg;
    }

    /** Queue wrapper used when none is configured: $PORTER_QUEUE_WRAPPER, else none (Gradle runs directly). */
    public static String defaultQueue() {
        String env = System.getenv("PORTER_QUEUE_WRAPPER");
        return env != null && !env.isBlank() ? env : null;
    }

    /** The command line that {@link #compile()} runs. */
    public List<String> command() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path init = cfg.workDir().resolve("porter-loop-init.gradle");
        Files.createDirectories(cfg.workDir());
        Files.writeString(init, """
                // written by porter loop: show every javac error in one run
                allprojects {
                    tasks.withType(JavaCompile).configureEach {
                        options.compilerArgs.addAll(['-Xmaxerrs', '100000'])
                    }
                }
                """, StandardCharsets.UTF_8);
        List<String> gradle = new ArrayList<>();
        List<String> cmd = new ArrayList<>();
        boolean viaBash = false;
        if (cfg.queueWrapper() != null) {
            if (cfg.queueWrapper().endsWith(".sh")) {
                cmd.add(bash(windows));
                viaBash = true;
            }
            cmd.add(cfg.queueWrapper());
            cmd.add(cfg.queueAgent());
        }
        Path project = cfg.project().toAbsolutePath().normalize();
        if (viaBash || !windows) {
            gradle.add(slash(project.resolve("gradlew")));
        } else {
            gradle.add("cmd.exe");
            gradle.add("/c");
            gradle.add(project.resolve("gradlew.bat").toString());
        }
        gradle.add(cfg.task());
        gradle.add("--no-daemon");
        gradle.add("--console=plain");
        gradle.add("--init-script");
        gradle.add(viaBash ? slash(init.toAbsolutePath()) : init.toAbsolutePath().toString());
        if (cfg.heap() != null) {
            gradle.add("-Dorg.gradle.jvmargs=-Xmx" + cfg.heap());
        }
        gradle.addAll(cfg.extraArgs());
        cmd.addAll(gradle);
        return cmd;
    }

    /**
     * The bash that runs a .sh wrapper: $PORTER_BASH, else on Windows Git Bash (plain "bash" there may be WSL's,
     * which cannot see Windows paths), else "bash".
     */
    static String bash(boolean windows) {
        String env = System.getenv("PORTER_BASH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        if (windows) {
            for (String c : List.of("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe")) {
                if (Files.isRegularFile(Path.of(c))) {
                    return c;
                }
            }
        }
        return "bash";
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Override
    public Result compile() throws Exception {
        List<String> cmd = command();
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cfg.project().toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        if (cfg.javaHome() != null) {
            env.put("JAVA_HOME", cfg.javaHome().toString());
        }
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        if (!p.waitFor(cfg.timeoutMin(), TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("gradle timed out after " + cfg.timeoutMin() + " min");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        runs++;
        Files.writeString(cfg.workDir().resolve("compile-" + runs + ".log"), text, StandardCharsets.UTF_8);
        return new Result(p.exitValue(), text, String.join(" ", cmd));
    }
}
