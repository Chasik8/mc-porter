package dev.porter.graph.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Resolves {@link Sources.Artifact}s to verified local files: {@code <cache>/dl/<dir>/<file>}, else a byte-identical
 * {@code local} copy, else a download. Every file is checked against the published sha1; downloads are treated as
 * untrusted data (only parsed, never executed).
 */
public final class Fetcher {
    /** A verified file plus its checksums (sha256 goes into the graph's {@code sources}). */
    public record Fetched(Path path, String sha1, String sha256) {
    }

    private final Path cacheDir;
    private final boolean offline;
    private final Consumer<String> log;
    private final Map<Path, Fetched> verified = new ConcurrentHashMap<>();

    public Fetcher(Path cacheDir, boolean offline, Consumer<String> log) {
        this.cacheDir = cacheDir;
        this.offline = offline;
        this.log = log;
    }

    public Fetched fetch(Sources.Artifact a) throws IOException {
        Path target = cacheDir.resolve("dl").resolve(a.dir()).resolve(a.fileName());
        if (Files.isRegularFile(target)) {
            return verify(target, a);
        }
        for (String candidate : a.local()) {
            Path p = Path.of(expand(candidate));
            if (Files.isRegularFile(p)) {
                Fetched f = hash(p);
                if (f.sha1().equals(a.sha1())) {
                    log.accept("using local copy " + p);
                    return f;
                }
                log.accept("ignoring local copy " + p + " (sha1 " + f.sha1() + " != " + a.sha1() + ")");
            }
        }
        if (offline) {
            throw new IOException("missing " + target + " and --offline is set (url " + a.url() + ")");
        }
        log.accept("downloading " + a.url());
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(a.url()))
                    .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("HTTP " + resp.statusCode() + " for " + a.url());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading " + a.url(), e);
        }
        Fetched f = hash(tmp);
        if (!f.sha1().equals(a.sha1())) {
            Files.deleteIfExists(tmp);
            throw new IOException("sha1 mismatch for " + a.url() + ": got " + f.sha1() + ", expected " + a.sha1());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return new Fetched(target, f.sha1(), f.sha256());
    }

    private Fetched verify(Path p, Sources.Artifact a) throws IOException {
        Fetched f = hash(p);
        if (!f.sha1().equals(a.sha1())) {
            throw new IOException("cached file " + p + " has sha1 " + f.sha1() + ", expected " + a.sha1() + " (delete it to re-download)");
        }
        return f;
    }

    private Fetched hash(Path p) throws IOException {
        Path key = p.toAbsolutePath().normalize();
        Fetched cached = verified.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            MessageDigest s1 = MessageDigest.getInstance("SHA-1");
            MessageDigest s256 = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            try (InputStream in = Files.newInputStream(p)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    s1.update(buf, 0, n);
                    s256.update(buf, 0, n);
                }
            }
            Fetched f = new Fetched(p, HexFormat.of().formatHex(s1.digest()), HexFormat.of().formatHex(s256.digest()));
            verified.put(key, f);
            return f;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String expand(String s) {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.isBlank()) {
            gradleHome = Path.of(System.getProperty("user.home"), ".gradle").toString();
        }
        return s.replace("${GRADLE_USER_HOME}", gradleHome).replace("${user.home}", System.getProperty("user.home"));
    }

    /** Default cache root: {@code $PORTER_CACHE/graph}, else {@code ~/.cache/porter/graph}. */
    public static Path defaultCacheDir() {
        String env = System.getenv("PORTER_CACHE");
        Path root = env == null || env.isBlank() ? Path.of(System.getProperty("user.home"), ".cache", "porter") : Path.of(env);
        return root.resolve("graph");
    }

    /** Reads an entry of a zip into memory (mapping files are at most a few tens of MB). */
    public static byte[] readEntry(Path zip, String entry) {
        try (var fs = new java.util.zip.ZipFile(zip.toFile())) {
            var e = fs.getEntry(entry);
            if (e == null) {
                throw new IOException("entry " + entry + " not found in " + zip);
            }
            try (InputStream in = fs.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
