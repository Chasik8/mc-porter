package dev.porter.loop.rules;

import dev.porter.core.rule.Rule;
import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.SourceFacts;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.TypeFacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Rule engine backed by the rewriter's Spoon engine ({@link Rewriter}, module {@code :rewrite}), with
 * {@link TextRuleEngine} as fallback.
 *
 * <p>Per file and text, the rewriter runs once over the whole file (all rules, type-aware); its result is used as the
 * fix for an error when it changed the error's line. The {@code TODO(porter:...)} line comments the rewriter writes
 * for sites it did not rewrite are not put into the project (the loop's task files carry that information), but the
 * TODO reason at the error line is reported. When the rewriter does not touch the line (typical for code that
 * already uses target-version types, which source-version rules do not match), the text engine is asked.
 */
public final class RewriterRuleEngine implements RuleEngine {
    private final Rewriter rewriter;
    private final RuleEngine fallback;
    private final int ruleCount;
    private final Map<String, Rewriter.FileResult> cache = new HashMap<>();

    /**
     * @param rules        rules for the version pair
     * @param fromPlatform e.g. forge-1.19.2 (selects the bundled type-facts table)
     * @param shim         whether rules needing the porter shim may fire
     * @param fallback     engine asked when the rewriter does not fix the error line
     */
    public RewriterRuleEngine(List<Rule> rules, String fromPlatform, boolean shim, RuleEngine fallback) {
        Rewriter.Options d = Rewriter.Options.defaults();
        Rewriter.Options opts = new Rewriter.Options(d.platformPrefixes(), shim, d.shimPackage(), false, d.complianceLevel(), d.classpath());
        this.rewriter = new Rewriter(rules, NameTable.empty(), TypeFacts.bundledPlus(fromPlatform, List.of()), Map.of(), opts);
        this.fallback = fallback;
        this.ruleCount = rules.size();
    }

    @Override
    public int ruleCount() {
        return ruleCount;
    }

    @Override
    public FixAttempt fix(ClassifiedError e, SourceText file) {
        if (!TextRuleEngine.handles(e.errorClass())) {
            return fallback.fix(e, file);
        }
        Rewriter.FileResult r = rewrite(file);
        String todo = null;
        if (r != null) {
            for (Rewriter.Todo t : r.todos()) {
                if (t.line() == e.line()) {
                    todo = t.reason() + (t.detail() == null ? "" : " " + t.detail());
                    break;
                }
            }
            if (r.changed()) {
                String out = withoutNewTodos(file.text(), r.text());
                SourceFacts before = new SourceFacts(file.text());
                SourceFacts after = new SourceFacts(out);
                int shift = after.lineCount() - before.lineCount();
                String b = before.line(e.line());
                String a = after.line(e.line() + shift);
                if (!out.equals(file.text()) && !a.equals(b) && !r.applied().isEmpty()) {
                    String ruleId = new TreeSet<>(r.applied().keySet()).first();
                    return FixAttempt.applied(ruleId, out, b, a);
                }
            }
        }
        FixAttempt f = fallback.fix(e, file);
        if (!f.isApplied() && todo != null) {
            return FixAttempt.none(f.reason() + " (rewriter: " + todo + ")");
        }
        return f;
    }

    private Rewriter.FileResult rewrite(SourceText file) {
        String key = file.relPath() + "\u0000" + file.text();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Rewriter.FileResult r;
        try {
            List<Rewriter.FileResult> files = rewriter.rewrite(List.of(new Rewriter.SourceFile(file.relPath(), file.text(), true))).files();
            r = files.isEmpty() ? null : files.get(0);
        } catch (RuntimeException ex) {
            r = null; // the text engine still gets its chance
        }
        if (cache.size() > 256) {
            cache.clear();
        }
        cache.put(key, r);
        return r;
    }

    /** Drops the TODO(porter:...) comment lines the rewriter added (lines already in the original are kept). */
    static String withoutNewTodos(String original, String rewritten) {
        Map<String, Integer> have = new HashMap<>();
        for (String l : original.split("\n", -1)) {
            have.merge(l, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (String l : rewritten.split("\n", -1)) {
            if (l.strip().startsWith("// TODO(porter:")) {
                Integer n = have.get(l);
                if (n == null || n == 0) {
                    continue;
                }
                have.put(l, n - 1);
            }
            out.add(l);
        }
        return String.join("\n", out);
    }
}
