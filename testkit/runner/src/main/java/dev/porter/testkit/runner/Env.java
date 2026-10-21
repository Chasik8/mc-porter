package dev.porter.testkit.runner;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Machine-specific launch configuration (java executables, Forge server installs, test-mod jars, client files).
 * Looked up in: {@code --env}, {@code $PORTER_TESTKIT_ENV}, {@code testkit/env.local.properties} (relative to the
 * working directory). See testkit/env.example.properties for every key.
 */
public final class Env {
    private final Properties props;
    private final Path file;

    private Env(Properties props, Path file) {
        this.props = props;
        this.file = file;
    }

    public static Env load(Path explicit) {
        Path f = explicit;
        if (f == null && System.getenv("PORTER_TESTKIT_ENV") != null) {
            f = Path.of(System.getenv("PORTER_TESTKIT_ENV"));
        }
        if (f == null) {
            f = Path.of("testkit", "env.local.properties");
        }
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("testkit environment file " + f.toAbsolutePath()
                    + " not found; copy testkit/env.example.properties to testkit/env.local.properties or pass --env");
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Env(p, f);
    }

    public static Env of(Properties p) {
        return new Env(p, null);
    }

    public Path file() {
        return file;
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalArgumentException("missing key '" + key + "' in testkit environment " + (file == null ? "" : file));
        }
        return v;
    }

    public Path path(String key) {
        Path p = Path.of(require(key));
        if (!p.isAbsolute() && file != null) {
            // relative paths are relative to the working directory (normally the repo root)
            p = p.toAbsolutePath();
        }
        return p;
    }

    public String forVersion(String version, String key, String def) {
        return get(version + "." + key, def);
    }
}
