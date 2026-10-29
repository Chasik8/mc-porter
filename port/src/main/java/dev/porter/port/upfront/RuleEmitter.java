package dev.porter.port.upfront;

import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.port.upfront.model.Kind;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import dev.porter.rewrite.engine.Descriptors;
import dev.porter.rewrite.engine.Selector;
import dev.porter.rewrite.graph.MechanicalRules;
import dev.porter.rewrite.graph.SymbolGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the unresolved rows of a match table into ordinary porter rules (no engine change): the rewriter consumes the
 * table through five rule files.
 *
 * <ul>
 *   <li>{@code upfront-map}: MAP rows (type, call, field, constructor, override rules; an instance member that moved to
 *       another class only through a static target that takes the receiver first).</li>
 *   <li>{@code upfront-expand}: EXPAND rows. The target call with each concept argument replaced by its expansion and the
 *       result wrapped; arguments expanded more than once need {@code pure:$aN}, a second rule for impure arguments calls
 *       the static helper {@code <Owner>Ext.<name>(receiver, args)} of the shim skeleton (WP7).</li>
 *   <li>{@code upfront-registry}: REGISTRY rows (field to the flattening expression, or a constant of the target class).</li>
 *   <li>{@code upfront-mirror}: type rules for MIRROR types (the mirror member has the 1.19.2 signature, so no call rule).</li>
 *   <li>{@code upfront-ext}: members that have a generated {@code <Simple>Ext} helper ({@code row.shim}): {@code $r.m(args)}
 *       to {@code Ext.m($r, args)}.</li>
 * </ul>
 *
 * Rows already resolved by a rule or the graph are skipped (existing rules win). Rows that cannot be a rule (instance
 * member moved without a static carrier, argument count mismatch, unknown concept) are reported in {@link Result#skipped}.
 */
public final class RuleEmitter {
    public static final String SOURCE = "upfront";

    /**
     * @param access   optional 1.19.2 access flags per member key string (overrides are not emitted for private/final)
     * @param ownerMap 1.19.2 class to its 1.7.10 class as existing type rules already map it (see {@link #typeRuleMap})
     */
    public record Options(String fromVersion, String toVersion, String fromPlatform, String toPlatform, Concepts concepts,
            Map<String, Integer> access, Map<String, String> ownerMap) {
        public static Options defaults() {
            return new Options("1.19.2", "1.7.10", "forge-1.19.2", "forge-1.7.10", Concepts.empty(), Map.of(), Map.of());
        }

        public Options withConcepts(Concepts c) {
            return new Options(fromVersion, toVersion, fromPlatform, toPlatform, c, access, ownerMap);
        }
    }

    /** @param files file name (without extension) to rules; skipped: key to reason. */
    public record Result(Map<String, List<Rule>> files, Map<String, String> skipped) {
        public int ruleCount() {
            return files.values().stream().mapToInt(List::size).sum();
        }
    }

    private static final Pattern ARG = Pattern.compile("\\$a(\\d+)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final int ACC_STATIC = 0x0008;
    private static final List<String> FILES = List.of("upfront-map", "upfront-expand", "upfront-registry", "upfront-mirror", "upfront-ext");

    private final Options opt;
    private Map<String, String> classMap;
    private Map<String, List<Rule>> files;
    private Map<String, String> skipped;
    private Set<String> ids;

    public RuleEmitter(Options opt) {
        this.opt = opt;
    }

    public Result emit(MatchTable table) {
        files = new LinkedHashMap<>();
        for (String f : FILES) {
            files.put(f, new ArrayList<>());
        }
        skipped = new TreeMap<>();
        ids = new LinkedHashSet<>();
        classMap = new HashMap<>(opt.ownerMap());
        for (MatchRow r : table.rows()) {
            if (r.kind() == Kind.TYPE && r.target() != null && r.plan() == Plan.MAP) {
                classMap.put(r.key().owner(), dotted(r.target().owner()));
            }
        }
        for (MatchRow r : table.sortedRows()) {
            if (!"none".equals(r.resolvedBy()) && r.resolvedBy() != null) {
                continue;
            }
            try {
                row(r);
            } catch (RuntimeException e) {
                skipped.put(r.key().toString(), "error: " + e.getMessage());
            }
        }
        for (List<Rule> l : files.values()) {
            l.sort(java.util.Comparator.comparing(Rule::id));
        }
        files.values().removeIf(List::isEmpty);
        return new Result(files, skipped);
    }

    /** Writes one {@code <name>.rule.yaml} per non-empty group into {@code dir}; stale groups are deleted. */
    public static void write(Result res, Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String f : FILES) {
            Path p = dir.resolve(f + ".rule.yaml");
            List<Rule> rules = res.files().get(f);
            if (rules == null || rules.isEmpty()) {
                Files.deleteIfExists(p);
            } else {
                RuleIo.write(p, rules);
            }
        }
        StringBuilder sb = new StringBuilder();
        res.skipped().forEach((k, v) -> sb.append(k).append((char) 9).append(v.replace((char) 10, (char) 32)).append((char) 10));
        Files.writeString(dir.resolve("upfront-skipped.tsv"), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ rows

    private void row(MatchRow r) {
        SymbolKey k = r.key();
        Target t = r.target();
        if (k.owner().matches(".*\\$\\d.*")) {
            return; // anonymous classes cannot be written in source
        }
        switch (r.plan()) {
            case MIRROR -> {
                if (k.kind() == Kind.TYPE && r.shim() != null) {
                    add("upfront-mirror", MechanicalRules.typeRule(meta(r), k.owner(), dotted(r.shim())));
                }
            }
            case MAP -> {
                if (r.shim() != null && k.kind() == Kind.METHOD) {
                    ext(r);
                } else {
                    map(r, t);
                }
            }
            case EXPAND -> expand(r, t);
            case REGISTRY -> registry(r, t);
            case HINT, TODO -> {
                if (k.kind() == Kind.METHOD && r.shim() != null) {
                    ext(r);
                }
            }
            default -> {
            }
        }
    }

    private void map(MatchRow r, Target t) {
        SymbolKey k = r.key();
        if (t == null) {
            skipped.put(k.toString(), "no-target");
            return;
        }
        String tOwner = dotted(t.owner());
        switch (k.kind()) {
            case TYPE -> add("upfront-map", MechanicalRules.typeRule(meta(r), k.owner(), tOwner)); // same name = an explicit "exists on the target" rule
            case FIELD -> {
                boolean isStatic = t.isStatic();
                boolean moved = moved(k, tOwner);
                if (moved && !isStatic) {
                    skipped.put(k.toString(), "moved-instance-field");
                    return;
                }
                add("upfront-map", MechanicalRules.fieldRule(meta(r), ref("field", k), tref("field", tOwner, t), isStatic, moved));
            }
            case METHOD -> {
                int n = Descriptors.params(k.desc()).size();
                if (!"<init>".equals(k.name()) && t.desc() != null && !t.desc().isEmpty() && t.desc().charAt(0) != '(') {
                    Integer acc = opt.access().get(k.toString());
                    accessor(r, t, tOwner, n, acc != null ? (acc & ACC_STATIC) != 0 : t.isStatic());
                    return;
                }
                boolean keyStatic = isStatic(k, t, n);
                if ("<init>".equals(k.name())) {
                    add("upfront-map", MechanicalRules.ctorRule(meta(r), ref("method", k), tref("method", tOwner, t)));
                    return;
                }
                boolean moved = moved(k, tOwner);
                int tn = t.desc() == null || t.desc().isEmpty() ? -1 : Descriptors.params(t.desc()).size();
                if (!keyStatic && t.isStatic()) {
                    if (tn != n + 1) {
                        skipped.put(k.toString(), "moved-instance-no-carrier");
                        return;
                    }
                    add("upfront-map", callRule(r, tOwner, t, n, false, true, null, null));
                    return;
                }
                if (!keyStatic && moved) {
                    skipped.put(k.toString(), "moved-instance-method");
                    return;
                }
                if (tn >= 0 && tn != n) {
                    skipped.put(k.toString(), "arity-differs");
                    return;
                }
                add("upfront-map", MechanicalRules.callRule(meta(r), ref("method", k), tref("method", tOwner, t), keyStatic, moved));
                if (!keyStatic && !k.name().equals(t.name()) && !moved) {
                    Integer acc = opt.access().get(k.toString());
                    if (acc == null || (acc & 0x0012) == 0) {
                        add("upfront-map", MechanicalRules.overrideRule(meta(r), ref("method", k), tref("method", tOwner, t)));
                    }
                }
            }
        }
    }

    /** A no-argument 1.19.2 getter whose 1.7.10 counterpart is a public field: {@code r.getX()} becomes {@code r.x}. */
    private void accessor(MatchRow r, Target t, String tOwner, int n, boolean keyStatic) {
        SymbolKey k = r.key();
        if (n != 0) {
            skipped.put(k.toString(), "accessor-has-arguments");
            return;
        }
        if (!keyStatic && t.isStatic()) {
            skipped.put(k.toString(), "moved-instance-no-carrier");
            return;
        }
        if (!keyStatic && moved(k, tOwner)) {
            skipped.put(k.toString(), "moved-instance-method");
            return;
        }
        String ownerKey = Selector.simple(k.owner()).replace('$', '.');
        String targetSimple = Selector.simple(tOwner).replace('$', '.');
        String replace = keyStatic ? targetSimple + "." + t.name() : "$r." + t.name();
        List<String> imports = keyStatic ? List.of(Selector.canonical(tOwner)) : null;
        Rule.Match m = new Rule.Match(k.owner() + "#" + k.name() + "()", null, null, null, null, null,
                new Rule.Bind(keyStatic ? null : "$r", null, null, null));
        String before = keyStatic ? ownerKey + "." + k.name() + "()" : "r." + k.name() + "()";
        String after = keyStatic ? replace : "r." + t.name();
        add("upfront-map", MechanicalRules.rule(meta(r), "field-call-" + k.owner() + "-" + k.name() + k.desc(), m, replace, imports, null,
                List.of(new Rule.Test(null, MechanicalRules.context(keyStatic ? null : k.owner(), List.of()),
                        "Object x = " + before + ";", "Object x = " + after + ";")),
                Rule.Kind.MECHANICAL, null));
    }

    private void registry(MatchRow r, Target t) {
        SymbolKey k = r.key();
        if (k.kind() != Kind.FIELD || t == null) {
            skipped.put(k.toString(), "registry-needs-field-target");
            return;
        }
        String tOwner = dotted(t.owner());
        if (IDENT.matcher(t.name()).matches()) {
            add("upfront-registry", MechanicalRules.fieldRule(meta(r), ref("field", k), tref("field", tOwner, t), true, true));
            return;
        }
        // a flattening expression (WP6), used verbatim
        String owner = Selector.simple(k.owner());
        List<String> exprImports = RegistryJoin.importsOf(t.name());
        List<String> imports = !tOwner.isEmpty() ? List.of(Selector.canonical(tOwner)) : exprImports.isEmpty() ? null : exprImports;
        add("upfront-registry", MechanicalRules.rule(meta(r), "field-" + k.owner() + "-" + k.name(),
                new Rule.Match(null, null, k.owner() + "#" + k.name(), null, null, null, null), t.name(), imports,
                List.of(new Rule.Test(null, null, "Object x = " + owner + "." + k.name() + ";", "Object x = " + t.name() + ";"))));
    }

    // ------------------------------------------------------------------------------------------------ expand

    private List<String> extraImports = new ArrayList<>();

    private void expand(MatchRow r, Target t) {
        extraImports = new ArrayList<>();
        SymbolKey k = r.key();
        if (t == null || k.kind() != Kind.METHOD) {
            skipped.put(k.toString(), t == null ? "no-target" : "expand-needs-method");
            return;
        }
        String tOwner = dotted(t.owner());
        List<String> params = Descriptors.params(k.desc());
        boolean ctor = "<init>".equals(k.name());
        List<String> args = new ArrayList<>();
        List<String> pure = new ArrayList<>();
        boolean anyConcept = false;
        for (int i = 0; i < params.size(); i++) {
            Concepts.Concept c = opt.concepts().find(params.get(i)).orElse(null);
            if (c == null || c.param() == null || c.param().expand() == null) {
                args.add("$a" + i);
                continue;
            }
            anyConcept = true;
            extraImports.addAll(c.param().imports());
            int uses = 0;
            for (String e : c.param().expand()) {
                args.add(e.replace("$a", "$a" + i));
                uses += e.contains("$a") ? e.split("\\$a", -1).length - 1 : 0;
            }
            if (uses > 1) {
                pure.add("pure:$a" + i);
            }
        }
        if (!anyConcept) {
            skipped.put(k.toString(), "no-concept-argument");
            return;
        }
        boolean keyStatic = !ctor && isStatic(k, t, args.size());
        boolean carrier = !ctor && !keyStatic && t.isStatic();
        if (!ctor && !keyStatic && !carrier && moved(k, tOwner)) {
            skipped.put(k.toString(), "moved-instance-method");
            return;
        }
        if (t.desc() != null && !t.desc().isEmpty() && Descriptors.params(t.desc()).size() != args.size() + (carrier ? 1 : 0)) {
            skipped.put(k.toString(), "expanded-arity-differs");
            return;
        }
        String retType = Descriptors.returnType(k.desc());
        Concepts.Concept rc = opt.concepts().find(retType).orElse(null);
        add("upfront-expand", callRule(r, tOwner, t, params.size(), keyStatic, carrier, args, pure));
        if (!pure.isEmpty()) {
            // impure argument: one helper call keeps the argument evaluated once
            String helper = helperOf(k);
            List<String> hargs = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                hargs.add("$a" + i);
            }
            String ext = Selector.simple(helper) + "." + k.name() + "(" + (keyStatic || ctor ? "" : "$r" + (hargs.isEmpty() ? "" : ", "))
                    + String.join(", ", hargs) + ")";
            if (!ctor) {
                add("upfront-expand", extRule(r, helper, params, retType, keyStatic, ext, wrapped(rc, ext), "helper"));
            }
        }
    }

    /** Call rule: the target call with the argument list {@code args} (null = unchanged), wrapped by the return concept. */
    private Rule callRule(MatchRow r, String tOwner, Target t, int n, boolean keyStatic, boolean carrier, List<String> args, List<String> pure) {
        SymbolKey k = r.key();
        List<String> params = Descriptors.params(k.desc());
        boolean ctor = "<init>".equals(k.name());
        List<String> a = args;
        if (a == null) {
            a = MechanicalRules.argNames(n);
        }
        String ownerS = Selector.simple(tOwner).replace('$', '.');
        List<String> imports = new ArrayList<>();
        String call;
        if (ctor) {
            call = "new " + ownerS + "(" + String.join(", ", a) + ")";
            imports.add(Selector.canonical(tOwner));
        } else if (carrier || t.isStatic()) {
            List<String> all = new ArrayList<>();
            if (carrier) {
                all.add("$r");
            }
            all.addAll(a);
            call = ownerS + "." + t.name() + "(" + String.join(", ", all) + ")";
            imports.add(Selector.canonical(tOwner));
        } else {
            call = "$r." + t.name() + "(" + String.join(", ", a) + ")";
        }
        Concepts.Concept rc = opt.concepts().find(Descriptors.returnType(k.desc())).orElse(null);
        String replace = wrapped(rc, call);
        if (rc != null && rc.ret() != null) {
            imports.addAll(rc.ret().imports());
        }
        if (args != null) {
            imports.addAll(extraImports);
        }
        List<String> argNames = MechanicalRules.argNames(n);
        boolean needsRecv = !ctor && !keyStatic;
        Rule.Match m = ctor
                ? new Rule.Match(null, k.owner() + "(" + String.join(", ", params) + ")", null, null, null, null,
                        argNames.isEmpty() ? null : new Rule.Bind(null, argNames, null, null))
                : new Rule.Match(k.owner() + "#" + k.name() + "(" + String.join(", ", params) + ")", null, null, null, null, null,
                        new Rule.Bind(needsRecv ? "$r" : null, argNames.isEmpty() ? null : argNames, null, null));
        String ret = Descriptors.returnType(k.desc());
        String plain = String.join(", ", argNames.stream().map(s -> s.substring(1)).toList());
        String ownerKey = Selector.simple(k.owner()).replace('$', '.');
        String beforeExpr = ctor ? "new " + ownerKey + "(" + plain + ")"
                : (keyStatic ? ownerKey : "r") + "." + k.name() + "(" + plain + ")";
        String before = "void".equals(ret) ? beforeExpr + ";" : "Object x = " + beforeExpr + ";";
        String afterExpr = replace.replace("$r", "r");
        afterExpr = ARG.matcher(afterExpr).replaceAll(res -> Matcher.quoteReplacement("a" + res.group(1)));
        String after = "void".equals(ret) ? afterExpr + ";" : "Object x = " + afterExpr + ";";
        boolean hasPure = pure != null && !pure.isEmpty();
        return MechanicalRules.rule(meta(r), (ctor ? "new-" : "call-") + k.owner() + "-" + k.name() + k.desc(), m, replace,
                imports.isEmpty() ? null : imports, hasPure ? pure : null,
                List.of(new Rule.Test(null, MechanicalRules.context(needsRecv ? k.owner() : null, params), before, after)),
                hasPure || args != null ? Rule.Kind.ADAPTER : Rule.Kind.MECHANICAL, hasPure ? Integer.valueOf(10) : null);
    }

    private static String wrapped(Concepts.Concept rc, String call) {
        return rc != null && rc.ret() != null ? rc.ret().wrap().replace("$e", call) : call;
    }

    // ------------------------------------------------------------------------------------------------ ext

    private void ext(MatchRow r) {
        SymbolKey k = r.key();
        String helper = dotted(r.shim());
        List<String> params = Descriptors.params(k.desc());
        boolean keyStatic = isStatic(k, r.target(), params.size());
        List<String> an = MechanicalRules.argNames(params.size());
        String call = Selector.simple(helper) + "." + k.name() + "(" + String.join(", ", (keyStatic ? an : prepend("$r", an))) + ")";
        add("upfront-ext", extRule(r, helper, params, Descriptors.returnType(k.desc()), keyStatic, call, call, "ext"));
    }

    private Rule extRule(MatchRow r, String helper, List<String> params, String ret, boolean keyStatic, String callWithoutWrap,
            String replace, String tag) {
        SymbolKey k = r.key();
        List<String> an = MechanicalRules.argNames(params.size());
        Rule.Match m = new Rule.Match(k.owner() + "#" + k.name() + "(" + String.join(", ", params) + ")", null, null, null, null, null,
                new Rule.Bind(keyStatic ? null : "$r", an.isEmpty() ? null : an, null, null));
        String plain = String.join(", ", an.stream().map(s -> s.substring(1)).toList());
        String ownerKey = Selector.simple(k.owner()).replace('$', '.');
        String beforeExpr = (keyStatic ? ownerKey : "r") + "." + k.name() + "(" + plain + ")";
        String afterExpr = replace.replace("$r", "r");
        afterExpr = ARG.matcher(afterExpr).replaceAll(res -> Matcher.quoteReplacement("a" + res.group(1)));
        boolean v = "void".equals(ret);
        List<String> imports = new ArrayList<>(List.of(Selector.canonical(helper)));
        Concepts.Concept rc = opt.concepts().find(ret).orElse(null);
        if (rc != null && rc.ret() != null && "helper".equals(tag)) {
            imports.addAll(rc.ret().imports());
        }
        return MechanicalRules.rule(meta(r), tag + "-" + k.owner() + "-" + k.name() + k.desc(), m, replace, imports, null,
                List.of(new Rule.Test(null, MechanicalRules.context(keyStatic ? null : k.owner(), params),
                        v ? beforeExpr + ";" : "Object x = " + beforeExpr + ";", v ? afterExpr + ";" : "Object x = " + afterExpr + ";")),
                Rule.Kind.ADAPTER, "helper".equals(tag) ? 0 : null);
    }

    /** Documented helper pattern for impure arguments: {@code dev.porter.shim.gen.<owner package>.<Owner>Ext}. */
    static String helperOf(SymbolKey k) {
        String owner = k.owner();
        int i = owner.lastIndexOf('.');
        String pkg = i < 0 ? "" : owner.substring(0, i);
        String simple = Selector.simple(owner);
        return "dev.porter.shim.gen" + (pkg.isEmpty() ? "" : "." + pkg) + "." + simple + "Ext";
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private MechanicalRules.Meta meta(MatchRow r) {
        return new MechanicalRules.Meta(opt.fromVersion(), opt.toVersion(), opt.fromPlatform(), opt.toPlatform(), SOURCE,
                "Generated from the match table: " + r.key() + " (" + r.plan() + ")", r.confidence(),
                (r.method() == null ? "none" : r.method()) + " " + String.format(java.util.Locale.ROOT, "%.2f", r.confidence()));
    }

    private void add(String file, Rule rule) {
        if (ids.add(rule.id())) {
            files.get(file).add(rule);
        }
    }

    private boolean moved(SymbolKey k, String tOwner) {
        return !tOwner.equals(classMap.getOrDefault(k.owner(), k.owner()));
    }

    /** Static-ness of the 1.19.2 member: from the access flags, else guessed from the target (a static target that takes one more argument is a receiver carrier). */
    private boolean isStatic(SymbolKey k, Target t, int targetArgs) {
        Integer acc = opt.access().get(k.toString());
        if (acc != null) {
            return (acc & ACC_STATIC) != 0;
        }
        if (t == null || !t.isStatic()) {
            return false;
        }
        boolean carrierShape = t.desc() != null && !t.desc().isEmpty() && Descriptors.params(t.desc()).size() == targetArgs + 1;
        return !carrierShape;
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** Class mapping of existing type rules (match.type to replace), the owners of instance members that moved with their class. */
    public static Map<String, String> typeRuleMap(List<Rule> rules) {
        Map<String, String> m = new HashMap<>();
        for (Rule r : rules) {
            if (r.match() != null && r.match().type() != null && r.replace() != null && !r.replace().contains(" ")) {
                m.putIfAbsent(r.match().type(), r.replace());
            }
        }
        return m;
    }

    /** {@code forge-1.19.2} -> {@code 1.19.2}. */
    public static String versionOf(String platform) {
        return platform.substring(platform.lastIndexOf('-') + 1);
    }

    private static String dotted(String s) {
        return s == null ? "" : s.replace('/', '.');
    }

    private static SymbolGraph.Ref ref(String kind, SymbolKey k) {
        return new SymbolGraph.Ref("1.19.2", kind, k.kind() == Kind.TYPE ? null : k.owner(),
                k.kind() == Kind.TYPE ? k.owner() : k.name(), k.desc());
    }

    private static SymbolGraph.Ref tref(String kind, String owner, Target t) {
        return new SymbolGraph.Ref("1.7.10", kind, owner, t.name(), t.desc());
    }
}
