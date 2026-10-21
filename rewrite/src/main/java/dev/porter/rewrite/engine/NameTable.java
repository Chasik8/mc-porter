package dev.porter.rewrite.engine;

import dev.porter.rewrite.graph.SymbolGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Member names of the source version in all namespaces, from symbol-graph symbols. Lets rules written with
 * canonical (mojmap) names match code that uses SRG names ({@code m_128451_}, {@code f_46441_}) — the form of
 * every decompiled Forge 1.17+ mod jar. SRG ids are unique per member family (an override shares the id of the
 * method it overrides), so an SRG name identifies the member regardless of the receiver's static type.
 */
public final class NameTable {

    /** One member (method or field) of the source version, in canonical names. */
    public record Member(String kind, String owner, String name, String desc, Set<String> aliases) {
        /** Parameter types of a method descriptor as Java type names, e.g. {@code [java.lang.String, int]}. */
        public List<String> paramTypes() {
            return desc == null || !desc.startsWith("(") ? List.of() : Descriptors.params(desc);
        }

        public String display() {
            return "method".equals(kind)
                    ? owner + "#" + name + "(" + String.join(", ", paramTypes().stream().map(Selector::simple).toList()) + ")"
                    : owner + "#" + name;
        }
    }

    private final Map<String, List<Member>> byAlias = new HashMap<>();
    private final Map<String, List<Member>> byOwnerName = new HashMap<>();

    public static NameTable empty() {
        return new NameTable();
    }

    /** Builds the table for one bare Minecraft version (e.g. {@code 1.19.2}). */
    public static NameTable of(SymbolGraph graph, String version) {
        NameTable t = new NameTable();
        for (SymbolGraph.Symbol s : graph.symbols()) {
            SymbolGraph.Ref r = s.ref();
            if (!version.equals(r.version()) || "class".equals(r.kind())) {
                continue;
            }
            Set<String> aliases = new TreeSet<>();
            for (String n : s.names().values()) {
                if (!n.equals(r.name()) && n.length() > 2) { // obf names (a, b, ...) are too ambiguous to alias
                    aliases.add(n);
                }
            }
            Member m = new Member(r.kind(), r.owner(), r.name(), r.desc(), aliases);
            for (String a : aliases) {
                t.byAlias.computeIfAbsent(a, k -> new ArrayList<>()).add(m);
            }
            t.byOwnerName.computeIfAbsent(r.owner() + "#" + r.name(), k -> new ArrayList<>()).add(m);
        }
        return t;
    }

    /** Members that have {@code alias} as a non-canonical name (empty if it is not an alias). */
    public List<Member> resolveAlias(String alias) {
        return byAlias.getOrDefault(alias, List.of());
    }

    /** Declared members {@code owner#name} (all overloads). */
    public List<Member> members(String owner, String name) {
        return byOwnerName.getOrDefault(owner + "#" + name, List.of());
    }

    public boolean isEmpty() {
        return byAlias.isEmpty() && byOwnerName.isEmpty();
    }

    /** Matches SRG-style names that carry no meaning on their own. */
    public static boolean looksLikeSrg(String name) {
        return name.matches("[mfp]_\\d+_");
    }
}
