package dev.porter.port.start;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The original files of a port ({@code inputs:} in the port config, RFC 007): found, checked against their
 * sha256 and reported in plain words. Nothing else runs before every input is right.
 */
public final class Inputs {
    /** One declared input. */
    public record Spec(String name, String mod, String version, String kind, String page, String file, String sha256,
                       String url, boolean optional) {
        public static Spec of(JsonNode n) {
            return new Spec(n.path("name").asString(), n.path("mod").asString(), n.path("version").asString(),
                    n.path("kind").asString("jar"), n.path("page").asString(), n.path("file").asString(),
                    n.path("sha256").asString(), n.has("url") ? n.get("url").asString() : null,
                    n.path("optional").asBoolean(false));
        }

        public String variable() {
            return "INPUT_" + name.toUpperCase(Locale.ROOT).replaceAll("[-.]", "_");
        }

        String label() {
            return mod + " " + version + " (" + file + ")";
        }
    }

    /** A verified input: the file and, for sources, the unpacked tree. */
    public record Found(Spec spec, Path file, Path dir) {
    }

    private Inputs() {
    }

    public static List<Spec> specs(JsonNode root) {
        List<Spec> out = new ArrayList<>();
        root.path("inputs").forEach(n -> out.add(Spec.of(n)));
        return out;
    }

    /**
     * Resolves and verifies the inputs.
     *
     * @param given     {@code --input name=path} values
     * @param searchIn  directories searched for the exact file name when nothing was given
     * @param downloads where files with a direct {@code url} are stored
     * @param unpackTo  base directory for unpacked source inputs
     * @return the found inputs by name (optional ones that are absent are left out)
     * @throws Fail with one message listing every problem
     */
    public static Map<String, Found> resolve(List<Spec> specs, Map<String, Path> given, List<Path> searchIn,
                                             Path downloads, Path unpackTo, PrintStream out) {
        Map<String, Found> found = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String g : given.keySet()) {
            if (specs.stream().noneMatch(s -> s.name().equals(g))) {
                problems.add("There is no input called '" + g + "' in this port. Known inputs: "
                        + String.join(", ", specs.stream().map(Spec::name).toList()) + ".");
            }
        }
        for (Spec s : specs) {
            Path file = null;
            if (given.containsKey(s.name())) {
                file = given.get(s.name());
                if (!Files.isRegularFile(file)) {
                    problems.add("The file you gave for '" + s.name() + "' does not exist: " + file + "\n"
                            + "   It should be " + s.file() + ", from " + s.page());
                    continue;
                }
            } else {
                for (Path dir : searchIn) {
                    Path c = dir.resolve(s.file());
                    if (Files.isRegularFile(c)) {
                        file = c;
                        break;
                    }
                }
                if (file == null && s.url() != null) {
                    out.println("   fetching " + s.file() + " (" + s.mod() + " " + s.version() + ")");
                    try {
                        file = Net.fetch(s.url(), downloads.resolve(s.file()), "SHA-256", s.sha256(), out);
                    } catch (Fail e) {
                        problems.add(e.getMessage() + "\n   You can also download " + s.file() + " yourself from " + s.page()
                                + " and pass it with --input " + s.name() + "=<path>.");
                        continue;
                    }
                }
                if (file == null) {
                    if (s.optional()) {
                        out.println("   (optional) " + s.file() + " not given; the extra checks that need it are skipped");
                        continue;
                    }
                    problems.add("The file " + s.file() + " is missing (" + s.mod() + " " + s.version() + ").\n"
                            + "   Download it from: " + s.page() + "\n"
                            + "   Then put it into " + searchIn.get(0) + " (or your Downloads folder) and run again,\n"
                            + "   or give its location with: --input " + s.name() + "=<path to " + s.file() + ">");
                    continue;
                }
            }
            String actual = Net.sha256(file);
            if (!actual.equals(s.sha256())) {
                problems.add("The file " + file + " is not the expected " + s.file() + ".\n"
                        + "   Expected checksum (SHA-256): " + s.sha256() + "\n"
                        + "   This file has:               " + actual + "\n"
                        + "   Download exactly " + s.file() + " (" + s.mod() + " " + s.version() + ") from: " + s.page() + "\n"
                        + "   A different version, a re-packed or partly downloaded file gives a different checksum.");
                continue;
            }
            Path dir = null;
            if ("sources".equals(s.kind())) {
                dir = unpack(s, file, unpackTo);
            }
            found.put(s.name(), new Found(s, file, dir));
            out.println("   ok: " + s.file() + (s.optional() ? " (optional)" : ""));
        }
        if (!problems.isEmpty()) {
            throw new Fail(String.join("\n\n", problems));
        }
        return found;
    }

    private static Path unpack(Spec s, Path file, Path unpackTo) {
        Path base = unpackTo.resolve(s.name());
        Path stamp = base.resolve(".sha256");
        try {
            if (Files.isRegularFile(stamp) && Files.readString(stamp).strip().equals(s.sha256())) {
                return Archives.stripTop(base.resolve("tree"));
            }
            deleteTree(base);
            Archives.extract(file, base.resolve("tree"), base.resolve("unpack.log"));
            Files.writeString(stamp, s.sha256());
            return Archives.stripTop(base.resolve("tree"));
        } catch (IOException e) {
            throw new Fail("cannot unpack " + file + ": " + e.getMessage(), e);
        }
    }

    public static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.delete(x);
            }
        } catch (IOException e) {
            throw new Fail("cannot delete " + p + ": " + e.getMessage(), e);
        }
    }
}
