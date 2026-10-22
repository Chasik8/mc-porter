package dev.porter.port.start;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/** Pinned downloads: every file is checked against a published checksum before it is used. */
public final class Net {
    private Net() {
    }

    private static final AtomicLong DOWNLOADED = new AtomicLong();

    /** Bytes downloaded by porter itself in this process (Gradle downloads its own files and is not counted). */
    public static long downloadedBytes() {
        return DOWNLOADED.get();
    }

    public static String hex(Path file, String algorithm) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new Fail("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public static String sha256(Path file) {
        return hex(file, "SHA-256");
    }

    /**
     * Downloads {@code url} to {@code dest} unless a file with the expected checksum is already there.
     *
     * @param algorithm {@code SHA-256} or {@code SHA-1}
     * @param expected  lower-case hex digest
     */
    public static Path fetch(String url, Path dest, String algorithm, String expected, PrintStream out) {
        if (Files.isRegularFile(dest) && hex(dest, algorithm).equals(expected)) {
            return dest;
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30)).build();
        Fail last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path part = dest.resolveSibling(dest.getFileName() + ".part");
            try {
                Files.createDirectories(dest.getParent());
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "porter").GET().build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    throw new Fail("download failed (HTTP " + resp.statusCode() + "): " + url);
                }
                long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                MessageDigest md = MessageDigest.getInstance(algorithm);
                long got = 0;
                long nextReport = System.nanoTime() + 5_000_000_000L;
                try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        md.update(buf, 0, n);
                        got += n;
                        DOWNLOADED.addAndGet(n);
                        if (out != null && total > 5_000_000 && System.nanoTime() > nextReport) {
                            out.printf("    %s: %d of %d MB%n", dest.getFileName(), got >> 20, total >> 20);
                            nextReport = System.nanoTime() + 10_000_000_000L;
                        }
                    }
                }
                if (!HexFormat.of().formatHex(md.digest()).equals(expected)) {
                    Files.deleteIfExists(part);
                    throw new Fail("the downloaded file " + dest.getFileName() + " does not match its published checksum ("
                            + url + "). It was not used.");
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return dest;
            } catch (Fail e) {
                last = e;
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                last = new Fail("download failed: " + url + " (" + e + ")", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Fail("interrupted while downloading " + url, e);
            }
            if (out != null) {
                out.println("    retrying (" + attempt + "/3): " + last.getMessage());
            }
        }
        throw last;
    }
}
