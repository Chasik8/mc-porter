package dev.porter.rewrite.graph;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.Descriptors;
import dev.porter.rewrite.engine.Selector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates mechanical rules from the symbol graph: every symbol of the source version whose chain to
 * the target version is {@code same}, {@code renamed} or {@code moved} (one result) becomes a type / call / override /
 * constructor / field rule with a synthetic before/after test. Anything else (removed, signature_changed, split,
 * merged, moved instance members, broken chains) is not turned into a rule; it is returned as a diagnostic so the
 * rewriter's TODOs can say why ({@code TODO(porter:removed)} etc.).
 *
 * <p>Chains come from {@code queryResult} documents, or are composed from adjacent {@code graph} edges (worst status,
 * minimum confidence along the path; edges are usable in both directions).
 */
public final class GraphRuleGenerator {

    public record Output(List<Rule> rules, Map<String, String> diagnostics) {
    }

    private static final List<String> ORDER = List.of("same", "renamed", "moved", "signature_changed", "split", "merged", "removed");
    private static final int ACC_STATIC = 0x0008;

    private record Chain(SymbolGraph.Ref from, String status, double confidence, List<SymbolGraph.Ref> results, String ref) {
    }

    private final String fromVersion;
    private final String toVersion;
    private final String fromPlatform;
    private final String toPlatform;

    /** @param fromVersion bare Minecraft version (1.19.2); platforms are e.g. forge-1.19.2 */
    public GraphRuleGenerator(String fromVersion, String toVersion, String fromPlatform, String toPlatform) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.fromPlatform = fromPlatform;
        this.toPlatform = toPlatform;
    }

    public Output generate(SymbolGraph g) {
        Map<String, Integer> access = new HashMap<>();
        for (SymbolGraph.Symbol s : g.symbols()) {
            if (s.access() != null) {
                access.put(key(s.ref()), s.access());
            }
        }
        Map<String, Chain> chains = new TreeMap<>();
        for (SymbolGraph.QueryResult q : g.queries()) {
            if (q.symbol() != null && fromVersion.equals(q.symbol().version()) && toVersion.equals(q.to())) {
                chains.put(key(q.symbol()), new Chain(q.symbol(), q.status(), q.confidence(), q.results(), "query " + q.symbol().display()));
            }
        }
        for (Chain c : compose(g.edges())) {
            chains.putIfAbsent(key(c.from()), c);
        }
        // Owner classes as they map into the target: a member whose class was renamed/moved is not itself "moved".
        Map<String, String> classMap = new HashMap<>();
        for (Chain c : chains.values()) {
            if ("class".equals(c.from().kind()) && c.results().size() == 1) {
                classMap.put(c.from().name(), c.results().get(0).name());
            }
        }
        this.classMap = classMap;
        List<Rule> rules = new ArrayList<>();
        Map<String, String> diagnostics = new TreeMap<>();
        for (Chain c : chains.values()) {
            SymbolGraph.Ref f = c.from();
            if (anonymous(f)) {
                continue; // anonymous/local classes ($1) cannot be written in source: no rule can match them
            }
            String diagKey = "class".equals(f.kind()) ? f.name() : f.owner() + "#" + f.name();
            boolean mechanical = List.of("same", "renamed", "moved").contains(c.status()) && c.results().size() == 1;
            if (!mechanical) {
                diagnostics.put(diagKey, c.results().isEmpty() && !"removed".equals(c.status()) ? "removed" : c.status());
                continue;
            }
            SymbolGraph.Ref t = c.results().get(0);
            Integer acc = access.get(key(f));
            boolean isStatic = acc != null && (acc & ACC_STATIC) != 0;
            switch (f.kind()) {
                case "class" -> rules.add(typeRule(c, t));
                case "field" -> {
                    if (!t.owner().equals(classMap.getOrDefault(f.owner(), f.owner())) && !isStatic) {
                        diagnostics.put(diagKey, "moved");
                    } else {
                        rules.add(fieldRule(c, t, isStatic));
                    }
                }
                case "method" -> {
                    if ("<init>".equals(f.name())) {
                        rules.add(ctorRule(c, t));
                    } else if (!t.owner().equals(classMap.getOrDefault(f.owner(), f.owner())) && !isStatic) {
                        diagnostics.put(diagKey, "moved");
                    } else {
                        rules.add(callRule(c, t, isStatic));
                        if (!isStatic && !f.name().equals(t.name()) && (acc == null || (acc & 0x0012) == 0)) {
                            rules.add(overrideRule(c, t)); // not private (0x2) / final (0x10)
                        }
                    }
                }
                default -> diagnostics.put(diagKey, c.status());
            }
        }
        rules.sort(Comparator.comparing(Rule::id));
        return new Output(rules, diagnostics);
    }

    private static final java.util.regex.Pattern ANON = java.util.regex.Pattern.compile("\\$\\d");

    static boolean anonymous(SymbolGraph.Ref r) {
        return ANON.matcher(r.name()).find() || r.owner() != null && ANON.matcher(r.owner()).find();
    }

    // ------------------------------------------------------------------------------------------------ chains

    private List<Chain> compose(List<SymbolGraph.Edge> edges) {
        Map<String, List<SymbolGraph.Edge>> out = new HashMap<>();
        for (SymbolGraph.Edge e : edges) {
            out.computeIfAbsent(key(e.from()), k -> new ArrayList<>()).add(e);
            if (e.to() != null) {
                String rs = switch (e.status()) {
                    case "split" -> "merged";
                    case "merged" -> "split";
                    default -> e.status();
                };
                out.computeIfAbsent(key(e.to()), k -> new ArrayList<>())
                        .add(new SymbolGraph.Edge(e.to(), e.from(), rs, e.confidence(), e.evidence(), e.note()));
            }
        }
        List<Chain> chains = new ArrayList<>();
        Map<String, SymbolGraph.Ref> starts = new TreeMap<>();
        for (SymbolGraph.Edge e : edges) {
            if (fromVersion.equals(e.from().version())) {
                starts.put(key(e.from()), e.from());
            }
            if (e.to() != null && fromVersion.equals(e.to().version())) {
                starts.put(key(e.to()), e.to());
            }
        }
        for (SymbolGraph.Ref s : starts.values()) {
            chains.add(walk(s, out));
        }
        return chains;
    }

    /** Follows edges away from the start version towards the target, one adjacent version at a time. */
    private Chain walk(SymbolGraph.Ref start, Map<String, List<SymbolGraph.Edge>> out) {
        List<SymbolGraph.Ref> frontier = List.of(start);
        String worst = "same";
        double conf = 1.0;
        List<String> refs = new ArrayList<>();
        String version = start.version();
        int guard = 0;
        while (!version.equals(toVersion) && guard++ < 16) {
            String nextVersion = null;
            for (SymbolGraph.Ref r : frontier) {
                for (SymbolGraph.Edge e : out.getOrDefault(key(r), List.of())) {
                    if (e.to() == null) {
                        if ("removed".equals(e.status()) && e.from().version().equals(version)) {
                            return new Chain(start, "removed", Math.min(conf, e.confidence()), List.of(), e.from().display() + " removed");
                        }
                        continue;
                    }
                    String v = e.to().version();
                    if (closer(version, v) && (nextVersion == null || cmp(dist(version, v), dist(version, nextVersion)) < 0)) {
                        nextVersion = v;
                    }
                }
            }
            if (nextVersion == null) {
                return new Chain(start, "removed", conf, List.of(), "no path from " + version);
            }
            List<SymbolGraph.Ref> next = new ArrayList<>();
            for (SymbolGraph.Ref r : frontier) {
                for (SymbolGraph.Edge e : out.getOrDefault(key(r), List.of())) {
                    if (e.to() != null && e.to().version().equals(nextVersion)) {
                        next.add(e.to());
                        worst = worse(worst, e.status());
                        conf = Math.min(conf, e.confidence());
                        refs.add(e.from().display() + " -> " + e.to().display() + " (" + e.status() + ")");
                    }
                }
            }
            frontier = next;
            version = nextVersion;
        }
        return new Chain(start, worst, conf, frontier, String.join(" ; ", refs));
    }

    /** Distance between two versions as a comparable pseudo-version (absolute component differences). */
    private static String dist(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            sb.append(i == 0 ? "" : ".").append(Math.abs(p - q));
        }
        return sb.toString();
    }

    /** Is version {@code v} strictly between {@code current} and the target (or the target itself)? */
    private boolean closer(String current, String v) {
        int c = cmp(current, toVersion);
        return c > 0 ? cmp(v, current) < 0 && cmp(v, toVersion) >= 0 : cmp(v, current) > 0 && cmp(v, toVersion) <= 0;
    }

    static int cmp(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static String worse(String a, String b) {
        return ORDER.indexOf(b) > ORDER.indexOf(a) ? b : a;
    }

    private static String key(SymbolGraph.Ref r) {
        return r.version() + "|" + r.kind() + "|" + r.owner() + "|" + r.name() + "|" + (r.desc() == null ? "" : r.desc());
    }

    // ------------------------------------------------------------------------------------------------ rules

    private Rule typeRule(Chain c, SymbolGraph.Ref t) {
        String from = c.from().name();
        String to = t.name();
        return rule("type-" + from, c, new Rule.Match(null, null, null, from, null, null, null), Selector.canonical(to), null,
                List.of(new Rule.Test(null, null, "import " + Selector.canonical(from) + ";", "import " + Selector.canonical(to) + ";")));
    }

    private Map<String, String> classMap = Map.of();

    private boolean moved(SymbolGraph.Ref f, SymbolGraph.Ref t) {
        return !t.owner().equals(classMap.getOrDefault(f.owner(), f.owner()));
    }

    private Rule fieldRule(Chain c, SymbolGraph.Ref t, boolean isStatic) {
        SymbolGraph.Ref f = c.from();
        boolean moved = moved(f, t);
        String replace = moved ? Selector.simple(t.owner()).replace('$', '.') + "." + t.name() : "$r." + t.name();
        Rule.Match m = new Rule.Match(null, null, f.owner() + "#" + f.name(), null, null, null,
                moved ? null : new Rule.Bind("$r", null, null, null));
        String ownerS = Selector.simple(f.owner());
        String before = isStatic ? "Object x = " + ownerS + "." + f.name() + ";" : "Object x = r." + f.name() + ";";
        String after = "Object x = " + (moved ? replace : (isStatic ? ownerS : "r") + "." + t.name()) + ";";
        return rule("field-" + f.owner() + "-" + f.name(), c, m, replace, moved ? List.of(Selector.canonical(t.owner())) : null,
                List.of(new Rule.Test(null, isStatic ? null : ownerS + " r", before, after)));
    }

    private Rule callRule(Chain c, SymbolGraph.Ref t, boolean isStatic) {
        SymbolGraph.Ref f = c.from();
        List<String> params = Descriptors.params(f.desc());
        List<String> argNames = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            argNames.add("$a" + i);
        }
        boolean moved = moved(f, t);
        String args = String.join(", ", argNames);
        String replace = moved ? Selector.simple(t.owner()).replace('$', '.') + "." + t.name() + "(" + args + ")"
                : "$r." + t.name() + "(" + args + ")";
        Rule.Match m = new Rule.Match(f.owner() + "#" + f.name() + "(" + String.join(", ", params) + ")", null, null, null, null,
                null, new Rule.Bind(moved ? null : "$r", argNames.isEmpty() ? null : argNames, null, null));
        String ownerS = Selector.simple(f.owner());
        String recv = isStatic ? ownerS : "r";
        String plain = String.join(", ", argNames.stream().map(a -> a.substring(1)).toList());
        String before = recv + "." + f.name() + "(" + plain + ");";
        String after = (moved ? Selector.simple(t.owner()).replace('$', '.') : recv) + "." + t.name() + "(" + plain + ");";
        return rule("call-" + f.owner() + "-" + f.name() + f.desc(), c, m, replace,
                moved ? List.of(Selector.canonical(t.owner())) : null,
                List.of(new Rule.Test(null, context(isStatic ? null : f.owner(), params), before, after)));
    }

    private Rule overrideRule(Chain c, SymbolGraph.Ref t) {
        SymbolGraph.Ref f = c.from();
        List<String> params = Descriptors.params(f.desc());
        String ret = Descriptors.returnType(f.desc());
        List<String> decl = new ArrayList<>();
        StringBuilder imports = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            decl.add(Selector.simple(params.get(i)).replace('$', '.') + " a" + i);
            imports.append(importLine(params.get(i)));
        }
        imports.append(importLine(ret));
        String sig = "(" + String.join(", ", decl) + ") { throw new UnsupportedOperationException(); }";
        String r = Selector.simple(ret).replace('$', '.');
        Rule.Match m = new Rule.Match(null, null, null, null, f.owner() + "#" + f.name() + "(" + String.join(", ", params) + ")", null, null);
        return rule("override-" + f.owner() + "-" + f.name() + f.desc(), c, m, t.name(), null,
                List.of(new Rule.Test(null, imports.isEmpty() ? null : imports.toString(),
                        "public " + r + " " + f.name() + sig, "public " + r + " " + t.name() + sig)));
    }

    private Rule ctorRule(Chain c, SymbolGraph.Ref t) {
        SymbolGraph.Ref f = c.from();
        List<String> params = Descriptors.params(f.desc());
        List<String> argNames = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            argNames.add("$a" + i);
        }
        String newS = Selector.simple(t.owner()).replace('$', '.');
        String replace = "new " + newS + "(" + String.join(", ", argNames) + ")";
        Rule.Match m = new Rule.Match(null, f.owner() + "(" + String.join(", ", params) + ")", null, null, null, null,
                argNames.isEmpty() ? null : new Rule.Bind(null, argNames, null, null));
        String plain = String.join(", ", argNames.stream().map(a -> a.substring(1)).toList());
        return rule("new-" + f.owner() + f.desc(), c, m, replace, List.of(Selector.canonical(t.owner())),
                List.of(new Rule.Test(null, context(null, params), "Object x = new " + Selector.simple(f.owner()).replace('$', '.') + "(" + plain + ");",
                        "Object x = new " + newS + "(" + plain + ");")));
    }

    private static String context(String recvOwner, List<String> params) {
        StringBuilder sb = new StringBuilder();
        if (recvOwner != null) {
            sb.append(Selector.simple(recvOwner).replace('$', '.')).append(" r; ");
        }
        for (int i = 0; i < params.size(); i++) {
            sb.append(Selector.simple(params.get(i)).replace('$', '.')).append(" a").append(i).append("; ");
            sb.append(importLine(params.get(i)));
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String importLine(String type) {
        String base = type.replace("[]", "");
        if (!base.contains(".") || base.startsWith("java.lang.") && base.indexOf('.', 10) < 0) {
            return "";
        }
        String top = base.contains("$") ? base.substring(0, base.indexOf('$')) : base;
        return "import " + top + "; ";
    }

    private Rule rule(String slug, Chain c, Rule.Match match, String replace, List<String> imports, List<Rule.Test> tests) {
        String id = "r-" + fromVersion + "-" + toVersion + "-graph-" + slugify(slug);
        return new Rule(1, id, "Generated from the symbol graph: " + c.from().display() + " (" + c.status() + ")",
                fromPlatform, toPlatform, Rule.Kind.MECHANICAL, match, replace, imports, null, null, -10,
                Math.round(c.confidence() * 1000) / 1000.0, new Rule.Provenance("graph", c.ref(), null), tests);
    }

    static String slugify(String s) {
        String kind = s.substring(0, s.indexOf('-'));
        String base = s.substring(s.indexOf('-') + 1).replaceAll("\\(.*", "").toLowerCase(Locale.ROOT);
        String readable = kind + "-" + base.replaceAll(".*\\.", "").replace('$', '-').replaceAll("[^a-z0-9._-]+", "-");
        if (readable.length() > 60) {
            readable = readable.substring(0, 60);
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return readable + "-" + HexFormat.of().formatHex(h, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For reports: number of rules per kind of selector. */
    public static Map<String, Integer> census(List<Rule> rules) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Rule r : rules) {
            m.merge(Selector.of(r.match()).kind().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return m;
    }
}
