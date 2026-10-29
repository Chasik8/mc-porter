package dev.porter.loop.rules;

import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.ErrorClass;
import dev.porter.loop.javac.SourceFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in rule engine of the loop: looks rules up by the error's symbol and applies them at text level to the
 * error site. It reads the rewriter's rule YAML files (frozen rule contract) and supports the shapes that can be
 * applied without a type model:
 * <ul>
 *   <li>{@code type} rules for {@code missing_class} (imports, qualified names and simple-name tokens of the file);</li>
 *   <li>{@code call} / {@code field} rules for {@code missing_method} / {@code signature_mismatch}, when the owner
 *       javac reports is the rule's owner (or what a type rule renamed it to) and the argument count matches;</li>
 *   <li>{@code new} rules for constructor errors; {@code override} rules for {@code no_override}.</li>
 * </ul>
 * Preconditions {@code shim:*}, {@code pure:$x}, {@code literal:$x}, {@code statement}, {@code not-statement}, {@code not-super} are
 * evaluated; any other precondition makes the rule not fire. {@code pattern} rules are never applied here.
 * Highest priority wins; equal priorities with different replacements are {@code ambiguous}.
 */
public final class TextRuleEngine implements RuleEngine {
    private static final Pattern MEMBER_SEL = Pattern.compile("^([\\w$.]+)#([\\w$<>]+)(?:\\((.*)\\))?$");
    private static final Pattern NEW_SEL = Pattern.compile("^([\\w$.]+)\\((.*)\\)$");
    private static final Pattern OVERRIDE_REPL = Pattern.compile(
            "^(?:(public|protected|private)\\s+)?(?:([\\w$.\\[\\]<>]+)\\s+)?([\\w$]+)$");
    private static final Pattern PURE = Pattern.compile("^(this|[\\w$]+(\\.[\\w$]+)*|\\d[\\w.]*|\"[^\"]*\"|'[^']*'|true|false|null)$");
    private static final Pattern LITERAL = Pattern.compile("^(-?\\d[\\w.]*|\"[^\"]*\"|'[^']*'|true|false|null)$");

    /** A parsed member selector. params == null when the selector has no parameter list. */
    private record Sel(Rule rule, String kind, String owner, String name, List<String> params) {
    }

    private final List<Rule> rules;
    private final boolean shim;
    private final Map<String, List<Rule>> typeRules = new TreeMap<>();
    private final Map<String, List<Rule>> typeRulesBySimple = new TreeMap<>();
    private final Map<String, String> typeRenames = new TreeMap<>();
    private final Map<String, List<Sel>> members = new TreeMap<>();

    /**
     * @param rules all rules (already filtered by version pair)
     * @param shim  whether the porter shim is available ({@code shim:*} preconditions)
     */
    public TextRuleEngine(List<Rule> rules, boolean shim) {
        this.rules = List.copyOf(rules);
        this.shim = shim;
        for (Rule r : rules) {
            Rule.Match m = r.match();
            if (m.type() != null) {
                // a type rule conditioned on where the type is written (RFC 006 position:*) is not a rename of the
                // type: it must neither shadow the unconditional rename nor make it ambiguous
                boolean positional = r.preconditions() != null && r.preconditions().stream().anyMatch(c -> c.startsWith("position:"));
                if (!positional && !m.type().equals(r.replace())) {
                    typeRules.computeIfAbsent(m.type(), k -> new ArrayList<>()).add(r);
                    typeRulesBySimple.computeIfAbsent(simple(m.type()), k -> new ArrayList<>()).add(r);
                    typeRenames.put(m.type(), r.replace().trim());
                }
            } else if (m.call() != null || m.field() != null || m.override() != null) {
                String sel = m.call() != null ? m.call() : m.field() != null ? m.field() : m.override();
                String kind = m.call() != null ? "call" : m.field() != null ? "field" : "override";
                Matcher mm = MEMBER_SEL.matcher(sel);
                if (mm.matches()) {
                    List<String> params = mm.group(3) == null ? null : CodeText.splitArgs(mm.group(3));
                    members.computeIfAbsent(mm.group(2), k -> new ArrayList<>()).add(new Sel(r, kind, mm.group(1), mm.group(2), params));
                }
            } else if (m.newCall() != null) {
                Matcher nm = NEW_SEL.matcher(m.newCall());
                if (nm.matches()) {
                    members.computeIfAbsent("<init>", k -> new ArrayList<>())
                            .add(new Sel(r, "new", nm.group(1), "<init>", CodeText.splitArgs(nm.group(2))));
                }
            }
        }
    }

    /**
     * Loads every {@code *.rule.yaml} from the given files/directories (sorted, validated by {@code RuleIo}) and keeps
     * the rules for {@code from -> to} (null = any).
     */
    public static TextRuleEngine load(List<Path> paths, String from, String to, boolean shim) {
        List<Rule> all = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                all.addAll(RuleIo.readAll(p));
            } else if (Files.isRegularFile(p)) {
                all.addAll(RuleIo.read(p));
            }
        }
        List<Rule> kept = all.stream()
                .filter(r -> (from == null || from.equals(r.from())) && (to == null || to.equals(r.to())))
                .filter(r -> !isProposed(r))
                .toList();
        return new TextRuleEngine(kept, shim);
    }

    private static boolean isProposed(Rule r) {
        return r.id().contains("-draft-");
    }

    @Override
    public int ruleCount() {
        return rules.size();
    }

    @Override
    public FixAttempt fix(ClassifiedError e, SourceText file) {
        return switch (e.errorClass()) {
            case MISSING_CLASS -> fixType(e, file);
            case MISSING_METHOD -> fixMember(e, file, "field".equals(e.symbolKind()) ? "field" : "call");
            case SIGNATURE_MISMATCH -> "constructor".equals(e.symbolKind()) ? fixMember(e, file, "new") : fixMember(e, file, "call");
            case NO_OVERRIDE -> "compiler.err.method.does.not.override.superclass".equals(e.code())
                    ? fixOverride(e, file) : FixAttempt.none("no-rule");
            default -> FixAttempt.none("no-rule");
        };
    }

    // ---------------------------------------------------------------- types

    private FixAttempt fixType(ClassifiedError e, SourceText file) {
        String sym = e.symbol();
        if (sym == null) {
            return FixAttempt.none("not-applicable:no-symbol");
        }
        List<Rule> cands = new ArrayList<>();
        String bin = sym;
        for (Map.Entry<String, List<Rule>> t : typeRules.entrySet()) {
            if (t.getKey().equals(bin) || sourceForm(t.getKey()).equals(sourceForm(bin))) {
                cands.addAll(t.getValue());
            }
        }
        if (cands.isEmpty() && !sym.contains(".")) {
            cands.addAll(typeRulesBySimple.getOrDefault(sym, List.of()));
            if (cands.stream().map(r -> r.match().type()).distinct().count() > 1) {
                return FixAttempt.none("ambiguous:simple-name " + sym);
            }
        }
        if (cands.isEmpty()) {
            return FixAttempt.none("no-rule");
        }
        Rule r = pick(cands);
        if (r == null) {
            return FixAttempt.none("ambiguous");
        }
        String fromSrc = sourceForm(r.match().type());
        String toSrc = sourceForm(r.replace().trim());
        String fromSimple = simpleSource(fromSrc);
        String toSimple = simpleSource(toSrc);
        String text = file.text();
        String out = replaceType(text, fromSrc, toSrc, fromSimple, toSimple);
        boolean simpleChanged = !fromSimple.equals(toSimple) && !out.equals(replaceQualified(text, fromSrc, toSrc));
        List<String> imports = new ArrayList<>();
        if (r.imports() != null) {
            imports.addAll(r.imports());
        }
        if (simpleChanged) {
            imports.add(topLevel(toSrc));
        }
        out = addImports(out, imports);
        if (out.equals(text)) {
            return FixAttempt.none("not-applicable:no-occurrence");
        }
        String before = new SourceFacts(text).line(e.line());
        return FixAttempt.applied(r.id(), out, before, replaceType(before, fromSrc, toSrc, fromSimple, toSimple));
    }

    private static String replaceType(String s, String fromSrc, String toSrc, String fromSimple, String toSimple) {
        String out = replaceQualified(s, fromSrc, toSrc);
        if (!fromSimple.equals(toSimple)) {
            Pattern p = Pattern.compile("(?<![\\w$.])" + Pattern.quote(fromSimple) + "(?![\\w$])");
            out = CodeText.replaceInCode(out, p, m -> toSimple);
        }
        return out;
    }

    private static String replaceQualified(String s, String fromSrc, String toSrc) {
        Pattern p = Pattern.compile("(?<![\\w$.])" + Pattern.quote(fromSrc) + "(?![\\w$])");
        return CodeText.replaceInCode(s, p, m -> toSrc);
    }

    // ---------------------------------------------------------------- members

    private FixAttempt fixMember(ClassifiedError e, SourceText file, String kind) {
        String name = e.member();
        if (name == null) {
            return FixAttempt.none("not-applicable:no-symbol");
        }
        List<Sel> sels = members.getOrDefault(name, List.of()).stream().filter(s -> s.kind().equals(kind)).toList();
        if (sels.isEmpty()) {
            return FixAttempt.none("no-rule");
        }
        if (e.owner() == null) {
            return FixAttempt.none("not-applicable:owner-unknown");
        }
        SourceFacts facts = new SourceFacts(file.text());
        Set<String> owners = ownerNames(e.owner(), facts);
        List<Sel> fitting = new ArrayList<>();
        for (Sel s : sels) {
            if (!ownerMatches(s.owner(), owners)) {
                continue;
            }
            if (s.params() != null && e.argTypes() != null && s.params().size() != e.argTypes().size()) {
                continue;
            }
            fitting.add(s);
        }
        if (fitting.isEmpty()) {
            return FixAttempt.none("not-applicable:owner-or-arity " + e.owner());
        }
        fitting.sort(Comparator.comparingInt((Sel s) -> -s.rule().priorityOrDefault()).thenComparing(s -> s.rule().id()));
        String lastReason = "not-applicable";
        for (int i = 0; i < fitting.size(); i++) {
            Sel s = fitting.get(i);
            if (i + 1 < fitting.size()) {
                Sel n = fitting.get(i + 1);
                if (n.rule().priorityOrDefault() == s.rule().priorityOrDefault() && !n.rule().replace().equals(s.rule().replace())) {
                    return FixAttempt.none("ambiguous:" + s.rule().id() + "," + n.rule().id());
                }
            }
            FixAttempt a = applyAtSite(s, e, file.text());
            if (a.isApplied()) {
                return a;
            }
            lastReason = a.reason();
        }
        return FixAttempt.none(lastReason);
    }

    private FixAttempt applyAtSite(Sel s, ClassifiedError e, String text) {
        int[] lineSpan = lineSpan(text, e.line());
        if (lineSpan == null) {
            return FixAttempt.none("not-applicable:line");
        }
        String line = text.substring(lineSpan[0], lineSpan[1]);
        Pattern occ = switch (s.kind()) {
            case "new" -> Pattern.compile("(?<![\\w$])new\\s+" + Pattern.quote(simpleSource(sourceForm(s.owner())))
                    + "\\s*(<[^>]*>)?\\s*\\(");
            case "field" -> Pattern.compile("(?<![\\w$])" + Pattern.quote(s.name()) + "(?![\\w$])(?!\\s*\\()");
            default -> Pattern.compile("(?<![\\w$])" + Pattern.quote(s.name()) + "\\s*\\(");
        };
        List<Integer> hits = CodeText.findInCode(line, occ);
        if (hits.isEmpty()) {
            return FixAttempt.none("not-applicable:site-not-found");
        }
        int hit;
        if (hits.size() == 1) {
            hit = hits.get(0);
        } else if (e.diagnostic().column() != null) {
            int col = e.diagnostic().column() - 1;
            hit = hits.stream().filter(h -> h >= col - 1).findFirst().orElse(-1);
            if (hit < 0) {
                return FixAttempt.none("ambiguous:site");
            }
        } else {
            return FixAttempt.none("ambiguous:site");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Rule.Bind bind = s.rule().match().bind();
        int spanStart = hit;
        int spanEnd;
        String recv = null;
        if (!"new".equals(s.kind())) {
            int k = hit - 1;
            while (k >= 0 && Character.isWhitespace(line.charAt(k))) {
                k--;
            }
            if (k >= 0 && line.charAt(k) == '.') {
                int rs = CodeText.receiverStart(line, k);
                if (rs < 0) {
                    return FixAttempt.none("not-applicable:complex-receiver");
                }
                recv = line.substring(rs, k).strip();
                spanStart = rs;
            }
        }
        if ("field".equals(s.kind())) {
            spanEnd = hit + s.name().length();
        } else {
            int open = line.indexOf('(', hit);
            int close = CodeText.matching(line, open);
            if (close < 0) {
                return FixAttempt.none("not-applicable:multi-line-call");
            }
            List<String> args = CodeText.splitArgs(line.substring(open + 1, close));
            if ("new".equals(s.kind()) && line.substring(close + 1).strip().startsWith("{")) {
                return FixAttempt.none("not-applicable:anonymous-class");
            }
            List<String> names = bind == null || bind.args() == null ? List.of() : bind.args();
            if (!names.isEmpty() && names.size() != args.size()) {
                return FixAttempt.none("not-applicable:arity");
            }
            for (int i = 0; i < names.size(); i++) {
                values.put(names.get(i), args.get(i));
            }
            spanEnd = close + 1;
        }
        String replace = s.rule().replace();
        String recvName = bind == null ? null : bind.recv();
        if (recvName != null) {
            if (recv == null) {
                if (replace.startsWith(recvName + ".")) {
                    replace = replace.substring(recvName.length() + 1); // keep the receiver implicit
                } else {
                    values.put(recvName, "this");
                }
            } else {
                values.put(recvName, recv);
            }
        } else if (recv != null) {
            // The rule does not talk about the receiver: keep it, replace only the member part. Except for a static
            // member written as Type.NAME whose replacement is a whole expression (Blocks.fire, Item.of(Blocks.x)):
            // replacing only NAME would give Blocks.Blocks.fire.
            boolean typeQualifier = s.owner() != null && recv.equals(simpleSource(s.owner())) && replace.matches("[\\w$.]*[.(].*");
            if (!typeQualifier) {
                spanStart = hit;
            }
        }
        if (bind != null && bind.result() != null) {
            return FixAttempt.none("not-applicable:result-binding");
        }
        // Placeholders used more than once must be pure.
        for (Map.Entry<String, String> v : values.entrySet()) {
            int uses = countPlaceholder(replace, v.getKey());
            if (uses > 1 && !PURE.matcher(v.getValue()).matches()) {
                return FixAttempt.none("not-applicable:impure-duplicate " + v.getKey());
            }
        }
        String pre = checkPreconditions(s.rule(), values, line, spanStart, spanEnd);
        if (pre != null) {
            return FixAttempt.none(pre);
        }
        String rendered = substitute(replace, values);
        if (rendered.contains("$")) {
            Matcher left = Pattern.compile("\\$[A-Za-z_]\\w*").matcher(rendered);
            if (left.find() && !values.containsKey(left.group())) {
                return FixAttempt.none("not-applicable:unbound " + left.group());
            }
        }
        String newLine = line.substring(0, spanStart) + rendered + line.substring(spanEnd);
        String out = text.substring(0, lineSpan[0]) + newLine + text.substring(lineSpan[1]);
        if (s.rule().imports() != null) {
            out = addImports(out, s.rule().imports());
        }
        if (out.equals(text)) {
            return FixAttempt.none("not-applicable:no-change");
        }
        return FixAttempt.applied(s.rule().id(), out, line, newLine);
    }

    private String checkPreconditions(Rule r, Map<String, String> values, String line, int start, int end) {
        if (r.preconditions() == null) {
            return null;
        }
        for (String c : r.preconditions()) {
            boolean ok;
            if (c.startsWith("shim:")) {
                ok = shim;
            } else if (c.startsWith("pure:") || c.startsWith("literal:")) {
                String v = values.get(c.substring(c.indexOf(':') + 1));
                if (v == null) {
                    return "unknown-precondition:" + c;
                }
                ok = (c.startsWith("pure:") ? PURE : LITERAL).matcher(v).matches();
            } else if (c.equals("statement") || c.equals("not-statement")) {
                boolean stmt = line.substring(0, start).isBlank() && line.substring(end).strip().startsWith(";");
                ok = c.equals("statement") == stmt;
            } else if (c.equals("not-super")) {
                ok = !line.substring(start, end).strip().startsWith("super.");
            } else {
                return "unknown-precondition:" + c;
            }
            if (!ok) {
                return "precondition:" + r.id() + " " + c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- overrides

    private FixAttempt fixOverride(ClassifiedError e, SourceText file) {
        String name = e.member();
        if (name == null) {
            return FixAttempt.none("not-applicable:no-symbol");
        }
        List<Sel> sels = members.getOrDefault(name, List.of()).stream().filter(s -> s.kind().equals("override")).toList();
        if (sels.isEmpty()) {
            return FixAttempt.none("no-rule");
        }
        SourceFacts facts = new SourceFacts(file.text());
        Set<String> owners = new HashSet<>();
        for (String st : facts.supertypes()) {
            owners.add(facts.qualify(st));
            owners.add(simpleSource(sourceForm(st)));
        }
        List<Sel> fitting = sels.stream().filter(s -> ownerMatches(s.owner(), owners))
                .filter(s -> s.params() == null || e.argTypes() == null || s.params().size() == e.argTypes().size())
                .sorted(Comparator.comparingInt((Sel s) -> -s.rule().priorityOrDefault()).thenComparing(s -> s.rule().id()))
                .toList();
        if (fitting.isEmpty()) {
            return FixAttempt.none("not-applicable:supertype");
        }
        if (fitting.size() > 1 && fitting.get(0).rule().priorityOrDefault() == fitting.get(1).rule().priorityOrDefault()
                && !fitting.get(0).rule().replace().equals(fitting.get(1).rule().replace())) {
            return FixAttempt.none("ambiguous");
        }
        Sel s = fitting.get(0);
        Matcher rm = OVERRIDE_REPL.matcher(s.rule().replace().trim());
        if (!rm.matches()) {
            return FixAttempt.none("not-applicable:override-replace");
        }
        String text = file.text();
        for (int l = e.line(); l <= Math.min(e.line() + 3, facts.lineCount()); l++) {
            int[] span = lineSpan(text, l);
            String line = text.substring(span[0], span[1]);
            Pattern decl = Pattern.compile("(?:\\b(public|protected|private)\\s+)?((?:(?:static|final|synchronized)\\s+)*)"
                    + "([\\w$.\\[\\]<>]+)\\s+" + Pattern.quote(name) + "\\s*\\(");
            Matcher dm = decl.matcher(line);
            if (!dm.find()) {
                continue;
            }
            String vis = rm.group(1) != null ? rm.group(1) : dm.group(1);
            String ret = rm.group(2) != null ? rm.group(2) : dm.group(3);
            String repl = (vis == null ? "" : vis + " ") + dm.group(2) + ret + " " + rm.group(3) + "(";
            String newLine = line.substring(0, dm.start()) + repl + line.substring(dm.end());
            String out = text.substring(0, span[0]) + newLine + text.substring(span[1]);
            if (s.rule().imports() != null) {
                out = addImports(out, s.rule().imports());
            }
            return FixAttempt.applied(s.rule().id(), out, line, newLine);
        }
        return FixAttempt.none("not-applicable:declaration-not-found");
    }

    // ---------------------------------------------------------------- helpers

    private Rule pick(List<Rule> cands) {
        List<Rule> sorted = cands.stream()
                .sorted(Comparator.comparingInt((Rule r) -> -r.priorityOrDefault()).thenComparing(Rule::id)).toList();
        if (sorted.size() > 1 && sorted.get(0).priorityOrDefault() == sorted.get(1).priorityOrDefault()
                && !sorted.get(0).replace().equals(sorted.get(1).replace())) {
            return null;
        }
        return sorted.get(0);
    }

    /** Names under which the error's owner may appear: itself, plus the file's supertypes when it is the file's class. */
    private Set<String> ownerNames(String owner, SourceFacts facts) {
        Set<String> out = new HashSet<>();
        out.add(owner);
        out.add(simpleSource(sourceForm(owner)));
        String simple = simpleSource(sourceForm(owner));
        if (Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(facts.text()).find()) {
            for (String st : facts.supertypes()) {
                out.add(facts.qualify(st));
                out.add(simpleSource(sourceForm(st)));
            }
        }
        return out;
    }

    /** Rule owner (or what a type rule renames it to) is one of the given names (qualified or simple). */
    private boolean ownerMatches(String ruleOwner, Set<String> names) {
        List<String> forms = new ArrayList<>();
        forms.add(ruleOwner);
        String renamed = typeRenames.get(ruleOwner);
        if (renamed != null) {
            forms.add(renamed);
        }
        for (String f : forms) {
            if (names.contains(f) || names.contains(sourceForm(f))) {
                return true;
            }
            String simple = simpleSource(sourceForm(f));
            if (names.contains(simple)) {
                // a simple name only counts when no qualified name contradicts it
                boolean contradicted = names.stream().anyMatch(n -> n.contains(".") && Character.isLowerCase(n.charAt(0))
                        && simpleSource(sourceForm(n)).equals(simple) && !sourceForm(n).equals(sourceForm(f)));
                if (!contradicted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countPlaceholder(String s, String ph) {
        Matcher m = Pattern.compile(Pattern.quote(ph) + "(?![\\w])").matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String substitute(String template, Map<String, String> values) {
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        String out = template;
        for (String k : keys) {
            out = out.replaceAll(Pattern.quote(k) + "(?![\\w])", Matcher.quoteReplacement(values.get(k)));
        }
        return out;
    }

    /** [start, end) of 1-based line (without the line terminator), or null. */
    static int[] lineSpan(String text, int line) {
        int start = 0;
        for (int l = 1; l < line; l++) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                return null;
            }
            start = nl + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        return new int[] {start, end};
    }

    /** Adds single-type imports that are not present yet, after the last import (or the package line). */
    static String addImports(String text, List<String> imports) {
        String out = text;
        String pkg = new SourceFacts(text).packageName();
        for (String imp : imports) {
            String top = imp;
            if (Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(top) + "\\s*;").matcher(out).find()) {
                continue;
            }
            int dot = top.lastIndexOf('.');
            if (dot > 0 && top.substring(0, dot).equals(pkg) && !top.endsWith(".*")) {
                continue;
            }
            Matcher last = Pattern.compile("(?m)^\\s*import\\s+[^;]+;[^\\n]*\\n").matcher(out);
            int at = -1;
            while (last.find()) {
                at = last.end();
            }
            if (at < 0) {
                Matcher p = Pattern.compile("(?m)^\\s*package\\s+[^;]+;[^\\n]*\\n").matcher(out);
                at = p.find() ? p.end() : 0;
                out = out.substring(0, at) + (at > 0 ? "\n" : "") + "import " + top + ";\n" + out.substring(at);
            } else {
                out = out.substring(0, at) + "import " + top + ";\n" + out.substring(at);
            }
        }
        return out;
    }

    /** Binary name -> source name (a.b.Outer$Inner -> a.b.Outer.Inner). */
    static String sourceForm(String binary) {
        return binary.replace('$', '.');
    }

    /** Package-free part of a source name: a.b.Outer.Inner -> Outer.Inner; Foo -> Foo. */
    static String simpleSource(String source) {
        String[] parts = source.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                return String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
            }
        }
        return parts[parts.length - 1];
    }

    private static String simple(String binary) {
        String s = sourceForm(binary);
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** a.b.Outer.Inner -> a.b.Outer. */
    private static String topLevel(String source) {
        String simple = simpleSource(source);
        int dot = simple.indexOf('.');
        return dot < 0 ? source : source.substring(0, source.length() - simple.length() + dot);
    }

    /** All rules this engine knows (for task context). */
    public List<Rule> rules() {
        return rules;
    }

    /** Error classes this engine can act on. */
    public static boolean handles(ErrorClass c) {
        return c == ErrorClass.MISSING_CLASS || c == ErrorClass.MISSING_METHOD || c == ErrorClass.SIGNATURE_MISMATCH
                || c == ErrorClass.NO_OVERRIDE;
    }
}
