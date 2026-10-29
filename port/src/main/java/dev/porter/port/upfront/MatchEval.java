package dev.porter.port.upfront;

import dev.porter.core.rule.Rule;
import dev.porter.port.upfront.model.Kind;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Precision/recall of the table against the hand rules (graph rules excluded: they come from the same lineage).
 * Ground truth = hand rules whose target is a vanilla/Forge name ("direct"); rules that keep the source name are
 * reported apart. Every number is computed from the rules and the table; nothing is tuned per symbol.
 */
public final class MatchEval {
    /** Result for one kind. */
    public record Score(String kind, int gt, int keptName, int tp, int fp, int fpAlt, int fn, Map<String, Integer> byMethod) {
        public double precision() {
            return tp + fp == 0 ? 0 : tp / (double) (tp + fp);
        }

        public double recall() {
            return gt == 0 ? 0 : tp / (double) gt;
        }
    }

    /**
     * @param handRules      distinct hand rules (by id) that resolve at least one inventory symbol
     * @param rulesByItself  of those, the rules whose every symbol the table reproduces without them (direct: same target
     *                       name; shim: planned MIRROR or a shim helper; idiom: planned EXPAND)
     * @param rowsByItself   the same count over symbols
     */
    public record Result(Score types, Score members, int handRows, int unresolvedAuto, int unresolved, List<String> lines,
                         int handRules, int rulesByItself, int rowsByItself, int scopeRules, int scopeUsed, int scopeByItself) {
    }

    private MatchEval() {
    }

    public static Result run(MatchTable table, Matcher matcher, IndexData idx) {
        return run(table, matcher, idx, null);
    }

    /** @param scope ids of the rules of one rule set (a profile): the by-itself count is also given for them; may be null */
    public static Result run(MatchTable table, Matcher matcher, IndexData idx, Set<String> scope) {
        RuleBook hand = matcher.handRules();
        List<String> out = new ArrayList<>();
        List<MatchRow> handRows = new ArrayList<>();
        Map<MatchRow, RuleBook.Gt> gts = new java.util.HashMap<>();
        for (MatchRow r : table.rows()) {
            if (!"rule".equals(r.resolvedBy())) {
                continue;
            }
            Rule rule = r.kind() == Kind.TYPE ? hand.typeRule(r.key().owner()) : hand.memberRule(r.key().owner(), r.key().name(), matcher::supersN);
            if (rule != null) {
                handRows.add(r);
                gts.put(r, hand.gtOf(rule));
            }
        }
        long resolved = table.rows().stream().filter(r -> !"none".equals(r.resolvedBy())).count();
        out.add(String.format(Locale.ROOT, "symbols %d, resolved %d = hand rules %d + graph %d", table.rows().size(), resolved, handRows.size(),
                resolved - handRows.size()));
        Map<String, Integer> kinds = new TreeMap<>();
        gts.values().forEach(g -> kinds.merge(g.kind(), 1, Integer::sum));
        out.add("hand ground truth kinds: " + kinds);
        Score types = score("type", handRows, gts, idx, true, out);
        Score members = score("member", handRows, gts, idx, false, out);
        List<MatchRow> sh = handRows.stream().filter(r -> !gts.get(r).kind().equals("direct")).toList();
        out.add(String.format(Locale.ROOT, "shim/idiom GT %d: matcher proposes a 1.7.10 target for %d", sh.size(),
                sh.stream().filter(r -> r.target() != null).count()));
        List<MatchRow> un = table.rows().stream().filter(r -> "none".equals(r.resolvedBy())).toList();
        Map<String, Integer> by = new TreeMap<>();
        int auto = 0;
        for (MatchRow r : un) {
            if (r.plan() == Plan.MAP || r.plan() == Plan.EXPAND || r.plan() == Plan.HINT) {
                auto++;
                by.merge(r.method().split(":")[0], 1, Integer::sum);
            }
        }
        out.add(String.format(Locale.ROOT, "UNRESOLVED %d: auto-matched (MAP/EXPAND/HINT) %d %s", un.size(), auto, by));
        Map<String, Integer> plans = new TreeMap<>();
        un.forEach(r -> plans.merge(r.plan().name(), 1, Integer::sum));
        out.add("  unresolved by plan: " + plans);
        Map<String, Boolean> ruleOk = new TreeMap<>();
        int rowsOk = 0;
        for (MatchRow r : handRows) {
            boolean ok = reproduced(r, gts.get(r), idx);
            rowsOk += ok ? 1 : 0;
            ruleOk.merge(r.ruleId() == null ? "?" : r.ruleId(), ok, Boolean::logicalAnd);
        }
        int rulesOk = (int) ruleOk.values().stream().filter(b -> b).count();
        out.add(String.format(Locale.ROOT, "by itself: %d of %d hand-resolved symbols, %d of %d hand rules (every symbol of the rule)", rowsOk,
                handRows.size(), rulesOk, ruleOk.size()));
        int sc = scope == null ? 0 : scope.size();
        int scUsed = scope == null ? 0 : (int) ruleOk.keySet().stream().filter(scope::contains).count();
        int scOk = scope == null ? 0 : (int) ruleOk.entrySet().stream().filter(e -> scope.contains(e.getKey()) && e.getValue()).count();
        if (scope != null) {
            out.add(String.format(Locale.ROOT, "scope: %d rules, %d used by the inventory, %d by itself", sc, scUsed, scOk));
        }
        return new Result(types, members, handRows.size(), auto, un.size(), out, ruleOk.size(), rulesOk, rowsOk, sc, scUsed, scOk);
    }

    /** Would the table, without the hand rule, have produced what the rule does? */
    private static boolean reproduced(MatchRow r, RuleBook.Gt g, IndexData idx) {
        switch (g.kind()) {
            case "direct" -> {
                if (r.target() == null) {
                    return false;
                }
                Set<String> names = g.names().stream().map(v -> RuleBook.norm(idx.srgToMcp.getOrDefault(v, v))).collect(Collectors.toSet());
                if (r.kind() == Kind.TYPE) {
                    return names.contains(RuleBook.norm(r.target().owner()));
                }
                String tn = r.target().name();
                return names.contains(RuleBook.norm(tn)) || names.contains(RuleBook.norm(idx.srgToMcp.getOrDefault(tn, tn)));
            }
            case "shim" -> {
                return r.plan() == Plan.MIRROR || (r.shim() != null && (r.plan() == Plan.EXPAND || r.plan() == Plan.MAP));
            }
            default -> {
                return r.plan() == Plan.EXPAND;
            }
        }
    }

    private static Score score(String label, List<MatchRow> handRows, Map<MatchRow, RuleBook.Gt> gts, IndexData idx, boolean types, List<String> out) {
        List<MatchRow> sel = new ArrayList<>();
        int kept = 0;
        for (MatchRow r : handRows) {
            RuleBook.Gt g = gts.get(r);
            if ((r.kind() == Kind.TYPE) != types || !g.kind().equals("direct")) {
                continue;
            }
            if (!types && g.names().equals(Set.of(r.key().name()))) {
                kept++;
                continue;
            }
            sel.add(r);
        }
        int tp = 0;
        int fp = 0;
        int alt = 0;
        int fn = 0;
        Map<String, Integer> by = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        for (MatchRow r : sel) {
            String m = r.method() == null ? "none" : r.method().split(":")[0];
            if (r.target() == null) {
                fn++;
                by.merge(m + "/none", 1, Integer::sum);
                continue;
            }
            Set<String> g = gts.get(r).names().stream().map(v -> RuleBook.norm(idx.srgToMcp.getOrDefault(v, v))).collect(Collectors.toSet());
            boolean ok;
            if (types) {
                ok = g.contains(RuleBook.norm(r.target().owner()));
            } else {
                String tn = r.target().name();
                ok = g.contains(RuleBook.norm(tn)) || g.contains(RuleBook.norm(idx.srgToMcp.getOrDefault(tn, tn)));
            }
            if (ok) {
                tp++;
                by.merge(m + "/ok", 1, Integer::sum);
            } else {
                fp++;
                by.merge(m + "/bad", 1, Integer::sum);
                boolean exists = !types && idx.hasMemberNamed(r.target().owner(), g);
                if (exists) {
                    alt++;
                }
                bad.add(String.format(Locale.ROOT, "  FP %s -> %s#%s%s  gt=%s (%s %.2f)%s", r.key(), r.target().owner(), r.target().name(), r.target().desc(),
                        g.stream().sorted().limit(3).toList(), r.method(), r.confidence(), exists ? " [GT name also exists on target owner]" : ""));
            }
        }
        Score s = new Score(label, sel.size(), kept, tp, fp, alt, fn, by);
        out.add(String.format(Locale.ROOT, "%s hand-direct GT %d (+%d kept-name): TP %d FP %d (GT name also exists on the target owner: %d) FN %d  precision %.3f recall %.3f",
                label, sel.size(), kept, tp, fp, alt, fn, s.precision(), s.recall()));
        out.add("  by method: " + by);
        out.addAll(bad.stream().sorted().limit(60).toList());
        return s;
    }
}
