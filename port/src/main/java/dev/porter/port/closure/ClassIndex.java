package dev.porter.port.closure;

import dev.porter.port.Text;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Top-level classes of a source tree (one per {@code .java} file, FQN from the path) and their source-level
 * dependencies: single-type, static and on-demand imports resolved to a file of the tree, plus capitalised words
 * that name a class of the same package. Deliberately syntactic (no type attribution): it over-approximates, which
 * is the safe side for a copy closure.
 */
public final class ClassIndex {
    private static final Pattern IMPORT = Text.re("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*|\\.[a-z_]\\w*)?;", Pattern.MULTILINE);
    private static final Pattern WORD = Text.re("\\b([A-Z]\\w+)\\b");

    private final Path root;
    private final Map<String, Path> files = new TreeMap<>();
    private final Map<String, Set<String>> deps = new HashMap<>();

    public ClassIndex(Path root) {
        this.root = root;
        for (Path p : Text.javaFiles(root)) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            files.put(rel.substring(0, rel.length() - 5).replace('/', '.'), p);
        }
    }

    public Path root() {
        return root;
    }

    public boolean contains(String fqn) {
        return files.containsKey(fqn);
    }

    public Path file(String fqn) {
        return files.get(fqn);
    }

    public Set<String> classes() {
        return files.keySet();
    }

    /** Classes of this tree that {@code fqn} references (may include itself). */
    public Set<String> deps(String fqn) {
        return deps.computeIfAbsent(fqn, this::scan);
    }

    private Set<String> scan(String fqn) {
        String src = Text.read(files.get(fqn));
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        Set<String> out = new HashSet<>();
        Matcher m = IMPORT.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            while (!name.isEmpty() && !files.containsKey(name) && name.contains(".")) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            if (files.containsKey(name)) {
                out.add(name);
            }
        }
        Matcher w = WORD.matcher(src);
        Set<String> words = new HashSet<>();
        while (w.find()) {
            words.add(w.group(1));
        }
        for (String word : words) {
            String c = pkg + "." + word;
            if (files.containsKey(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Number of source lines of the given classes. */
    public long lines(Iterable<String> classes) {
        long n = 0;
        for (String c : classes) {
            String s = Text.read(files.get(c));
            n += s.isEmpty() ? 0 : s.split("\n", -1).length - (s.endsWith("\n") ? 1 : 0);
        }
        return n;
    }

    static boolean startsWithAny(String s, List<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
