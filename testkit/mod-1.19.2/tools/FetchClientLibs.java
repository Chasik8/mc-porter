import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the vanilla libraries of a Minecraft version json (launcher format) into a libraries directory,
 * like the launcher does: only entries whose rules allow the current OS, every file checked against its sha1.
 * Files already present (or found under --seed, e.g. a Forge server install's libraries/) are reused.
 *
 * Usage (Java 17 single-file launcher): java FetchClientLibs.java version.json librariesDir [seedLibrariesDir]
 * Minimal JSON scanning (no library): relies on the launcher format's "downloads.artifact" objects.
 */
public class FetchClientLibs {
    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of(args[0]));
        Path libs = Path.of(args[1]);
        Path seed = args.length > 2 ? Path.of(args[2]) : null;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        int i = json.indexOf("\"libraries\"");
        int fetched = 0, reused = 0, skipped = 0;
        // every library object starts with {"downloads": ...
        Matcher m = Pattern.compile("\\{\\s*\"downloads\"").matcher(json);
        m.region(i, json.length());
        while (m.find()) {
            int start = m.start();
            int end = matchBrace(json, start);
            String lib = json.substring(start, end + 1);
            m.region(end + 1, json.length());
            if (!allowed(lib, osName)) {
                skipped++;
                continue;
            }
            String name = str(lib, "name");
            boolean x86 = arch.equals("x86") || arch.equals("i386"), arm = arch.contains("aarch64") || arch.contains("arm64");
            if ((name.endsWith("-x86") && !x86) || (name.endsWith("-arm64") && !arm)) {
                skipped++;
                continue;
            }
            int a = lib.indexOf("\"artifact\"");
            if (a < 0) {
                continue;
            }
            String art = lib.substring(a, matchBrace(lib, lib.indexOf('{', a)) + 1);
            String path = str(art, "path"), sha1 = str(art, "sha1"), url = str(art, "url");
            Path target = libs.resolve(path);
            if (Files.isRegularFile(target) && sha1(target).equals(sha1)) {
                reused++;
                continue;
            }
            Files.createDirectories(target.getParent());
            if (seed != null && Files.isRegularFile(seed.resolve(path)) && sha1(seed.resolve(path)).equals(sha1)) {
                Files.copy(seed.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
                reused++;
                continue;
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!sha1(tmp).equals(sha1)) {
                Files.delete(tmp);
                throw new IOException("sha1 mismatch for " + url);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            fetched++;
        }
        System.out.println("libraries: fetched " + fetched + ", reused " + reused + ", skipped (other OS) " + skipped);
    }

    static boolean allowed(String lib, String os) {
        int r = lib.indexOf("\"rules\"");
        if (r < 0) {
            return true;
        }
        String rules = lib.substring(r, lib.indexOf(']', r) + 1);
        boolean allow = false;
        Matcher m = Pattern.compile("\"action\"\\s*:\\s*\"(allow|disallow)\"(\\s*,\\s*\"os\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"(\\w+)\")?").matcher(rules);
        while (m.find()) {
            boolean applies = m.group(3) == null || m.group(3).equals(os);
            if (applies) {
                allow = m.group(1).equals("allow");
            }
        }
        return allow;
    }

    static String str(String s, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        if (!m.find()) {
            throw new IllegalArgumentException("no " + key + " in " + s);
        }
        return m.group(1);
    }

    static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unbalanced json");
    }

    static String sha1(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(d.digest());
    }
}
