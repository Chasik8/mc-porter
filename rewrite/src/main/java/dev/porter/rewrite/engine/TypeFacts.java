package dev.porter.rewrite.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Supertype knowledge for type-aware matching, three-valued: a subtype question is answered YES/NO only when the
 * whole hierarchy of the subject is known, otherwise UNKNOWN (and the rewriter writes a TODO instead of guessing).
 *
 * <p>Sources, in order: the Spoon model of the mod sources, a supertypes table (TSV {@code class \t super \t
 * iface,iface \t access}, e.g. produced from the vanilla jar by {@code rewrite/scripts/mojmap_srg_graph.py}), and
 * the running JDK for {@code java.*} types. Names are binary ({@code a.b.Outer$Inner}).
 */
public final class TypeFacts {
    public enum Tri { YES, NO, UNKNOWN }

    public record Supers(String superclass, List<String> interfaces) {
    }

    private final Map<String, Supers> table = new HashMap<>();
    private Function<String, Supers> sourceLookup = n -> null;

    public static TypeFacts empty() {
        return new TypeFacts();
    }

    /** The curated table bundled with the rewriter for a platform version (e.g. forge-1.19.2) plus extra files. */
    public static TypeFacts bundledPlus(String platform, List<Path> extra) {
        TypeFacts f = load(extra);
        try (java.io.InputStream in = TypeFacts.class.getResourceAsStream("/porter/rewrite/typefacts/" + platform + ".tsv")) {
            if (in != null) {
                Map<String, Supers> bundled = new HashMap<>();
                parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), bundled);
                bundled.forEach(f.table::putIfAbsent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return f;
    }

    public static TypeFacts load(List<Path> files) {
        TypeFacts f = new TypeFacts();
        for (Path p : files) {
            try {
                parse(Files.readAllLines(p, StandardCharsets.UTF_8), f.table);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return f;
    }

    private static void parse(List<String> lines, Map<String, Supers> table) {
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            List<String> ifs = c.length > 2 && !c[2].isEmpty() ? List.of(c[2].split(",")) : List.of();
            table.put(c[0], new Supers(c.length > 1 && !c[1].isEmpty() ? c[1] : null, ifs));
        }
    }

    /** Hooks in the Spoon model (types declared in sources). */
    public void setSourceLookup(Function<String, Supers> lookup) {
        this.sourceLookup = lookup;
    }

    /** Direct supertypes, or null if the type is unknown. */
    public Supers supers(String type) {
        Supers s = sourceLookup.apply(type);
        if (s != null) {
            return s;
        }
        s = table.get(type);
        if (s != null) {
            return s;
        }
        if (type.startsWith("java.") || type.startsWith("javax.")) {
            try {
                Class<?> c = Class.forName(type, false, ClassLoader.getPlatformClassLoader());
                List<String> ifs = new ArrayList<>();
                for (Class<?> i : c.getInterfaces()) {
                    ifs.add(i.getName());
                }
                String sup = c.getSuperclass() == null ? (c.isInterface() ? "java.lang.Object" : null) : c.getSuperclass().getName();
                return new Supers(sup, ifs);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    public boolean isKnown(String type) {
        return supers(type) != null;
    }

    /** Is {@code sub} the same as, or a subtype of, {@code sup}? */
    public Tri isSubtype(String sub, String sup) {
        if (sub == null || sup == null) {
            return Tri.UNKNOWN;
        }
        if (sub.equals(sup) || "java.lang.Object".equals(sup)) {
            return Tri.YES;
        }
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(sub);
        boolean complete = true;
        while (!todo.isEmpty()) {
            String t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t.equals(sup)) {
                return Tri.YES;
            }
            Supers s = supers(t);
            if (s == null) {
                complete = false;
                continue;
            }
            if (s.superclass() != null) {
                todo.add(s.superclass());
            }
            todo.addAll(s.interfaces());
        }
        return complete ? Tri.NO : Tri.UNKNOWN;
    }

    /** All known supertypes of {@code type} (breadth first, including itself), for owner attribution. */
    public List<String> ancestry(String type) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            String t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            out.add(t);
            Supers s = supers(t);
            if (s != null) {
                if (s.superclass() != null) {
                    todo.add(s.superclass());
                }
                todo.addAll(s.interfaces());
            }
        }
        return out;
    }
}
