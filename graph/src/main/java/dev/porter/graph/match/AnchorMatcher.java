package dev.porter.graph.match;

import dev.porter.graph.model.Sym;
import dev.porter.graph.model.VersionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Name-based matching between adjacent versions, used as high-confidence anchors before bytecode matching.
 *
 * <ul>
 *   <li>{@link Mode#MOJMAP} (both versions have Mojang mappings): same class name; same member name + descriptor.</li>
 *   <li>{@link Mode#SRG} (both versions have MCP/SRG): same MCP class name; same SRG member id ({@code func_N_x},
 *       {@code field_N_x}) — MCP kept these ids stable across versions by design.</li>
 * </ul>
 * Classes that were renamed are recovered by member votes: an unmatched class is matched to the unmatched class of
 * the other version that holds most of its member keys (reciprocal best, at least half of both sides' keys).
 */
public final class AnchorMatcher {
    public enum Mode {
        MOJMAP("mojmap"), SRG("srg");

        final String ns;

        Mode(String ns) {
            this.ns = ns;
        }
    }

    private static final Pattern SRG_ID = Pattern.compile("(func|field)_\\d+_[a-zA-Z]+_?|[mf]_\\d+_");

    private final PairResult r;
    private final VersionData a;
    private final VersionData b;
    private final Mode mode;
    private final int nsA;
    private final int nsB;

    public AnchorMatcher(PairResult r) {
        this.r = r;
        this.a = r.a;
        this.b = r.b;
        this.mode = modeFor(a, b);
        this.nsA = a.ns(mode.ns);
        this.nsB = b.ns(mode.ns);
    }

    public static Mode modeFor(VersionData a, VersionData b) {
        if (a.ns("mojmap") >= 0 && b.ns("mojmap") >= 0) {
            return Mode.MOJMAP;
        }
        if (a.ns("srg") >= 0 && b.ns("srg") >= 0 && a.ns("mcp") >= 0 && b.ns("mcp") >= 0) {
            return Mode.SRG;
        }
        throw new IllegalArgumentException("no shared name world between " + a.id() + " and " + b.id());
    }

    public Mode mode() {
        return mode;
    }

    public void run() {
        Map<String, Sym> bClasses = new HashMap<>();
        for (Sym c : b.classes()) {
            bClasses.put(classKey(b, c), c);
        }
        for (Sym ca : a.classes()) {
            Sym cb = bClasses.get(classKey(a, ca));
            if (cb != null) {
                r.add(ca, cb, 0.99, "mapping-name-match");
            }
        }
        for (int iter = 0; iter < 6; iter++) {
            membersOfMatchedClasses();
            if (classVotes() == 0) {
                break;
            }
        }
        membersOfMatchedClasses();
        movedMembers();
        pulledUpMembers();
    }

    /**
     * Members of a class that has no counterpart (typically a new abstract super class such as 1.16's
     * AbstractBlock/BlockBehaviour) are looked for in the counterpart of its matched direct subclasses / implementors:
     * same human name, unique on both sides.
     */
    private void pulledUpMembers() {
        Map<Sym, List<Sym>> subs = new LinkedHashMap<>();
        for (Sym c : a.classes()) {
            List<String> supers = new ArrayList<>(c.interfacesObf);
            if (c.superObf != null) {
                supers.add(c.superObf);
            }
            for (String so : supers) {
                Sym sc = a.classByObf(so);
                if (sc != null) {
                    subs.computeIfAbsent(sc, x -> new ArrayList<>()).add(c);
                }
            }
        }
        int nnA = a.ns(mode == Mode.SRG ? "mcp" : "mojmap");
        int nnB = b.ns(mode == Mode.SRG ? "mcp" : "mojmap");
        for (Sym ca : a.classes()) {
            if (r.matchA(ca) != null || !subs.containsKey(ca)) {
                continue;
            }
            List<Sym> targets = new ArrayList<>();
            java.util.ArrayDeque<Sym> queue = new java.util.ArrayDeque<>(subs.get(ca));
            java.util.Set<Sym> seen = new java.util.HashSet<>();
            while (!queue.isEmpty() && targets.size() <= 3) {
                Sym sub = queue.poll();
                if (!seen.add(sub)) {
                    continue;
                }
                Sym cb = r.matchA(sub);
                if (cb != null) {
                    if (!targets.contains(cb)) {
                        targets.add(cb);
                    }
                } else if (subs.containsKey(sub)) {
                    queue.addAll(subs.get(sub)); // unmatched intermediate (new interface layer): go further down
                }
            }
            if (targets.isEmpty() || targets.size() > 3) {
                continue;
            }
            Map<String, Integer> countA = new HashMap<>();
            for (Sym ma : ca.members) {
                countA.merge(ma.kind + ":" + ma.names[nnA], 1, Integer::sum);
            }
            for (Sym ma : ca.members) {
                String n = ma.names[nnA];
                if (r.ofA(ma) != null || n.startsWith("<") || !humanName(n, ma) || countA.get(ma.kind + ":" + n) != 1) {
                    continue;
                }
                Sym found = null;
                int hits = 0;
                for (Sym cb : targets) {
                    List<Sym> same = new ArrayList<>();
                    for (Sym mb : cb.members) {
                        if (mb.kind == ma.kind && mb.names[nnB].equals(n)) {
                            same.add(mb);
                        }
                    }
                    if (same.size() == 1 && !r.matchedB(same.get(0))) {
                        found = same.get(0);
                        hits++;
                    } else if (!same.isEmpty()) {
                        hits += 2;
                    }
                }
                if (hits == 1 && (ma.access & 8) == (found.access & 8)) {
                    r.add(ma, found, 0.8, "mapping-name-match", "hierarchy");
                }
            }
        }
    }

    /** SRG mode compares MCP class names (srg = mcp for classes); mojmap compares mojmap names. */
    private String classKey(VersionData v, Sym c) {
        return c.names[v.ns(mode == Mode.SRG ? "mcp" : "mojmap")];
    }

    /** Stable member key in one version (null if the member has no usable key). */
    private String key(Sym m, int ns, boolean sideA) {
        String n = m.names[ns];
        if (mode == Mode.SRG && SRG_ID.matcher(n).matches()) {
            return n;
        }
        String desc = sideA ? Descs.translate(m.obfDesc, r) : m.obfDesc;
        if (desc == null) {
            return null;
        }
        // Mojmap or non-obfuscated names (<init>, toString, values...): name + descriptor in b's obf space.
        if (mode == Mode.SRG && !n.startsWith("<") && m.names[0].equals(n) && n.length() <= 2) {
            return null; // still obfuscated: no stable name
        }
        return m.kind.json + ":" + n + ":" + (m.kind == Sym.Kind.FIELD ? "" : desc);
    }

    private void membersOfMatchedClasses() {
        for (Sym ca : a.classes()) {
            Sym cb = r.matchA(ca);
            if (cb == null) {
                continue;
            }
            Map<String, Sym> bKeys = new HashMap<>();
            for (Sym mb : cb.members) {
                if (r.matchedB(mb)) {
                    continue;
                }
                String k = key(mb, nsB, false);
                if (k != null) {
                    bKeys.putIfAbsent(k, mb);
                }
            }
            for (Sym ma : ca.members) {
                if (r.ofA(ma) != null) {
                    continue;
                }
                String k = key(ma, nsA, true);
                Sym mb = k == null ? null : bKeys.get(k);
                if (mb != null && !r.matchedB(mb)) {
                    r.add(ma, mb, 0.99, "mapping-name-match");
                }
            }
            // Second pass: same human name (mojmap, or MCP name for SRG pairs), no overloads on either side, other
            // descriptor -> signature change (e.g. getTileEntity(III) -> getTileEntity(BlockPos), new SRG id in 1.8).
            int nnA = a.ns(mode == Mode.SRG ? "mcp" : "mojmap");
            int nnB = b.ns(mode == Mode.SRG ? "mcp" : "mojmap");
            Map<String, List<Sym>> aByName = new HashMap<>();
            for (Sym ma : ca.members) {
                aByName.computeIfAbsent(ma.kind + ":" + ma.names[nnA], x -> new ArrayList<>()).add(ma);
            }
            Map<String, List<Sym>> bByName = new HashMap<>();
            for (Sym mb : cb.members) {
                bByName.computeIfAbsent(mb.kind + ":" + mb.names[nnB], x -> new ArrayList<>()).add(mb);
            }
            for (Sym ma : ca.members) {
                String n = ma.names[nnA];
                if (r.ofA(ma) != null || n.startsWith("<") || !humanName(n, ma)) {
                    continue;
                }
                List<Sym> sameA = aByName.get(ma.kind + ":" + n);
                List<Sym> sameB = bByName.get(ma.kind + ":" + n);
                if (sameA.size() == 1 && sameB != null && sameB.size() == 1 && !r.matchedB(sameB.get(0))
                        && (ma.access & 8) == (sameB.get(0).access & 8)) {
                    r.add(ma, sameB.get(0), ma.kind == Sym.Kind.FIELD ? 0.9 : 0.85, "mapping-name-match");
                }
            }
        }
    }

    /** A real (deobfuscated) name: not an SRG id and not the obfuscated jar name. */
    private static boolean humanName(String n, Sym s) {
        return !SRG_ID.matcher(n).matches() && !(n.equals(s.obfName) && n.length() <= 3);
    }

    private int classVotes() {
        Map<String, List<Sym>> bIndex = new HashMap<>();
        Map<Sym, Integer> keyCountB = new HashMap<>();
        for (Sym cb : b.classes()) {
            if (r.matchedB(cb)) {
                continue;
            }
            int n = 0;
            for (Sym mb : cb.members) {
                String k = voteKey(mb, nsB, false);
                if (k != null) {
                    bIndex.computeIfAbsent(k, x -> new ArrayList<>()).add(cb);
                    n++;
                }
            }
            keyCountB.put(cb, n);
        }
        Map<Sym, Sym> bestA = new LinkedHashMap<>();
        Map<Sym, Double> ratio = new HashMap<>();
        for (Sym ca : a.classes()) {
            if (r.matchA(ca) != null) {
                continue;
            }
            Map<Sym, Double> votes = new LinkedHashMap<>();
            int keys = 0;
            for (Sym ma : ca.members) {
                String k = voteKey(ma, nsA, true);
                if (k == null) {
                    continue;
                }
                keys++;
                List<Sym> owners = bIndex.get(k);
                if (owners != null) {
                    for (Sym cb : owners) {
                        votes.merge(cb, 1.0 / owners.size(), Double::sum);
                    }
                }
            }
            Sym best = null;
            double bs = 0;
            double second = 0;
            for (Map.Entry<Sym, Double> e : votes.entrySet()) {
                if (e.getValue() > bs) {
                    second = bs;
                    bs = e.getValue();
                    best = e.getKey();
                } else if (e.getValue() > second) {
                    second = e.getValue();
                }
            }
            if (best == null) {
                continue;
            }
            double ra = bs / keys;
            double rb = bs / Math.max(1, keyCountB.get(best));
            // Classes grow and shrink between versions (Blocks gained hundreds of fields in 1.13): require half of
            // one side and a fifth of the other, or many shared ids with a dominant winner.
            if ((bs >= 2 && Math.max(ra, rb) >= 0.5 && Math.min(ra, rb) >= 0.2 && second <= 0.5 * bs)
                    || (bs >= 4 && Math.min(ra, rb) >= 0.2 && second <= 0.25 * bs)) {
                bestA.put(ca, best);
                ratio.put(ca, Math.min(1, (ra + rb) / 2));
            }
        }
        // reciprocal: drop b classes claimed by several a classes
        Map<Sym, Integer> claims = new HashMap<>();
        bestA.values().forEach(cb -> claims.merge(cb, 1, Integer::sum));
        int added = 0;
        for (Map.Entry<Sym, Sym> e : bestA.entrySet()) {
            if (claims.get(e.getValue()) == 1 && r.add(e.getKey(), e.getValue(), 0.6 + 0.35 * ratio.get(e.getKey()), "member-votes")) {
                added++;
            }
        }
        return added;
    }

    /** Vote keys must be specific: SRG ids, or mojmap name+desc (constructors excluded). */
    private String voteKey(Sym m, int ns, boolean sideA) {
        String n = m.names[ns];
        if (n.startsWith("<")) {
            return null;
        }
        if (mode == Mode.SRG) {
            return SRG_ID.matcher(n).matches() ? n : null;
        }
        String desc = m.kind == Sym.Kind.FIELD ? "" : m.desc; // mojmap descriptors on both sides
        return m.kind.json + ":" + n + ":" + desc;
    }

    /** A member whose stable id now lives in another class (pulled up, moved to a helper...). */
    private void movedMembers() {
        Map<String, List<Sym>> bIndex = new HashMap<>();
        for (Sym s : b.symbols()) {
            if (!s.isClass() && !r.matchedB(s)) {
                String k = voteKey(s, nsB, false);
                if (k != null) {
                    bIndex.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
                }
            }
        }
        Map<String, Integer> aCount = new HashMap<>();
        for (Sym s : a.symbols()) {
            if (!s.isClass() && r.ofA(s) == null) {
                String k = voteKey(s, nsA, true);
                if (k != null) {
                    aCount.merge(k, 1, Integer::sum);
                }
            }
        }
        for (Sym s : a.symbols()) {
            if (s.isClass() || r.ofA(s) != null) {
                continue;
            }
            String k = voteKey(s, nsA, true);
            if (k == null || aCount.get(k) != 1) {
                continue;
            }
            List<Sym> cands = bIndex.get(k);
            if (cands != null && cands.size() == 1) {
                r.add(s, cands.get(0), mode == Mode.SRG ? 0.9 : 0.75, "mapping-name-match");
            }
        }
    }
}
