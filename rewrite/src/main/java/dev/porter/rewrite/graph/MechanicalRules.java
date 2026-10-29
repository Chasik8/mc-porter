package dev.porter.rewrite.graph;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.Descriptors;
import dev.porter.rewrite.engine.Selector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Builders for the mechanical rule shapes (type / call / field / override / constructor), each with a synthetic
 * before/after test. Shared by {@link GraphRuleGenerator} and the upfront rule emitter; the shapes are identical for
 * both, only the id tag, description and provenance differ ({@link Meta}).
 */
public final class MechanicalRules {
    private MechanicalRules() {
    }

    /**
     * @param source provenance source and id tag ({@code graph}, {@code upfront})
     * @param ref    provenance ref
     */
    public record Meta(String fromVersion, String toVersion, String fromPlatform, String toPlatform, String source,
            String description, double confidence, String ref) {
    }

    public static Rule typeRule(Meta meta, String from, String to) {
        return rule(meta, "type-" + from, new Rule.Match(null, null, null, from, null, null, null), Selector.canonical(to), null,
                List.of(new Rule.Test(null, null, "import " + Selector.canonical(from) + ";", "import " + Selector.canonical(to) + ";")));
    }

    /** @param moved the target member lives in another class than the source owner's image (static access) */
    public static Rule fieldRule(Meta meta, SymbolGraph.Ref f, SymbolGraph.Ref t, boolean isStatic, boolean moved) {
        String replace = moved ? Selector.simple(t.owner()).replace('$', '.') + "." + t.name() : "$r." + t.name();
        Rule.Match m = new Rule.Match(null, null, f.owner() + "#" + f.name(), null, null, null,
                moved ? null : new Rule.Bind("$r", null, null, null));
        String ownerS = Selector.simple(f.owner());
        String before = isStatic ? "Object x = " + ownerS + "." + f.name() + ";" : "Object x = r." + f.name() + ";";
        String after = "Object x = " + (moved ? replace : (isStatic ? ownerS : "r") + "." + t.name()) + ";";
        return rule(meta, "field-" + f.owner() + "-" + f.name(), m, replace, moved ? List.of(Selector.canonical(t.owner())) : null,
                List.of(new Rule.Test(null, isStatic ? null : ownerS + " r", before, after)));
    }

    public static Rule callRule(Meta meta, SymbolGraph.Ref f, SymbolGraph.Ref t, boolean isStatic, boolean moved) {
        List<String> params = Descriptors.params(f.desc());
        List<String> argNames = argNames(params.size());
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
        return rule(meta, "call-" + f.owner() + "-" + f.name() + f.desc(), m, replace,
                moved ? List.of(Selector.canonical(t.owner())) : null,
                List.of(new Rule.Test(null, context(isStatic ? null : f.owner(), params), before, after)));
    }

    public static Rule overrideRule(Meta meta, SymbolGraph.Ref f, SymbolGraph.Ref t) {
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
        return rule(meta, "override-" + f.owner() + "-" + f.name() + f.desc(), m, t.name(), null,
                List.of(new Rule.Test(null, imports.isEmpty() ? null : imports.toString(),
                        "public " + r + " " + f.name() + sig, "public " + r + " " + t.name() + sig)));
    }

    public static Rule ctorRule(Meta meta, SymbolGraph.Ref f, SymbolGraph.Ref t) {
        List<String> params = Descriptors.params(f.desc());
        List<String> argNames = argNames(params.size());
        String newS = Selector.simple(t.owner()).replace('$', '.');
        String replace = "new " + newS + "(" + String.join(", ", argNames) + ")";
        Rule.Match m = new Rule.Match(null, f.owner() + "(" + String.join(", ", params) + ")", null, null, null, null,
                argNames.isEmpty() ? null : new Rule.Bind(null, argNames, null, null));
        String plain = String.join(", ", argNames.stream().map(a -> a.substring(1)).toList());
        return rule(meta, "new-" + f.owner() + f.desc(), m, replace, List.of(Selector.canonical(t.owner())),
                List.of(new Rule.Test(null, context(null, params), "Object x = new " + Selector.simple(f.owner()).replace('$', '.') + "(" + plain + ");",
                        "Object x = new " + newS + "(" + plain + ");")));
    }

    public static List<String> argNames(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add("$a" + i);
        }
        return names;
    }

    /** Test context: receiver and parameter declarations plus the imports the parameters need. */
    public static String context(String recvOwner, List<String> params) {
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

    public static String importLine(String type) {
        String base = type.replace("[]", "");
        if (!base.contains(".") || base.startsWith("java.lang.") && base.indexOf('.', 10) < 0) {
            return "";
        }
        String top = base.contains("$") ? base.substring(0, base.indexOf('$')) : base;
        return "import " + top + "; ";
    }

    public static Rule rule(Meta meta, String slug, Rule.Match match, String replace, List<String> imports, List<Rule.Test> tests) {
        return rule(meta, slug, match, replace, imports, null, tests, Rule.Kind.MECHANICAL, null);
    }

    public static Rule rule(Meta meta, String slug, Rule.Match match, String replace, List<String> imports,
            List<String> preconditions, List<Rule.Test> tests, Rule.Kind kind, Integer priority) {
        String id = "r-" + meta.fromVersion() + "-" + meta.toVersion() + "-" + meta.source() + "-" + slugify(slug);
        return new Rule(1, id, meta.description(), meta.fromPlatform(), meta.toPlatform(), kind, match, replace, imports,
                preconditions, null, priority == null ? -10 : priority, meta.confidence(),
                new Rule.Provenance(meta.source(), meta.ref(), null), tests);
    }

    public static String slugify(String s) {
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
}
