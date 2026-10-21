package dev.porter.graph.match;

import dev.porter.graph.model.Edge;
import dev.porter.graph.model.Edge.Status;
import dev.porter.graph.model.Sym;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns matches into contract edges (newer -> older) and classifies them.
 *
 * <ul>
 *   <li>class: same canonical name = same; same package = renamed; other package = moved.</li>
 *   <li>member: descriptor differs after translating the types through the class matches = signature_changed;
 *       declared in a class that is not the counterpart of its old owner = moved; simple name or owner name
 *       differs = renamed; else same.</li>
 *   <li>unmatched symbol of the newer version = removed (no {@code to}).</li>
 * </ul>
 * Names are compared in each version's canonical namespace, so crossing from Mojang names (1.16.5) to MCP
 * names (1.12.2) shows up as renamed/moved: that is exactly what a source rewrite has to change.
 */
public final class EdgeBuilder {
    private EdgeBuilder() {
    }

    public static List<Edge> edges(PairResult r) {
        List<Edge> out = new ArrayList<>();
        for (Sym s : r.a.symbols()) {
            PairResult.Match m = r.ofA(s);
            if (m == null) {
                out.add(removed(r, s));
            } else {
                out.add(new Edge(s.ref(r.a), m.b.ref(r.b), status(r, m), m.confidence, List.copyOf(m.evidence), null));
            }
        }
        return out;
    }

    private static Edge removed(PairResult r, Sym s) {
        double c;
        String note;
        if (s.isClass()) {
            c = 0.7;
            note = "no counterpart found in " + r.b.id();
        } else if (r.matchA(s.owner) == null) {
            c = 0.8;
            note = "declaring class has no counterpart in " + r.b.id();
        } else {
            c = 0.7;
            note = "no counterpart in the matched class";
        }
        return new Edge(s.ref(r.a), null, Status.REMOVED, c, List.of("no-match"), note);
    }

    static Status status(PairResult r, PairResult.Match m) {
        Sym a = m.a;
        Sym b = m.b;
        if (a.isClass()) {
            String na = a.canonicalName(r.a);
            String nb = b.canonicalName(r.b);
            if (na.equals(nb)) {
                return Status.SAME;
            }
            return pkg(na).equals(pkg(nb)) ? Status.RENAMED : Status.MOVED;
        }
        if (!Objects.equals(Descs.translate(a.obfDesc, r), b.obfDesc)) {
            return Status.SIGNATURE_CHANGED;
        }
        if (r.matchA(a.owner) != b.owner) {
            return Status.MOVED;
        }
        boolean renamed = !a.names[r.a.canonicalIndex()].equals(b.names[r.b.canonicalIndex()])
                || !a.owner.canonicalName(r.a).equals(b.owner.canonicalName(r.b));
        return renamed ? Status.RENAMED : Status.SAME;
    }

    private static String pkg(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }
}
