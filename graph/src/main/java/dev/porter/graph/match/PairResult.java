package dev.porter.graph.match;

import dev.porter.graph.model.Sym;
import dev.porter.graph.model.VersionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Matches between a newer version {@code a} and the adjacent older version {@code b}. */
public final class PairResult {
    /** One correspondence a -> b with its evidence. */
    public static final class Match {
        public final Sym a;
        public final Sym b;
        public final TreeSet<String> evidence = new TreeSet<>();
        public double confidence;

        Match(Sym a, Sym b, double confidence, String... evidence) {
            this.a = a;
            this.b = b;
            this.confidence = confidence;
            for (String e : evidence) {
                this.evidence.add(e);
            }
        }
    }

    public final VersionData a;
    public final VersionData b;
    private final Map<Sym, Match> byA = new HashMap<>();
    private final Map<Sym, Match> byB = new HashMap<>();
    public final Map<String, Integer> stats = new java.util.LinkedHashMap<>();

    public PairResult(VersionData a, VersionData b) {
        this.a = a;
        this.b = b;
    }

    /** Adds a 1:1 match; returns false (and changes nothing) if either side is already matched. */
    public boolean add(Sym sa, Sym sb, double confidence, String... evidence) {
        if (byA.containsKey(sa) || byB.containsKey(sb)) {
            return false;
        }
        Match m = new Match(sa, sb, confidence, evidence);
        byA.put(sa, m);
        byB.put(sb, m);
        stats.merge(sa.kind.json + ":" + String.join("+", m.evidence), 1, Integer::sum);
        return true;
    }

    public Match ofA(Sym sa) {
        return byA.get(sa);
    }

    public Match ofB(Sym sb) {
        return byB.get(sb);
    }

    public Sym matchA(Sym sa) {
        Match m = byA.get(sa);
        return m == null ? null : m.b;
    }

    public boolean matchedB(Sym sb) {
        return byB.containsKey(sb);
    }

    public List<Match> all() {
        List<Match> out = new ArrayList<>(byA.values());
        out.sort((x, y) -> Integer.compare(x.a.index, y.a.index));
        return out;
    }

    public int size() {
        return byA.size();
    }
}
