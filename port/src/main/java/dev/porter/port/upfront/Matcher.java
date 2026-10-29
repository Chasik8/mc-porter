package dev.porter.port.upfront;

import dev.porter.core.rule.Rule;
import dev.porter.port.upfront.model.Alt;
import dev.porter.port.upfront.model.ApiInventory;
import dev.porter.port.upfront.model.ApiSymbol;
import dev.porter.port.upfront.model.Kind;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.SymbolKey;
import dev.porter.port.upfront.model.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides, for every inventory symbol, what it becomes on the target. Stages run in order and the first accepting stage
 * wins (types: lineage, same name, concept plan, shim rule, simple name; members: lineage, registry, scored candidates in
 * the mapped owner, mirror). All numbers, synonyms and owners come from {@link MatchTables}; no symbol is named here.
 */
public final class Matcher {
    private final MatchTables tables;
    private final IndexData idx;
    private final ConceptDescs concepts;
    private final RuleBook hand;
    private final RuleBook graph;
    private final Features feat;
    /** Normalised source type to an existing target class named by a hand type rule. */
    private final Map<String, String> typeRuleClasses = new HashMap<>();
    private final Map<String, List<String>> hierN = new HashMap<>();
    private final Map<String, List<String>> hierNCache = new HashMap<>();

    public Matcher(MatchTables tables, IndexData idx, ConceptDescs concepts, List<Rule> handRules, List<Rule> graphRules) {
        this.tables = tables;
        this.idx = idx;
        this.concepts = concepts == null ? ConceptDescs.empty() : concepts;
        this.hand = new RuleBook(handRules);
        this.graph = new RuleBook(graphRules);
        for (Map.Entry<String, String> e : hand.directTypes().entrySet()) {
            String t = e.getValue();
            int dot = t.lastIndexOf('.');
            for (String k : new String[] {t, dot < 0 ? t : t.substring(0, dot) + "$" + t.substring(dot + 1)}) {
                if (idx.hasTargetClass(k)) {
                    typeRuleClasses.put(e.getKey(), k);
                    break;
                }
            }
        }
        this.feat = new Features(tables, idx.classMap, typeRuleClasses);
        for (Map.Entry<String, List<String>> e : idx.sourceSupers.entrySet()) {
            hierN.put(RuleBook.norm(e.getKey()), e.getValue().stream().map(RuleBook::norm).toList());
        }
    }

    RuleBook handRules() {
        return hand;
    }

    List<String> supersN(String normOwner) {
        return IndexData.supers(normOwner, hierN, hierNCache);
    }

    private record Res(Plan plan, String method, double conf, Target target, String shim, Map<String, Double> features, List<Alt> alts, String note) {
        static Res todo(String reason, double conf, Map<String, Double> f, List<Alt> alts) {
            return new Res(Plan.TODO, "none:" + reason, conf, null, null, f, alts, null);
        }
    }

    public MatchTable match(ApiInventory inv) {
        List<MatchRow> rows = new ArrayList<>();
        for (ApiSymbol s : inv.symbols()) {
            SymbolKey k = s.key();
            String decl = s.declaredIn() != null && !s.declaredIn().isEmpty() ? s.declaredIn() : k.owner();
            Rule hr;
            Rule gr = null;
            if (k.kind() == Kind.TYPE) {
                hr = hand.typeRule(k.owner());
                gr = hr == null ? graph.typeRule(k.owner()) : null;
            } else {
                hr = hand.memberRule(decl, k.name(), this::supersN);
                gr = hr == null ? graph.memberRule(decl, k.name(), this::supersN) : null;
            }
            String resolvedBy = hr != null ? "rule" : gr != null ? "graph" : "none";
            String ruleId = hr != null ? hr.id() : gr != null ? gr.id() : null;
            Res r = k.kind() == Kind.TYPE ? matchType(k.owner()) : matchMember(k, decl, s.access());
            rows.add(new MatchRow(k, s.uses(), tables.areaOf(decl.isEmpty() ? k.owner() : decl), resolvedBy, r.plan(), r.method(), r.conf(),
                    r.target(), r.shim(), ruleId, r.features(), r.alts(), r.note()));
        }
        return new MatchTable(MatchTable.SCHEMA_VERSION, new MatchTable(MatchTable.SCHEMA_VERSION, rows).sortedRows());
    }

    // ------------------------------------------------------------------ types

    private Res matchType(String t) {
        List<String> keys = new ArrayList<>();
        keys.add(t);
        if (t.indexOf('$') < 0) {
            String[] parts = t.split("\\.");
            for (int i = parts.length - 1; i > 0; i--) {
                if (Character.isUpperCase(parts[i].charAt(0)) && Character.isUpperCase(parts[i - 1].charAt(0))) {
                    keys.add(String.join(".", java.util.Arrays.copyOfRange(parts, 0, i)) + "$" + String.join("$", java.util.Arrays.copyOfRange(parts, i, parts.length)));
                }
            }
        }
        for (String k : keys) {
            String m = idx.classMap.get(k);
            if (m != null) {
                return typeRes(Plan.MAP, "lineage", 0.9, m, null);
            }
            if (idx.hasTargetClass(k)) {
                return typeRes(Plan.MAP, "same-fqn", 0.95, k, null);
            }
        }
        for (String k : keys) {
            ConceptDescs.TypePlan p = concepts.planOf(k);
            if (p != null && p.plan() != null && (p.plan().equals("EXPAND") || p.plan().equals("MIRROR"))) {
                return new Res(Plan.valueOf(p.plan()), p.plan().equals("EXPAND") ? "lineage-concept" : "mirror", 0.9, null, p.shim(), Map.of(), List.of(), null);
            }
            String shim = hand.shimOf(k);
            if (shim != null) {
                return new Res(Plan.MIRROR, "mirror", 0.9, null, shim, Map.of(), List.of(), null);
            }
        }
        String simple = t.substring(t.lastIndexOf('.') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1).toLowerCase(Locale.ROOT);
        List<String> c = idx.targetBySimple.getOrDefault(simple, List.of());
        if (c.size() == 1) {
            return typeRes(Plan.MAP, "simple-name", 0.5, c.get(0), null);
        }
        return Res.todo(c.isEmpty() ? "no-candidates" : "ambiguous", 0.0, Map.of(), List.of());
    }

    private static Res typeRes(Plan p, String method, double conf, String cls, String shim) {
        return new Res(p, method, conf, new Target(cls, "", "", false), shim, Map.of(), List.of(), null);
    }

    // ------------------------------------------------------------------ members

    private Res matchMember(SymbolKey k, String owner, int access) {
        String kind = k.kind() == Kind.METHOD ? "method" : "field";
        String name = k.name();
        String desc = k.desc();
        // 3. lineage on the declaring class, then on each source supertype
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(idx.sourceSupers(owner));
        for (String o : chain) {
            IndexData.Lin e = idx.lineage.get(IndexData.lineageKey(kind, o, name, desc));
            if (e == null) {
                continue;
            }
            if (e.brokenAt().isEmpty() && e.conf() >= tables.lineageMin) {
                Object[] ex = idx.findExisting(e.tOwner(), e.tName(), e.tDesc());
                if (ex != null) {
                    IndexData.Mem m = (IndexData.Mem) ex[1];
                    Plan plan;
                    String method;
                    if (e.mechanical()) {
                        plan = Plan.MAP;
                        method = "lineage";
                    } else if (concepts.conceptDesc(desc, idx.classMap, typeRuleClasses, tables.conceptOwners).equals(e.tDesc())) {
                        plan = Plan.EXPAND;
                        method = "lineage-concept";
                    } else {
                        if (kind.equals("field") && tables.registryOwners.contains(owner)) {
                            // a registry constant whose 1.7.10 field has a narrower type (ItemBow): the flattening table answers, not a body
                            return new Res(Plan.REGISTRY, "registry", 0.0, null, null, Map.of(), List.of(), null);
                        }
                        plan = Plan.HINT;
                        method = "lineage-sig";
                    }
                    return mapped(plan, method, e.conf(), (String) ex[0], m, access, Map.of("lineage", e.conf()), List.of());
                }
            }
            break;
        }
        // 4. registry constants
        if (tables.registryOwners.contains(owner)) {
            return new Res(Plan.REGISTRY, "registry", 0.0, null, null, Map.of(), List.of(), null);
        }
        if (name.equals("<init>")) {
            return mirrorOr(k, owner, Res.todo("no-lineage", 0.0, Map.of(), List.of()));
        }
        // 5. owner for scoring
        String[] to = targetOwner(owner);
        if (to == null) {
            return mirrorOr(k, owner, Res.todo("no-owner", 0.0, Map.of(), List.of()));
        }
        // 6. candidates
        record Cand(double score, String owner, IndexData.Mem mem, Map<String, Double> f) {
        }
        List<Cand> cands = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> tchain = new ArrayList<>();
        tchain.add(to[0]);
        tchain.addAll(idx.targetSupers(to[0]));
        List<String> pa = Descs.params(desc);
        boolean getter = k.kind() == Kind.METHOD && desc.startsWith("()");
        boolean setter = k.kind() == Kind.METHOD && pa != null && pa.size() == 1 && desc.endsWith(")V");
        for (String c : tchain) {
            for (IndexData.Mem m : idx.targetMembers.getOrDefault(c, List.of())) {
                boolean acc = k.kind() == Kind.METHOD && m.kind().equals("field") && (getter || setter);
                if ((!m.kind().equals(kind) && !acc) || m.name().equals("<clinit>") || m.name().equals("<init>") || m.isPrivate()
                        || !seen.add(m.name() + m.desc())) {
                    continue;
                }
                Map<String, Double> f;
                double s;
                if (acc && !m.kind().equals(kind)) {
                    f = feat.accessor(name, desc, getter, m.name(), m.desc());
                    s = feat.score(f) - tables.accessorPenalty;
                } else {
                    f = feat.pair(name, desc, m.name(), m.desc());
                    s = feat.score(f);
                }
                cands.add(new Cand(s, c, m, f));
            }
        }
        if (cands.isEmpty()) {
            return mirrorOr(k, owner, Res.todo("no-candidates", 0.0, Map.of(), List.of()));
        }
        cands.sort((a, b) -> Double.compare(b.score(), a.score()));
        Cand b = cands.get(0);
        double second = cands.size() > 1 ? cands.get(1).score() : 0.0;
        List<Alt> alts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            Cand c = cands.get(i);
            alts.add(new Alt(c.owner(), c.mem().name(), c.mem().desc(), Math.round(c.score() * 1000) / 1000.0));
        }
        double bs = Math.round(b.score() * 1000) / 1000.0;
        if (b.score() >= tables.accept && b.score() - second >= tables.margin) {
            return mapped(Plan.MAP, "scored:" + to[1], bs, b.owner(), b.mem(), access, b.f(), alts);
        }
        return mirrorOr(k, owner, Res.todo("below-threshold", bs, b.f(), alts));
    }

    /** MAP, or HINT when an instance member would become a static one. */
    private Res mapped(Plan plan, String method, double conf, String tOwner, IndexData.Mem m, int access, Map<String, Double> f, List<Alt> alts) {
        boolean srcStatic = (access & 0x0008) != 0;
        String note = null;
        if (access >= 0 && !srcStatic && m.isStatic() && !m.kind().equals("field") && plan != Plan.HINT) {
            plan = Plan.HINT;
            note = "instance to static";
        }
        return new Res(plan, method, conf, new Target(tOwner, m.name(), m.desc(), m.isStatic()), null, f, alts, note);
    }

    /** Step 7: a member of a mirror (shim) type, unless a MAP was accepted. */
    private Res mirrorOr(SymbolKey k, String owner, Res fallback) {
        String cur = owner;
        while (true) {
            ConceptDescs.TypePlan p = concepts.planOf(cur);
            String shim = hand.shimOf(cur);
            if (p != null && ("MIRROR".equals(p.plan()) || p.shim() != null)) {
                return new Res(Plan.MIRROR, "mirror", 0.8, null, p.shim() != null ? p.shim() : shim, fallback.features(), fallback.alts(), fallback.method());
            }
            if (shim != null) {
                return new Res(Plan.MIRROR, "mirror", 0.8, null, shim, fallback.features(), fallback.alts(), fallback.method());
            }
            int i = cur.lastIndexOf('$');
            if (i < 0) {
                return fallback;
            }
            cur = cur.substring(0, i);
        }
    }

    /** {target class, owner source}: concept owner, hand type rule, lineage class map, same FQN; walking source supertypes. */
    private String[] targetOwner(String owner) {
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(idx.sourceSupers(owner));
        for (String c : chain) {
            String co = tables.conceptOwners.get(c);
            if (co != null) {
                return new String[] {co, "concept-owner"};
            }
            String tr = typeRuleClasses.get(RuleBook.norm(c));
            if (tr != null) {
                return new String[] {tr, "type-rule-owner"};
            }
            String lm = idx.classMap.get(c);
            if (lm != null) {
                return new String[] {lm, "lineage-class"};
            }
            if (idx.hasTargetClass(c)) {
                return new String[] {c, "same-fqn"};
            }
        }
        return null;
    }

    /** For tests and the eval: the table as a map from key text to row. */
    static Map<String, MatchRow> byKey(MatchTable t) {
        Map<String, MatchRow> m = new LinkedHashMap<>();
        t.rows().forEach(r -> m.put(r.key().toString(), r));
        return m;
    }
}
