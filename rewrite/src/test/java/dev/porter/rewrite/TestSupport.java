package dev.porter.rewrite;

import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.TypeFacts;
import dev.porter.rewrite.graph.SymbolGraph;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class TestSupport {
    static final String FROM = "forge-1.19.2";
    static final String TO = "forge-1.7.10";

    static Path repo() {
        return Path.of(System.getProperty("porter.repoRoot", "."));
    }

    static Path resource(String name) {
        try {
            return Path.of(TestSupport.class.getResource("/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Rule> rules;
    private static List<Rule> allRules;

    /** The core rule set (rules/composite): what a plain `porter rewrite` uses. */
    static synchronized List<Rule> publicRules() {
        if (rules == null) {
            rules = RuleIo.readAll(repo().resolve("rules/composite")).stream()
                    .filter(r -> r.from().equals(FROM) && r.to().equals(TO)).toList();
        }
        return rules;
    }

    /** Every public rule including the opt-in packs (rules/packs/...): all of them must pass their own tests. */
    static synchronized List<Rule> allPublicRules() {
        if (allRules == null) {
            allRules = RuleIo.readAll(repo().resolve("rules")).stream()
                    .filter(r -> r.from().equals(FROM) && r.to().equals(TO)).toList();
        }
        return allRules;
    }

    static TypeFacts facts() {
        return TypeFacts.bundledPlus(FROM, List.of());
    }

    static NameTable srgNames() {
        return NameTable.of(SymbolGraph.load(List.of(resource("graphs/srg-names-1.19.2.graph.json"))), "1.19.2");
    }

    static Rewriter.FileResult rewrite(List<Rule> rules, NameTable names, String code) {
        Rewriter rw = new Rewriter(rules, names, facts(), Map.of(), Rewriter.Options.defaults());
        return rw.rewrite(List.of(new Rewriter.SourceFile("p/A.java", code, true))).files().get(0);
    }

    private TestSupport() {
    }
}
