package dev.porter.port.upfront;

import dev.porter.core.rule.Rule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index over a rule list with the semantics of {@code Coverage.measure}: a type is resolved by a rule whose
 * {@code match.type} equals it ({@code $} and {@code .} alike); a member by a call/field/override rule of the same name
 * on its declaring class or a subtype of it. Also classifies what a hand rule maps a symbol to ({@link #gtOf}).
 */
final class RuleBook {
    static final List<String> TARGET_NS = List.of("net.minecraft.", "net.minecraftforge.", "cpw.mods.");
    private static final Pattern IDENT = Pattern.compile("\\.\\s*([a-zA-Z_]\\w*)\\s*(\\()?");

    /** Rule target classification: {@code direct} (names), {@code shim}, {@code idiom}. */
    record Gt(String kind, Set<String> names) {
    }

    private record OwnerRule(String owner, Rule rule) {
    }

    private final Map<String, Rule> types = new HashMap<>();
    private final Map<String, List<OwnerRule>> members = new HashMap<>();
    private final Set<String> shimTypes = new HashSet<>();
    /** Type rules that point to a vanilla/Forge class: normalised source type to target FQN. */
    private final Map<String, String> directTypes = new HashMap<>();
    /** Type rules that point to a shim: normalised source type to the shim text. */
    private final Map<String, String> shimTypeTargets = new HashMap<>();

    RuleBook(List<Rule> rules) {
        for (Rule r : rules) {
            Rule.Match mt = r.match();
            String rp = r.replace() == null ? "" : r.replace();
            if (mt.type() != null) {
                String n = norm(mt.type());
                types.putIfAbsent(n, r);
                if (startsWithTarget(rp) && !isShimText(rp)) {
                    directTypes.putIfAbsent(n, rp.strip());
                } else {
                    shimTypes.add(n);
                    shimTypeTargets.putIfAbsent(n, rp.strip());
                }
            }
            for (String sel : new String[] {mt.call(), mt.field(), mt.override()}) {
                if (sel != null && sel.contains("#")) {
                    String owner = norm(sel.substring(0, sel.indexOf('#')));
                    String rest = sel.substring(sel.indexOf('#') + 1);
                    String name = rest.contains("(") ? rest.substring(0, rest.indexOf('(')).strip() : rest.strip();
                    members.computeIfAbsent(name, x -> new ArrayList<>()).add(new OwnerRule(owner, r));
                }
            }
        }
    }

    static String norm(String t) {
        return t.replace('$', '.').strip();
    }

    static boolean startsWithTarget(String s) {
        for (String p : TARGET_NS) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isShimText(String s) {
        return s.contains("dev.porter") || s.toLowerCase(Locale.ROOT).contains("shim");
    }

    Rule typeRule(String type) {
        return types.get(norm(type));
    }

    /** Normalised source type to vanilla/Forge target class named by a hand type rule. */
    Map<String, String> directTypes() {
        return directTypes;
    }

    /** Shim text a hand type rule maps the type to, or null. */
    String shimOf(String type) {
        return shimTypeTargets.get(norm(type));
    }

    boolean isShimType(String normType) {
        return shimTypes.contains(normType);
    }

    /** The first rule naming this member on {@code declaredIn} or a subtype of it; {@code supersN} gives normalised supertypes. */
    Rule memberRule(String declaredIn, String name, Function<String, List<String>> supersN) {
        String decl = norm(declaredIn);
        for (OwnerRule or : members.getOrDefault(name, List.of())) {
            if (or.owner().equals(decl) || supersN.apply(or.owner()).contains(decl)) {
                return or.rule();
            }
        }
        return null;
    }

    /** What a hand rule maps its symbol to. */
    Gt gtOf(Rule rule) {
        String rp = rule.replace() == null ? "" : rule.replace();
        Rule.Match mt = rule.match();
        if (mt.type() != null) {
            if (startsWithTarget(rp) && !isShimText(rp)) {
                return new Gt("direct", Set.of(rp.strip()));
            }
            return new Gt("shim", Set.of(rp));
        }
        StringBuilder extra = new StringBuilder();
        if (rule.imports() != null) {
            rule.imports().forEach(i -> extra.append(i).append(' '));
        }
        extra.append(' ');
        if (rule.preconditions() != null) {
            rule.preconditions().forEach(i -> extra.append(i).append(' '));
        }
        String sel = mt.call() != null ? mt.call() : mt.field() != null ? mt.field() : mt.override();
        String owner = sel == null ? "" : norm(sel.split("#")[0]);
        if (isShimText(rp + " " + extra) || shimTypes.contains(owner)) {
            return new Gt("shim", Set.of(rp));
        }
        if (mt.override() != null) {
            String[] w = rp.strip().split("\\s+");
            return new Gt("direct", w.length == 0 || w[0].isEmpty() ? Set.of() : Set.of(w[w.length - 1]));
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(rp);
        while (m.find()) {
            String n = m.group(1);
            if (!Character.isUpperCase(n.charAt(0))) {
                names.add(n);
            }
        }
        if (names.isEmpty()) {
            return new Gt("idiom", Set.of(rp));
        }
        return new Gt("direct", names);
    }
}
