package dev.porter.port.start;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything `porter run` downloads or builds lives under one directory ({@code $PORTER_HOME}, default
 * {@code ~/.porter}): delete it to remove all traces. Subdirectories: {@code cache} (= {@code $PORTER_CACHE}: symbol
 * graph, Forge compile jars, type table), {@code gradle} (Gradle user home), {@code downloads}, {@code stamps}
 * (finished one-time steps), {@code logs}, {@code runs/<port>} (a port's workspace and 1.7.10 project).
 */
public final class Home {
    private final Path root;
    private final PrintStream out;

    public Home(Path root, PrintStream out) {
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
    }

    /** {@code $PORTER_HOME}, else {@code ~/.porter}. */
    public static Path defaultRoot() {
        String env = System.getenv("PORTER_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".porter");
    }

    public Path root() {
        return root;
    }

    public Path cache() {
        return root.resolve("cache");
    }

    public Path gradle() {
        return root.resolve("gradle");
    }

    public Path downloads() {
        return root.resolve("downloads");
    }

    public Path logs() {
        return root.resolve("logs");
    }

    public Path run(String port) {
        return root.resolve("runs").resolve(port);
    }

    public PrintStream out() {
        return out;
    }

    /** Variables every port config may use (RFC 007). */
    public Map<String, String> vars(String port) {
        return Map.of("PORTER_HOME", root.toString(), "PORTER_CACHE", cache().toString(), "GRADLE_USER_HOME", gradle().toString(),
                "RUN_DIR", run(port).toString(), "BUILD_JAVA_HOME", System.getProperty("java.home"));
    }

    /**
     * A one-time step: skipped when its stamp exists.
     *
     * @param id    stamp name; change it when the step's inputs change
     * @param what  what the player reads
     * @param about rough duration shown to the player
     */
    public boolean step(String id, String what, String about, Runnable work) {
        Path stamp = root.resolve("stamps").resolve(id);
        if (Files.exists(stamp)) {
            out.println("-- " + what + ": already done");
            return false;
        }
        out.println("== " + what + (about == null ? "" : " (about " + about + ")"));
        long t0 = System.nanoTime();
        work.run();
        try {
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, java.time.Instant.now() + "\n");
        } catch (IOException e) {
            throw new Fail("cannot write " + stamp + ": " + e.getMessage(), e);
        }
        out.println("   done in " + (Math.round((System.nanoTime() - t0) / 1e9)) + " s");
        return true;
    }

    public <T> T timed(String what, Supplier<T> s) {
        long t0 = System.nanoTime();
        T r = s.get();
        out.println("   " + what + ": " + Math.round((System.nanoTime() - t0) / 1e9) + " s");
        return r;
    }
}
