package dev.porter.rewrite.engine;

import dev.porter.core.rule.Rule;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the {@code tests} of rules: each {@code before} snippet is wrapped into a synthetic compilation unit, rewritten
 * with that single rule (platform TODOs off, rule TODOs on) and compared with {@code after} (whitespace-normalised).
 *
 * <p>Snippet placement: {@code before} starting with {@code import} goes to the import section; for {@code override}
 * rules it is a class member of a class extending the rule's owner; otherwise it is statements in a method body.
 * {@code context} is split on ';': {@code import a.b.C} lines become imports, {@code extends a.b.C} sets the test
 * class superclass, anything else becomes fields of the test class (a context containing '{' is used verbatim as
 * class body). The rule's owner type and qualified parameter types are imported automatically.
 *
 * <p>Declaration rules (RFC 006): {@code before} is the whole declaration of the rule's top-level owner type, placed in
 * the owner's package; each test is rewritten on its own (the tests declare the same type).
 */
public final class RuleTestRunner {
    public record Failure(String ruleId, int test, String name, String expected, String actual) {
        public String message() {
            return ruleId + " test #" + (test + 1) + (name == null ? "" : " (" + name + ")") + "\n  expected: " + expected
                    + "\n  actual:   " + actual;
        }
    }

    static final String BEGIN = "//<<porter-test";
    static final String END = "//porter-test>>";

    private final NameTable names;
    private final TypeFacts facts;
    private final Rewriter.Options options;

    public RuleTestRunner(NameTable names, TypeFacts facts, Rewriter.Options options) {
        this.names = names;
        this.facts = facts;
        this.options = options.withPlatformTodos(false);
    }

    public List<Failure> run(List<Rule> rules) {
        List<Failure> failures = new ArrayList<>();
        for (Rule r : rules) {
            failures.addAll(run(r));
        }
        return failures;
    }

    public List<Failure> run(Rule rule) {
        if (Selector.of(rule.match()).kind() == Selector.Kind.DECLARATION && rule.tests().size() > 1) {
            List<Failure> failures = new ArrayList<>();
            for (int i = 0; i < rule.tests().size(); i++) {
                List<Rule.Test> one = new ArrayList<>();
                for (int k = 0; k < rule.tests().size(); k++) {
                    one.add(k == i ? rule.tests().get(k) : null);
                }
                failures.addAll(run(rule, one));
            }
            return failures;
        }
        return run(rule, rule.tests());
    }

    /** Runs the non-null entries of {@code tests} (indices are kept for the messages). */
    private List<Failure> run(Rule rule, List<Rule.Test> tests) {
        List<Failure> failures = new ArrayList<>();
        List<Rewriter.SourceFile> files = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            if (tests.get(i) != null) {
                files.add(new Rewriter.SourceFile("porter/ruletest/T" + i + ".java", source(rule, tests.get(i), i), true));
            }
        }
        Rewriter rw = new Rewriter(List.of(rule), names, facts, Map.of(), options);
        Rewriter.Result res;
        try {
            res = rw.rewrite(files);
        } catch (RuntimeException e) {
            failures.add(new Failure(rule.id(), 0, null, "<rewrite>", "exception: " + e));
            return failures;
        }
        for (Rewriter.FileResult f : res.files()) {
            int i = Integer.parseInt(f.path().replaceAll(".*/T(\\d+)\\.java", "$1"));
            Rule.Test t = rule.tests().get(i);
            String actual = normalize(region(f.text()));
            String expected = normalize(t.after());
            if (!actual.equals(expected)) {
                failures.add(new Failure(rule.id(), i, t.name(), expected, actual));
            }
        }
        return failures;
    }

    static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static String region(String text) {
        int b = text.indexOf(BEGIN);
        int e = text.indexOf(END);
        if (b < 0 || e < b) {
            return "<markers lost>";
        }
        return text.substring(b + BEGIN.length(), e);
    }

    static String source(Rule rule, Rule.Test test, int index) {
        Selector sel = Selector.of(rule.match());
        if (sel.kind() == Selector.Kind.DECLARATION) {
            return declarationSource(sel, test);
        }
        Set<String> imports = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder();
        String superclass = null;
        String ctx = test.context() == null ? "" : test.context();
        if (ctx.contains("{")) {
            List<String> rest = new ArrayList<>();
            for (String line : ctx.split("\n")) {
                if (line.trim().startsWith("import ")) {
                    imports.add(line.trim().replaceAll(";$", "").substring(7).trim());
                } else {
                    rest.add(line);
                }
            }
            body.append(String.join("\n", rest)).append('\n');
        } else {
            for (String part : ctx.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.startsWith("import ")) {
                    imports.add(p.substring(7).trim());
                } else if (p.startsWith("extends ")) {
                    superclass = p.substring(8).trim();
                } else {
                    body.append("    ").append(p).append(";\n");
                }
            }
        }
        boolean importMode = test.before().trim().startsWith("import ");
        boolean memberMode = sel.kind() == Selector.Kind.OVERRIDE;
        if (!importMode) {
            if (sel.owner() != null) {
                addImport(imports, sel.owner());
            }
            if (sel.params() != null) {
                for (String p : sel.params()) {
                    String base = p.replace("[]", "");
                    if (base.contains(".")) {
                        addImport(imports, base);
                    }
                }
            }
        }
        if (memberMode && superclass == null) {
            superclass = Selector.simple(sel.owner());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("package porter.ruletest;\n\n");
        if (importMode) {
            sb.append(BEGIN).append('\n').append(test.before()).append('\n').append(END).append('\n');
        }
        for (String i : imports) {
            sb.append("import ").append(i).append(";\n");
        }
        sb.append("\nclass T").append(index).append(superclass == null ? "" : " extends " + superclass).append(" {\n");
        sb.append(body);
        if (memberMode) {
            sb.append(BEGIN).append('\n').append(test.before()).append('\n').append(END).append('\n');
        } else {
            sb.append("    void porterTest() throws Exception {\n");
            if (!importMode) {
                sb.append(BEGIN).append('\n').append(test.before()).append('\n').append(END).append('\n');
            }
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Declaration rules: {@code before} declares the owner's top-level type, in the owner's package. */
    static String declarationSource(Selector sel, Rule.Test test) {
        String top = sel.owner().contains("$") ? sel.owner().substring(0, sel.owner().indexOf('$')) : sel.owner();
        String pkg = top.contains(".") ? top.substring(0, top.lastIndexOf('.')) : "";
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        String ctx = test.context() == null ? "" : test.context();
        for (String part : ctx.split("[;\n]")) {
            String p = part.trim();
            if (p.startsWith("import ")) {
                sb.append(p).append(";\n");
            }
        }
        sb.append('\n').append(BEGIN).append('\n').append(test.before()).append('\n').append(END).append('\n');
        return sb.toString();
    }

    private static void addImport(Set<String> imports, String binary) {
        if (!binary.contains(".") || binary.startsWith("java.lang.") && binary.indexOf('.', 10) < 0) {
            return;
        }
        String simple = Selector.simple(binary);
        for (String i : imports) {
            if (Selector.simple(i).equals(simple)) {
                return;
            }
        }
        if (binary.contains("$")) {
            imports.add(binary.substring(0, binary.indexOf('$')));
        }
        imports.add(Selector.canonical(binary));
    }
}
