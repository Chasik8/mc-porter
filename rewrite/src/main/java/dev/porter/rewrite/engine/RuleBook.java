package dev.porter.rewrite.engine;

import dev.porter.core.rule.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rules of one version pair, prepared (parsed selectors/templates) and indexed by what they match. */
public final class RuleBook {

    /** A rule ready for matching. {@code imports}/{@code template} already have the shim package relocated. */
    public record Prepared(Rule rule, Selector selector, Template template, String replaceText, List<String> imports,
            List<String> preconditions, CodePattern pattern) {
        public String id() {
            return rule.id();
        }

        public int priority() {
            return rule.priorityOrDefault();
        }

        /**
         * Type rule whose replacement is the matched type itself: "known identical in the target". Type replacements
         * are written in canonical form ({@code a.b.Outer.Inner}): {@code $} would read as a placeholder.
         */
        public boolean identityType() {
            return selector.kind() == Selector.Kind.TYPE
                    && Selector.canonical(selector.owner()).equals(Selector.canonical(replaceText.trim()));
        }
    }

    public static final String SHIM_PACKAGE = "dev.porter.shim";

    private static final Comparator<Prepared> ORDER = Comparator.comparingInt(Prepared::priority).reversed()
            .thenComparing(Prepared::id);

    private final List<Prepared> all = new ArrayList<>();
    private final Map<String, List<Prepared>> calls = new HashMap<>();
    private final Map<String, List<Prepared>> news = new HashMap<>();
    private final Map<String, List<Prepared>> fields = new HashMap<>();
    private final Map<String, List<Prepared>> types = new HashMap<>();
    private final Map<String, List<Prepared>> overrides = new HashMap<>();
    private final List<Prepared> unsupported = new ArrayList<>();
    private final Map<String, String> problems = new HashMap<>();
    private final List<Prepared> expressionPatterns = new ArrayList<>();
    private final List<Prepared> statementPatterns = new ArrayList<>();
    private final List<Prepared> declarations = new ArrayList<>();

    /**
     * @param shimPackage package the shim is relocated to in the output ({@link #SHIM_PACKAGE} = no relocation)
     */
    public RuleBook(List<Rule> rules, String shimPackage) {
        Set<String> ids = new HashSet<>();
        for (Rule r : rules) {
            if (!ids.add(r.id())) {
                throw new IllegalArgumentException("duplicate rule id " + r.id());
            }
            String replace = Template.markTargets(relocate(r.replace() == null ? "" : r.replace(), shimPackage));
            List<String> imports = r.imports() == null ? List.of()
                    : r.imports().stream().map(i -> relocate(i, shimPackage)).toList();
            Selector sel = Selector.of(r.match());
            CodePattern pattern = null;
            String problem = null;
            if (sel.kind() == Selector.Kind.PATTERN) {
                try {
                    pattern = CodePattern.parse(r.match().pattern());
                } catch (IllegalArgumentException e) {
                    problem = e.getMessage();
                }
                if (pattern != null && replace.isBlank() && !pattern.statements()) {
                    problem = "an empty replacement needs a statement pattern";
                }
            } else if (sel.kind() == Selector.Kind.DECLARATION) {
                if (r.edit() == null) {
                    problem = "declaration rule without edit";
                }
            } else if (replace.isBlank()) {
                problem = "empty replacement";
            }
            Prepared p = new Prepared(r, sel, Template.parse(replace), replace, imports,
                    r.preconditions() == null ? List.of() : r.preconditions(), pattern);
            all.add(p);
            if (problem != null) {
                unsupported.add(p);
                problems.put(r.id(), problem);
                continue;
            }
            switch (sel.kind()) {
                case CALL -> calls.computeIfAbsent(sel.name(), k -> new ArrayList<>()).add(p);
                case NEW -> news.computeIfAbsent(sel.owner(), k -> new ArrayList<>()).add(p);
                case FIELD -> fields.computeIfAbsent(sel.name(), k -> new ArrayList<>()).add(p);
                case TYPE -> types.computeIfAbsent(sel.owner(), k -> new ArrayList<>()).add(p);
                case OVERRIDE -> overrides.computeIfAbsent(sel.name(), k -> new ArrayList<>()).add(p);
                case PATTERN -> (pattern.statements() ? statementPatterns : expressionPatterns).add(p);
                case DECLARATION -> declarations.add(p);
            }
        }
        for (Map<String, List<Prepared>> m : List.of(calls, news, fields, types, overrides)) {
            m.values().forEach(l -> l.sort(ORDER));
        }
        expressionPatterns.sort(ORDER);
        statementPatterns.sort(ORDER);
        declarations.sort(ORDER);
    }

    /**
     * Replacement text with the bound placeholders renamed by position ({@code $s} of one rule and {@code $a0} of
     * another, both bound to argument 0, are the same): two rules that write the same code are not ambiguous.
     */
    public static String canonicalReplace(Prepared p) {
        String t = p.replaceText();
        Rule.Bind bind = p.rule().match().bind();
        if (bind == null) {
            return t;
        }
        Map<String, String> names = new LinkedHashMap<>();
        if (bind.recv() != null) {
            names.put(bind.recv(), "#porter-bind:recv");
        }
        if (bind.args() != null) {
            for (int i = 0; i < bind.args().size(); i++) {
                names.putIfAbsent(bind.args().get(i), "#porter-bind:arg" + i);
            }
        }
        List<String> keys = new ArrayList<>(names.keySet());
        keys.sort((a, b) -> b.length() - a.length()); // $a10 before $a1
        for (String k : keys) {
            t = t.replace(k, names.get(k));
        }
        return t;
    }

    static String relocate(String s, String shimPackage) {
        return shimPackage == null || SHIM_PACKAGE.equals(shimPackage) ? s : s.replace(SHIM_PACKAGE + ".", shimPackage + ".");
    }

    public List<Prepared> all() {
        return all;
    }

    /** Rules this engine cannot apply (a pattern that does not parse, a missing edit...): reported, never applied. */
    public List<Prepared> unsupported() {
        return unsupported;
    }

    /** Why a rule is in {@link #unsupported()}. */
    public String problem(String id) {
        return problems.get(id);
    }

    /** Expression patterns (RFC 006), by priority. */
    public List<Prepared> expressionPatterns() {
        return expressionPatterns;
    }

    /** Statement-sequence patterns (RFC 006), by priority. */
    public List<Prepared> statementPatterns() {
        return statementPatterns;
    }

    /** Declaration rules (RFC 006), by priority. */
    public List<Prepared> declarations() {
        return declarations;
    }

    public List<Prepared> calls(Set<String> names) {
        return lookup(calls, names);
    }

    public List<Prepared> fields(Set<String> names) {
        return lookup(fields, names);
    }

    public List<Prepared> overrides(Set<String> names) {
        return lookup(overrides, names);
    }

    public List<Prepared> news(String owner) {
        return news.getOrDefault(owner, List.of());
    }

    public List<Prepared> types(String type) {
        return types.getOrDefault(type, List.of());
    }

    private static List<Prepared> lookup(Map<String, List<Prepared>> m, Set<String> names) {
        if (names.size() == 1) {
            return m.getOrDefault(names.iterator().next(), List.of());
        }
        List<Prepared> out = new ArrayList<>();
        for (String n : names) {
            out.addAll(m.getOrDefault(n, List.of()));
        }
        out.sort(ORDER);
        return out;
    }
}
