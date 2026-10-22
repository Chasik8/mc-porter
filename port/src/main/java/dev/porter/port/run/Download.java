package dev.porter.port.run;

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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pinned downloads (sha256-checked) and archive extraction; downloads are data, never executed unchecked. */
public final class Download {
    private Download() {
    }

    public static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throws when {@code p} is missing or its sha256 differs ({@code expected} null or empty = not checked). */
    public static void verify(Path p, String expected, String what) {
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(what + ": file not found: " + p);
        }
        if (expected != null && !expected.isBlank()) {
            String got = sha256(p);
            if (!got.equalsIgnoreCase(expected.strip())) {
                throw new IllegalStateException(what + ": " + p + " has sha256 " + got + ", expected " + expected.strip()
                        + " (a different file or version)");
            }
        }
    }

    /**
     * {@code to} when it exists with the right sha256, else a download of {@code url} into it (atomically, after the
     * check). Returns the file.
     */
    public static Path fetch(String url, String sha256, Path to) {
        if (Files.isRegularFile(to) && (sha256 == null || sha256(to).equalsIgnoreCase(sha256))) {
            return to;
        }
        try {
            Files.createDirectories(to.toAbsolutePath().getParent());
            Path tmp = to.resolveSibling(to.getFileName() + ".part");
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "porter")
                    .timeout(Duration.ofMinutes(20)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);
            }
            if (sha256 != null && !sha256(tmp).equalsIgnoreCase(sha256)) {
                String got = sha256(tmp);
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("sha256 mismatch for " + url + ": got " + got + ", expected " + sha256);
            }
            Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
            return to;
        } catch (IOException e) {
            throw new UncheckedIOException("download " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + url, e);
        }
    }

    /**
     * Extracts the entries of a zip/jar whose names start with {@code prefix} (empty = all) into {@code to}, the
     * prefix removed; {@code skipClasses} leaves out {@code *.class}. {@code stripFirst} drops the archive's
     * top-level directory (JDK archives). Returns the number of files written. Paths escaping {@code to} are refused.
     */
    public static int unzip(Path zip, String prefix, Path to, boolean skipClasses, boolean stripFirst) {
        String lower = zip.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return untar(zip, prefix, to, stripFirst);
        }
        int n = 0;
        Path root = to.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (stripFirst) {
                    int s = name.indexOf('/');
                    if (s < 0) {
                        continue;
                    }
                    name = name.substring(s + 1);
                }
                if (e.isDirectory() || name.isEmpty() || !name.startsWith(prefix)) {
                    continue;
                }
                if (skipClasses && name.toLowerCase(Locale.ROOT).endsWith(".class")) {
                    continue;
                }
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) { // the prefix named a single file
                    rel = name.substring(name.lastIndexOf('/') + 1);
                }
                Path out = root.resolve(rel).normalize();
                if (!out.startsWith(root)) {
                    throw new IllegalStateException("zip entry escapes the target directory: " + e.getName());
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("unzip " + zip + ": " + ex.getMessage(), ex);
        }
        return n;
    }

    /** {@link #unzip} for a {@code .tar.gz} (ustar/GNU, regular files and the executable bit). */
    static int untar(Path tgz, String prefix, Path to, boolean stripFirst) {
        int n = 0;
        Path root = to.toAbsolutePath().normalize();
        try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.BufferedInputStream(Files.newInputStream(tgz)))) {
            byte[] h = new byte[512];
            String longName = null;
            while (in.readNBytes(h, 0, 512) == 512) {
                if (h[0] == 0) {
                    break;
                }
                String name = longName != null ? longName : field(h, 0, 100);
                longName = null;
                String pre = field(h, 345, 155);
                if (!pre.isEmpty() && name.equals(field(h, 0, 100))) {
                    name = pre + "/" + name;
                }
                long size = Long.parseLong(field(h, 124, 12).trim().isEmpty() ? "0" : field(h, 124, 12).trim(), 8);
                int mode = Integer.parseInt(field(h, 100, 8).trim().isEmpty() ? "0" : field(h, 100, 8).trim(), 8);
                char type = (char) h[156];
                byte[] data = in.readNBytes((int) size);
                long pad = (512 - size % 512) % 512;
                in.readNBytes((int) pad);
                if (type == 'L') { // GNU long name
                    longName = new String(data, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", "").strip();
                    continue;
                }
                if (type != '0' && type != 0) {
                    continue;
                }
                if (stripFirst) {
                    int s = name.indexOf('/');
                    if (s < 0) {
                        continue;
                    }
                    name = name.substring(s + 1);
                }
                if (name.isEmpty() || !name.startsWith(prefix)) {
                    continue;
                }
                Path out = root.resolve(name.substring(prefix.length())).normalize();
                if (!out.startsWith(root)) {
                    throw new IllegalStateException("tar entry escapes the target directory: " + name);
                }
                Files.createDirectories(out.getParent());
                Files.write(out, data);
                if ((mode & 0100) != 0) {
                    out.toFile().setExecutable(true);
                }
                n++;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("untar " + tgz + ": " + ex.getMessage(), ex);
        }
        return n;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }
}
