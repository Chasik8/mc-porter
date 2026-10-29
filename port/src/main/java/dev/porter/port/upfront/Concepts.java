package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.upfront.model.Plan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The concept table ({@code rules/upfront/1.19.2__1.7.10/concepts.yaml}): 1.19.2 types that change a signature the
 * same way everywhere (BlockPos becomes {@code III}, a BlockState becomes block plus metadata, the hand argument
 * disappears, ...). A concept absorbs every signature that differs only by it: {@link #conceptDesc} rewrites a 1.19.2
 * descriptor into what the 1.7.10 member would have, {@link #expand} unfolds a call-site argument into the 1.7.10
 * arguments and {@link #wrap} wraps a call expression whose result the 1.19.2 code reads as the concept type.
 * Format: section 2.5 of the upfront spec. Expression helpers resolve against the porter shims ({@link #check}).
 */
public final class Concepts {

    // Plan (MAP, MIRROR, EXPAND used here) is the shared dev.porter.port.upfront.model.Plan.

    /** The parameter side: the 1.7.10 descriptor fragment and the argument expressions ({@code $a} = the argument). */
    public record Param(String desc, List<String> expand, List<String> imports) {
    }

    /** The result side: the 1.7.10 descriptor fragment and the wrapping expression ({@code $e} = the call). */
    public record Ret(String desc, String wrap, List<String> imports) {
    }

    /** {@code type} uses internal names ({@code a/b/C$D}). {@code whenSignature} is a regex on the generic signature. */
    public record Concept(String type, Plan plan, String shim, Param param, Ret ret, String whenSignature) {
        /** the concept's type as a source name ({@code a.b.C$D}) */
        public String dotted() {
            return type.replace('/', '.');
        }

        public boolean conditional() {
            return whenSignature != null && !whenSignature.isEmpty();
        }
    }

    /** An explicit type plan from the {@code types:} section. */
    public record TypeEntry(Plan plan, String shim) {
    }

    private static final Pattern DESC_TOKEN = Pattern.compile("\\[*(?:[BCDFIJSZV]|L[^;]+;)");
    /** methods a shim inherits from its JDK superclass; not declared in the shim source */
    private static final Set<String> INHERITED = Set.of("toArray", "size", "get", "isEmpty", "toString", "equals", "hashCode");
    private static final Pattern CALL = Pattern.compile("(\\$a|\\$e|[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\.(\\w+)\\s*\\(");

    private final Map<String, Concept> byInternal = new LinkedHashMap<>();
    private final Map<String, TypeEntry> types = new LinkedHashMap<>();

    private Concepts() {
    }

    /** no concepts at all */
    public static Concepts empty() {
        return new Concepts();
    }

    // ------------------------------------------------------------------------------------------------ loading

    public static Concepts load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Concepts parse(String yaml) {
        JsonNode root = Json.yaml().readTree(yaml);
        if (root.path("schemaVersion").asInt(0) != 1) {
            throw new IllegalArgumentException("concepts.yaml: schemaVersion 1 expected");
        }
        Concepts c = new Concepts();
        for (JsonNode n : root.path("concepts")) {
            String type = text(n, "type");
            if (type == null || type.isEmpty()) {
                throw new IllegalArgumentException("concepts.yaml: concept without type");
            }
            type = type.replace('.', '/');
            Plan plan = Plan.valueOf(n.path("plan").asString("EXPAND"));
            Param param = null;
            if (n.has("param")) {
                JsonNode p = n.get("param");
                param = new Param(p.path("desc").asString(""), strings(p.path("expand")), strings(p.path("imports")));
            }
            Ret ret = null;
            if (n.has("ret")) {
                JsonNode r = n.get("ret");
                ret = new Ret(r.path("desc").asString(""), r.path("wrap").asString("$e"), strings(r.path("imports")));
            }
            if (c.byInternal.put(type, new Concept(type, plan, text(n, "shim"), param, ret, text(n, "whenSignature"))) != null) {
                throw new IllegalArgumentException("concepts.yaml: duplicate concept " + type);
            }
        }
        JsonNode ts = root.path("types");
        for (String name : ts.propertyNames()) {
            JsonNode t = ts.get(name);
            c.types.put(name, new TypeEntry(Plan.valueOf(t.path("plan").asString("MIRROR")), text(t, "shim")));
        }
        return c;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : a) {
            out.add(x.asString());
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------------ lookup

    public List<Concept> all() {
        return List.copyOf(byInternal.values());
    }

    /** by internal name ({@code a/b/C}) or source name ({@code a.b.C}) */
    public Optional<Concept> find(String type) {
        return Optional.ofNullable(byInternal.get(type.replace('.', '/')));
    }

    public Map<String, TypeEntry> types() {
        return Map.copyOf(types);
    }

    // ------------------------------------------------------------------------------------------------ descriptors

    /**
     * A 1.19.2 descriptor as the 1.7.10 member would have it: concept types in parameters become their
     * {@code param.desc} (possibly nothing), a concept return type becomes {@code ret.desc}, other class types go
     * through {@code classMap} (source names to source names). Arrays of a concept type are left to the class map
     * (they are a HINT: no expansion). A field descriptor takes the return side. A conditional concept
     * ({@code whenSignature}) applies unconditionally here.
     */
    public String conceptDesc(String desc, Map<String, String> classMap) {
        return conceptDesc(desc, classMap, null, false);
    }

    /**
     * Like {@link #conceptDesc(String, Map)} but honours {@code whenSignature}: a conditional concept applies when
     * the generic {@code signature} matches its regex; when the signature is unknown ({@code null}) and the
     * descriptor needs a conditional concept the answer is {@code null} (the symbol is a HINT).
     */
    public String conceptDesc(String desc, Map<String, String> classMap, String signature) {
        return conceptDesc(desc, classMap, signature, true);
    }

    private String conceptDesc(String desc, Map<String, String> classMap, String signature, boolean strict) {
        if (!desc.startsWith("(")) {
            return one(desc, false, classMap, signature, strict);
        }
        int close = desc.indexOf(')');
        StringBuilder sb = new StringBuilder("(");
        Matcher m = DESC_TOKEN.matcher(desc.substring(1, close));
        while (m.find()) {
            String t = one(m.group(), true, classMap, signature, strict);
            if (t == null) {
                return null;
            }
            sb.append(t);
        }
        String r = one(desc.substring(close + 1), false, classMap, signature, strict);
        return r == null ? null : sb.append(')').append(r).toString();
    }

    private String one(String t, boolean param, Map<String, String> classMap, String signature, boolean strict) {
        if (t.startsWith("L") && t.endsWith(";")) {
            Concept c = byInternal.get(t.substring(1, t.length() - 1));
            if (c != null) {
                String d = side(c, param);
                if (d != null) {
                    if (c.conditional() && strict) {
                        if (signature == null) {
                            return null;
                        }
                        if (!Pattern.compile(c.whenSignature()).matcher(signature).find()) {
                            return mapType(t, classMap);
                        }
                    }
                    return d;
                }
            }
        }
        return mapType(t, classMap);
    }

    private static String side(Concept c, boolean param) {
        if (param) {
            return c.param() == null ? null : c.param().desc();
        }
        return c.ret() == null ? null : c.ret().desc();
    }

    private static String mapType(String t, Map<String, String> classMap) {
        int i = 0;
        while (i < t.length() && t.charAt(i) == '[') {
            i++;
        }
        if (i >= t.length() || t.charAt(i) != 'L') {
            return t;
        }
        String cls = t.substring(i + 1, t.length() - 1).replace('/', '.');
        String to = classMap.get(cls);
        return to == null ? t : t.substring(0, i) + "L" + to.replace('.', '/') + ";";
    }

    /** true if a parameter or return type of the descriptor is an array of a concept type (no expansion: a HINT) */
    public boolean hasConceptArray(String desc) {
        Matcher m = DESC_TOKEN.matcher(desc);
        while (m.find()) {
            String t = m.group();
            if (t.startsWith("[")) {
                String base = t.replaceFirst("^\\[+", "");
                if (base.startsWith("L") && byInternal.containsKey(base.substring(1, base.length() - 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** the concept types (internal names) a descriptor uses in a position where they apply, in order */
    public List<String> conceptsIn(String desc) {
        List<String> out = new ArrayList<>();
        boolean method = desc.startsWith("(");
        int close = desc.indexOf(')');
        Matcher m = DESC_TOKEN.matcher(desc);
        while (m.find()) {
            String t = m.group();
            if (t.startsWith("L")) {
                String n = t.substring(1, t.length() - 1);
                Concept c = byInternal.get(n);
                boolean param = method && m.start() < close;
                if (c != null && side(c, param) != null) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ expressions

    /** the 1.7.10 argument expressions for one 1.19.2 argument of the concept {@code type}; empty = dropped */
    public List<String> expand(String type, String argExpr) {
        Concept c = find(type).orElseThrow(() -> new IllegalArgumentException("not a concept: " + type));
        if (c.param() == null) {
            throw new IllegalArgumentException("concept has no parameter side: " + type);
        }
        List<String> out = new ArrayList<>();
        String arg = atom(argExpr);
        for (String e : c.param().expand()) {
            out.add(e.equals("$a") ? argExpr : e.replace("$a", arg));
        }
        return out;
    }

    /** the number of times {@link #expand} evaluates the argument (more than 1: the argument must be free of effects) */
    public int argUses(String type) {
        Concept c = find(type).orElseThrow(() -> new IllegalArgumentException("not a concept: " + type));
        int n = 0;
        for (String e : c.param().expand()) {
            for (int i = e.indexOf("$a"); i >= 0; i = e.indexOf("$a", i + 2)) {
                n++;
            }
        }
        return n;
    }

    /** the expression that turns a 1.7.10 call result into the concept type the 1.19.2 code expects */
    public String wrap(String type, String callExpr) {
        Concept c = find(type).orElseThrow(() -> new IllegalArgumentException("not a concept: " + type));
        if (c.ret() == null) {
            throw new IllegalArgumentException("concept has no result side: " + type);
        }
        return c.ret().wrap().equals("$e") ? callExpr : c.ret().wrap().replace("$e", callExpr);
    }

    /** imports the expressions of {@code type} need (parameter side, or result side) */
    public List<String> imports(String type, boolean resultSide) {
        Concept c = find(type).orElseThrow(() -> new IllegalArgumentException("not a concept: " + type));
        if (resultSide) {
            return c.ret() == null ? List.of() : c.ret().imports();
        }
        return c.param() == null ? List.of() : c.param().imports();
    }

    /** parenthesises an expression that is not a plain access chain, so a member can be appended to it */
    static String atom(String e) {
        int depth = 0;
        for (int i = 0; i < e.length(); i++) {
            char ch = e.charAt(i);
            if (ch == '(' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == ']') {
                depth--;
            } else if (depth == 0 && !(Character.isJavaIdentifierPart(ch) || ch == '.')) {
                return "(" + e + ")";
            }
        }
        return e;
    }

    // ------------------------------------------------------------------------------------------------ shim check

    /**
     * Verifies the table against the shim sources: every {@code shim:} class and every import has a source file;
     * {@code $a.m(} calls name a method the concept's shim declares; {@code Helper.m(} calls name an imported class
     * that declares {@code m}. Returns the problems (empty = ok).
     *
     * @param shimRoots source roots to search ({@code rewrite/shim/src/main/java}, {@code rewrite/shim/src/adapters/java})
     */
    public List<String> check(List<Path> shimRoots) {
        List<String> problems = new ArrayList<>();
        for (Concept c : byInternal.values()) {
            String src = c.shim() == null ? null : source(c.shim(), shimRoots);
            if (c.shim() != null && src == null) {
                problems.add(c.type() + ": shim class not found: " + c.shim());
            }
            if (c.param() != null) {
                for (String e : c.param().expand()) {
                    checkExpr(c, e, src, c.param().imports(), shimRoots, problems);
                }
                checkImports(c, c.param().imports(), shimRoots, problems);
            }
            if (c.ret() != null) {
                checkExpr(c, c.ret().wrap(), src, c.ret().imports(), shimRoots, problems);
                checkImports(c, c.ret().imports(), shimRoots, problems);
            }
        }
        for (Map.Entry<String, TypeEntry> t : types.entrySet()) {
            if (t.getValue().shim() != null && source(t.getValue().shim(), shimRoots) == null) {
                problems.add(t.getKey() + ": shim class not found: " + t.getValue().shim());
            }
        }
        return problems;
    }

    private static void checkImports(Concept c, List<String> imports, List<Path> roots, List<String> problems) {
        for (String i : imports) {
            if (source(i, roots) == null) {
                problems.add(c.type() + ": import not found in the shim sources: " + i);
            }
        }
    }

    private static void checkExpr(Concept c, String expr, String ownSource, List<String> imports, List<Path> roots,
            List<String> problems) {
        Matcher m = CALL.matcher(expr);
        while (m.find()) {
            String target = m.group(1);
            String method = m.group(2);
            if (target.equals("$a") || target.equals("$e")) {
                if (target.equals("$a") && ownSource != null && !declares(ownSource, method) && !INHERITED.contains(method)) {
                    problems.add(c.type() + ": " + c.shim() + " has no method " + method + " (in " + expr + ")");
                }
                continue;
            }
            if (target.contains(".")) {
                continue; // a field chain or a qualified name: JDK / 1.7.10 types, not checked here
            }
            String fqn = null;
            for (String i : imports) {
                if (i.endsWith("." + target)) {
                    fqn = i;
                }
            }
            if (fqn == null) {
                problems.add(c.type() + ": helper " + target + " is not imported (in " + expr + ")");
                continue;
            }
            String src = source(fqn, roots);
            if (src != null && !declares(src, method)) {
                problems.add(c.type() + ": " + fqn + " has no method " + method + " (in " + expr + ")");
            }
        }
    }

    private static boolean declares(String source, String method) {
        return Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(source).find();
    }

    private static String source(String fqn, List<Path> roots) {
        String rel = fqn.replace('.', '/') + ".java";
        for (Path root : roots) {
            Path f = root.resolve(rel);
            if (Files.isRegularFile(f)) {
                try {
                    return Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return null;
    }
}
