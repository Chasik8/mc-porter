package dev.porter.port.closure;

import dev.porter.port.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The dependency closure of a port (the user's algorithm, docs/decisions/0008): starting from the seed classes,
 * every class of the mod that the copied code references is copied too, recursively. The walk stops at
 * <ul>
 *   <li>{@code cut} prefixes (hubs: registries, networking, ... that would pull in the whole mod) — referenced hub
 *       classes become the <em>frontier</em> and are replaced by generated stubs that keep only the members the
 *       closure uses;</li>
 *   <li>{@code drop} prefixes — cut and not stubbed (the references are removed by rules or edits);</li>
 *   <li>classes that already exist on the target side with a semantically identical API ({@code known} source
 *       roots, {@link ApiCompare}) — left alone, not copied.</li>
 * </ul>
 * Known classes that differ are copied like unknown ones; the differences surface as compile errors that are
 * resolved once per distinct symbol.
 */
public final class Closure {

    /** Inputs of the walk. Prefix lists match by {@code String.startsWith}. */
    public record Spec(List<String> modPrefixes, List<String> seeds, List<String> include, List<String> cut,
                       List<String> drop, List<String> stubExtra, List<Path> known) {
    }

    /** Outcome: classes to port, frontier classes to stub, and the known-class comparison. */
    public record Result(List<String> port, List<String> frontier, Map<String, String> known, List<String> missingSeeds,
                         long portLines) {
        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", port.size());
            m.put("portLines", portLines);
            m.put("frontier", frontier.size());
            m.put("knownIdentical", known.values().stream().filter("identical"::equals).count());
            m.put("knownDifferent", known.values().stream().filter("different"::equals).count());
            m.put("missingSeeds", missingSeeds);
            return m;
        }
    }

    private Closure() {
    }

    public static Result compute(ClassIndex index, Spec spec) {
        List<String> stop = new ArrayList<>(spec.cut());
        stop.addAll(spec.drop());
        List<ClassIndex> knownIdx = spec.known().stream().filter(Files::isDirectory).map(ClassIndex::new).toList();
        Map<String, String> known = new TreeMap<>();
        Set<String> seen = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>();
        List<String> missing = new ArrayList<>();
        for (String s : concat(spec.seeds(), spec.include())) {
            if (index.contains(s)) {
                todo.push(s);
            } else {
                missing.add(s);
            }
        }
        while (!todo.isEmpty()) {
            String c = todo.pop();
            if (seen.contains(c)) {
                continue;
            }
            String k = compareKnown(index, knownIdx, c);
            if (k != null) {
                known.put(c, k);
                if (k.equals("identical")) {
                    continue; // left to the target side
                }
            }
            seen.add(c);
            for (String d : index.deps(c)) {
                if (ClassIndex.startsWithAny(d, spec.modPrefixes()) && !ClassIndex.startsWithAny(d, stop)) {
                    todo.push(d);
                }
            }
        }
        Set<String> frontier = new TreeSet<>();
        for (String c : seen) {
            for (String d : index.deps(c)) {
                if (ClassIndex.startsWithAny(d, spec.modPrefixes()) && !seen.contains(d) && !ClassIndex.startsWithAny(d, spec.drop())
                        && !"identical".equals(known.get(d))) {
                    frontier.add(d);
                }
            }
        }
        for (String s : spec.stubExtra()) {
            if (index.contains(s) && !seen.contains(s)) {
                frontier.add(s);
            }
        }
        List<String> port = List.copyOf(seen);
        return new Result(port, List.copyOf(frontier), known, missing, index.lines(port));
    }

    private static String compareKnown(ClassIndex index, List<ClassIndex> knownIdx, String c) {
        for (ClassIndex k : knownIdx) {
            if (k.contains(c)) {
                return ApiCompare.identical(Text.read(index.file(c)), Text.read(k.file(c))) ? "identical" : "different";
            }
        }
        return null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
