package dev.porter.port.start;

import dev.porter.port.run.Proc;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Unpacking of .zip and .tar.gz archives. */
public final class Archives {
    private Archives() {
    }

    public static void unzip(Path zip, Path dest) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            Path root = dest.toAbsolutePath().normalize();
            Files.createDirectories(root);
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                Path t = root.resolve(e.getName()).normalize();
                if (!t.startsWith(root)) {
                    throw new Fail("unsafe entry in " + zip.getFileName() + ": " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new Fail("cannot unpack " + zip + ": " + e.getMessage(), e);
        }
    }

    /** .tar.gz / .tgz through the system {@code tar} (present on Linux, macOS and Windows 10+). */
    public static void untar(Path tgz, Path dest, Path log) {
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new Fail("cannot create " + dest, e);
        }
        Proc.Result r = Proc.run(List.of("tar", "-xzf", tgz.toAbsolutePath().toString(), "-C", dest.toAbsolutePath().toString()),
                dest, Map.of(), log, null);
        if (!r.ok()) {
            throw new Fail("cannot unpack " + tgz.getFileName() + " (is `tar` installed?), see " + log);
        }
    }

    public static void extract(Path archive, Path dest, Path log) {
        String n = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".jar")) {
            unzip(archive, dest);
        } else if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
            untar(archive, dest, log);
        } else {
            throw new Fail("unknown archive type: " + archive.getFileName() + " (expected .zip or .tar.gz)");
        }
    }

    /** The single top-level directory of an unpacked archive, or {@code dir} itself when there are several entries. */
    public static Path stripTop(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> l = s.toList();
            return l.size() == 1 && Files.isDirectory(l.get(0)) ? l.get(0) : dir;
        } catch (IOException e) {
            throw new Fail("cannot list " + dir, e);
        }
    }

    public static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> p = Files.getPosixFilePermissions(file);
            p.add(PosixFilePermission.OWNER_EXECUTE);
            p.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, p);
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows): nothing to do
        }
    }
}
