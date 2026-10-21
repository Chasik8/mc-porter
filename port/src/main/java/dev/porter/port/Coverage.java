package dev.porter.port;

import dev.porter.core.rule.Rule;
import dev.porter.port.passes.SrgMap;
import dev.porter.rewrite.engine.TypeFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reuse metric: which share of a mod's distinct platform API symbols the existing rules (public packs, private
 * packs, mechanical rules from the symbol graph) already resolve, before any AI work. Symbols:
 * <ul>
 *   <li>types: single-type and static imports of platform packages ({@code net.minecraft.}, {@code net.minecraftforge.},
 *       {@code com.mojang.} and {@code --platform} prefixes);</li>
 *   <li>members: SRG ids in the source ({@code m_123_}, {@code f_123_}, the member names of every decompiled
 *       1.17+ jar), mapped to {@code Owner#name} with the source version's SRG map.</li>
 * </ul>
 * A type is resolved when a rule matches it ({@code match.type}); a member when a call/field/override rule names it
 * on its declaring class or a subtype ({@code exact}), {@code probable} when only the name matches a rule on an
 * unrelated owner. Limits: members of sources with mojmap (not SRG) names are not counted (no type attribution);
 * a resolved symbol may still need an adapter at run time. Symbols the mod's own classes declare are ignored.
 */
public final class Coverage {
    public static final List<String> PLATFORM = List.of("net.minecraft.", "net.minecraftforge.", "com.mojang.");
    private static final Pattern IMPORT = Text.re("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern SRG = Text.re("\\b([mf]_\\d+_)\\b");

    /** One symbol and its status. */
    public record Sym(String key, String status, int uses) {
    }

    public record Result(int types, int typesResolved, int members, int membersExact, int membersProbable, List<Sym> unresolved) {
        public int total() {
            return types + members;
        }

        public int resolved() {
            return typesResolved + membersExact;
        }

        public double share() {
            return total() == 0 ? 1.0 : resolved() / (double) total();
        }

        public Map<String, Object> toJson(boolean withList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("distinctSymbols", total());
            m.put("resolvedByRules", resolved());
            m.put("share", Math.round(share() * 1000) / 1000.0);
            m.put("types", Map.of("total", types, "resolved", typesResolved));
            Map<String, Object> mem = new LinkedHashMap<>();
            mem.put("total", members);
            mem.put("exact", membersExact);
            mem.put("probable", membersProbable);
            m.put("members", mem);
            if (withList) {
                m.put("unresolved", unresolved.stream().map(s -> s.status() + " " + s.key() + " (" + s.uses() + ")").toList());
            }
            return m;
        }
    }

    private Coverage() {
    }

    private static String norm(String t) {
        return t.replace('$', '.').strip();
    }

    public static Result measure(List<Path> files, List<Rule> rules, SrgMap srg, List<String> extraPlatform, TypeFacts facts) {
        List<String> platform = new ArrayList<>(PLATFORM);
        platform.addAll(extraPlatform);
        Map<String, Integer> types = new TreeMap<>();
        Map<String, Integer> members = new TreeMap<>();
        Map<String, String[]> memberRef = new HashMap<>();
        for (Path p : files) {
            String s = Text.read(p);
            Matcher m = IMPORT.matcher(s);
            while (m.find()) {
                String fq = m.group(2);
                if (m.group(1) != null) {
                    fq = fq.substring(0, Math.max(0, fq.lastIndexOf('.')));
                }
                String f = fq;
                if (platform.stream().anyMatch(f::startsWith)) {
                    types.merge(fq, 1, Integer::sum);
                }
            }
            Matcher r = SRG.matcher(s);
            while (r.find()) {
                String[] v = srg.get(r.group(1));
                if (v != null) {
                    String k = v[0] + "#" + v[1];
                    members.merge(k, 1, Integer::sum);
                    memberRef.put(k, v);
                }
            }
        }
        Set<String> ruleTypes = new TreeSet<>();
        Map<String, List<String>> ruleMembers = new HashMap<>(); // name -> owners
        for (Rule rule : rules) {
            Rule.Match mt = rule.match();
            if (mt.type() != null) {
                ruleTypes.add(norm(mt.type()));
            }
            for (String sel : new String[] {mt.call(), mt.field(), mt.override()}) {
                if (sel != null && sel.contains("#")) {
                    String owner = norm(sel.substring(0, sel.indexOf('#')));
                    String rest = sel.substring(sel.indexOf('#') + 1);
                    String name = rest.contains("(") ? rest.substring(0, rest.indexOf('(')).strip() : rest.strip();
                    ruleMembers.computeIfAbsent(name, x -> new ArrayList<>()).add(owner);
                }
            }
        }
        List<Sym> unresolved = new ArrayList<>();
        int tr = 0;
        for (Map.Entry<String, Integer> e : types.entrySet()) {
            if (ruleTypes.contains(norm(e.getKey()))) {
                tr++;
            } else {
                unresolved.add(new Sym("type " + e.getKey(), "unresolved", e.getValue()));
            }
        }
        int exact = 0;
        int probable = 0;
        for (Map.Entry<String, Integer> e : members.entrySet()) {
            String[] v = memberRef.get(e.getKey());
            String decl = norm(v[0]);
            List<String> owners = ruleMembers.getOrDefault(v[1], List.of());
            boolean ex = false;
            for (String o : owners) {
                if (o.equals(decl) || facts.isSubtype(o, decl) == TypeFacts.Tri.YES) {
                    ex = true;
                    break;
                }
            }
            if (ex) {
                exact++;
            } else if (!owners.isEmpty()) {
                probable++;
                unresolved.add(new Sym("member " + e.getKey(), "probable", e.getValue()));
            } else {
                unresolved.add(new Sym("member " + e.getKey(), "unresolved", e.getValue()));
            }
        }
        unresolved.sort((a, b) -> Integer.compare(b.uses(), a.uses()));
        return new Result(types.size(), tr, members.size(), exact, probable, unresolved);
    }
}
