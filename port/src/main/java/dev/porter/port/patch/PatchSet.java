package dev.porter.port.patch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Sets of unified diffs between two directory trees. A patch's {@code ---} path is relative to the <em>base</em>
 * root and its {@code +++} path to the <em>target</em> root, so one patch can turn a file of one tree into a file of
 * another (e.g. a class of the original mod's decompiled sources into the ported class of the target project, at a
 * different path). With the same root for both, a patch edits files in place.
 */
public final class PatchSet {
    private PatchSet() {
    }

    /** One file to compare: base-relative and target-relative paths (either may be absent on disk). */
    public record Pair(String base, String target) {
    }

    /** Result of {@link #apply}: target paths written and deleted. */
    public record Applied(List<String> written, List<String> deleted) {
    }

    static String read(Path p) {
        try {
            return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Patch files of a path: the file itself, or every {@code *.patch}/{@code *.diff} below a directory (sorted). */
    public static List<Path> patchFiles(Path p) {
        if (!Files.isDirectory(p)) {
            return List.of(p);
        }
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".patch") || f.toString().endsWith(".diff"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Applies the patches of {@code patches} (a file or a directory of {@code *.patch}): every file patch reads
     * {@code base/<old path>} and writes {@code target/<new path>}. All patches are checked before anything is
     * written, so a set either applies completely or not at all.
     */
    public static Applied apply(Path patches, Path base, Path target) {
        List<UnifiedDiff.FilePatch> all = new ArrayList<>();
        for (Path f : patchFiles(patches)) {
            all.addAll(UnifiedDiff.parse(read(f)));
        }
        List<String[]> results = new ArrayList<>(); // target path, new text (null = delete)
        for (UnifiedDiff.FilePatch fp : all) {
            String old = fp.created() ? null : read(base.resolve(fp.oldPath()));
            String neu = UnifiedDiff.apply(fp, old);
            results.add(new String[] {fp.deleted() ? fp.oldPath() : fp.newPath(), neu});
        }
        List<String> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        try {
            for (String[] r : results) {
                Path t = target.resolve(r[0]);
                if (r[1] == null) {
                    Files.deleteIfExists(t);
                    deleted.add(r[0]);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.write(t, r[1].getBytes(StandardCharsets.UTF_8));
                    written.add(r[0]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Applied(written, deleted);
    }

    /** Patch text for the given pairs (only pairs whose files differ produce output), in pair order. */
    public static String make(Path base, Path target, List<Pair> pairs, int context) {
        return make(base, target, pairs, context, false);
    }

    /** {@code blind}: patches that carry no text of the base (see {@link UnifiedDiff#diffBlind}). */
    public static String make(Path base, Path target, List<Pair> pairs, int context, boolean blind) {
        StringBuilder sb = new StringBuilder();
        for (Pair p : pairs) {
            String a = p.base() == null ? null : read(base.resolve(p.base()));
            String b = p.target() == null ? null : read(target.resolve(p.target()));
            if (a == null && b == null) {
                throw new IllegalArgumentException("neither " + base.resolve(String.valueOf(p.base())) + " nor "
                        + target.resolve(String.valueOf(p.target())) + " exists");
            }
            sb.append(blind ? UnifiedDiff.diffBlind(p.base(), a, p.target(), b) : UnifiedDiff.diff(p.base(), a, p.target(), b, context));
        }
        return sb.toString();
    }

    /** Pairs for every file of either tree at the same relative path (sorted), optionally under some subpaths only. */
    public static List<Pair> sameNames(Path base, Path target, List<String> under) {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(files(base, under));
        names.addAll(files(target, under));
        List<Pair> out = new ArrayList<>();
        for (String n : names) {
            out.add(new Pair(Files.exists(base.resolve(n)) ? n : null, Files.exists(target.resolve(n)) ? n : null));
        }
        return out;
    }

    private static List<String> files(Path root, List<String> under) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(r -> under.isEmpty() || under.stream().anyMatch(u -> r.startsWith(u))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pairs from {@code base=target} strings. */
    public static List<Pair> pairs(List<String> specs, Map<String, String> unused) {
        List<Pair> out = new ArrayList<>();
        for (String s : specs) {
            int eq = s.indexOf('=');
            out.add(eq < 0 ? new Pair(s, s) : new Pair(s.substring(0, eq), s.substring(eq + 1)));
        }
        return out;
    }
}
