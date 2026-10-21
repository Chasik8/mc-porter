package dev.porter.loop;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.json.Json;
import dev.porter.loop.candidates.CandidateAggregator;
import dev.porter.loop.candidates.Normalizer;
import dev.porter.loop.graph.GraphSource;
import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.ErrorClass;
import dev.porter.loop.javac.ErrorClassifier;
import dev.porter.loop.javac.JavacDiagnostic;
import dev.porter.loop.javac.JavacOutputParser;
import dev.porter.loop.javac.SourceFacts;
import dev.porter.loop.rules.FixAttempt;
import dev.porter.loop.rules.RuleEngine;
import dev.porter.loop.run.CompileRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The compile / classify / fix loop.
 *
 * <p>Each iteration compiles the project, parses and classifies javac's errors, checks that the previous iteration's
 * fixes removed their errors, and asks the {@link RuleEngine} for a fix per remaining error. It stops when the project
 * compiles, when no rule applies any more ({@code stuck}), when the same error set comes back ({@code cycle}), or at
 * {@code maxIter} compiles. Loop protection: an error that is still there after a rule fixed it is escalated
 * ({@code same-error-after-fix}) and never retried. Every remaining error becomes an agent task file with context.
 * Verified rule fixes are logged as candidates ({@code author: rule:<id>}, audit); candidate logs are aggregated and
 * groups with count &gt;= 3 drafted as rules.
 */
public final class LoopEngine {
    /** Everything the loop needs. */
    public record Config(Path project, CompileRunner runner, RuleEngine rules, int maxIter, Path workDir, Path candidateLog,
            List<Path> candidateSources, Path proposedDir, Supplier<GraphSource> graph, String from, String to) {
    }

    /** A fix applied in one iteration, verified in the next. */
    public record AppliedFix(String fingerprint, String key, int keyCountBefore, String ruleId, String file, int line,
            String errorClass, String symbol, String before, String after, String error) {
    }

    private final Config cfg;
    private final Map<Path, SourceFacts> factsCache = new HashMap<>();

    public LoopEngine(Config cfg) {
        this.cfg = cfg;
    }

    /** Runs the loop; returns the iteration report (also written by the caller). */
    public Map<String, Object> run() throws Exception {
        Path tasksDir = cfg.workDir().resolve("tasks");
        deleteTree(tasksDir);
        Files.createDirectories(tasksDir);
        List<Map<String, Object>> iterations = new ArrayList<>();
        List<AppliedFix> pending = new ArrayList<>();
        Set<String> blocked = new HashSet<>();
        Map<String, String> escalated = new TreeMap<>();
        Map<String, String> lastReason = new HashMap<>();
        Set<String> seenSets = new HashSet<>();
        List<ClassifiedError> errors = List.of();
        List<ClassifiedError> initial = null;
        String status = "max-iter";
        int verifiedTotal = 0;
        int persistedTotal = 0;
        int appliedTotal = 0;
        Map<String, Integer> appliedByRule = new TreeMap<>();
        for (int iter = 1; iter <= cfg.maxIter(); iter++) {
            factsCache.clear();
            CompileRunner.Result res = cfg.runner().compile();
            List<JavacDiagnostic> diags = JavacOutputParser.parse(res.output());
            ErrorClassifier classifier = new ErrorClassifier(cfg.project(), p -> factsCache.computeIfAbsent(p, SourceFacts::read));
            errors = classifier.classifyAll(diags);
            if (initial == null) {
                initial = errors;
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("iteration", iter);
            it.put("exitCode", res.exitCode());
            it.put("errors", errors.size());
            it.put("byClass", byClass(errors));
            Set<String> current = new LinkedHashSet<>();
            Map<String, Integer> keyCounts = new HashMap<>();
            for (ClassifiedError e : errors) {
                current.add(e.fingerprint());
                keyCounts.merge(e.key(), 1, Integer::sum);
            }
            // Loop protection: did the previous iteration's fixes remove their errors?
            List<String> persisted = new ArrayList<>();
            int verified = 0;
            for (AppliedFix f : pending) {
                if (keyCounts.getOrDefault(f.key(), 0) >= f.keyCountBefore()) {
                    blocked.add(f.key());
                    escalated.put(f.key(), "same-error-after-fix:" + f.ruleId());
                    persisted.add(f.ruleId() + " @ " + f.file() + ":" + f.line());
                } else {
                    verified++;
                    logCandidate(f);
                }
            }
            verifiedTotal += verified;
            persistedTotal += persisted.size();
            it.put("fixesVerified", verified);
            it.put("fixesPersisted", persisted);
            pending = new ArrayList<>();
            if (errors.isEmpty()) {
                status = res.exitCode() == 0 ? "compiled" : "build-failed-unparsed";
                iterations.add(it);
                if (res.exitCode() != 0) {
                    Files.writeString(cfg.workDir().resolve("unparsed-build.log"), res.output(), StandardCharsets.UTF_8);
                }
                break;
            }
            String setKey = String.join("\n", new java.util.TreeSet<>(current));
            if (!seenSets.add(setKey)) {
                status = "cycle";
                iterations.add(it);
                break;
            }
            if (iter == cfg.maxIter()) {
                iterations.add(it);
                break; // last compile only verifies
            }
            // Fix what the rules can fix.
            Map<String, String> texts = new TreeMap<>();
            Set<String> lineShifted = new HashSet<>();
            Set<String> done = new HashSet<>();
            Map<String, Integer> notApplied = new TreeMap<>();
            List<Map<String, Object>> fixes = new ArrayList<>();
            int deferred = 0;
            Set<String> fixedTypeKeys = new HashSet<>();
            for (ClassifiedError e : errors) {
                String fp = e.fingerprint();
                if (!done.add(fp) || blocked.contains(e.key())) {
                    continue;
                }
                if (e.errorClass() == ErrorClass.MISSING_CLASS && fixedTypeKeys.contains(typeKey(e))) {
                    continue; // a type rule rewrites the whole file at once
                }
                if (lineShifted.contains(e.file())) {
                    deferred++;
                    continue;
                }
                Path abs = cfg.project().resolve(e.file());
                if (!Files.isRegularFile(abs)) {
                    lastReason.put(fp, "not-applicable:file-not-found");
                    continue;
                }
                String text = texts.containsKey(e.file()) ? texts.get(e.file()) : Files.readString(abs, StandardCharsets.UTF_8);
                FixAttempt a = cfg.rules().fix(e, new RuleEngine.SourceText(e.file(), text));
                if (!a.isApplied()) {
                    lastReason.put(fp, a.reason());
                    notApplied.merge(a.reason().replaceAll("[ :].*", ""), 1, Integer::sum);
                    continue;
                }
                texts.put(e.file(), a.newText());
                if (e.errorClass() == ErrorClass.MISSING_CLASS) {
                    fixedTypeKeys.add(typeKey(e));
                }
                if (lines(a.newText()) != lines(text)) {
                    lineShifted.add(e.file());
                }
                pending.add(new AppliedFix(fp, e.key(), keyCounts.get(e.key()), a.ruleId(), e.file(), e.line(), e.errorClass().id(), e.symbol(), a.before(), a.after(),
                        e.diagnostic().fullMessage()));
                appliedByRule.merge(a.ruleId(), 1, Integer::sum);
                Map<String, Object> fx = new LinkedHashMap<>();
                fx.put("rule", a.ruleId());
                fx.put("file", e.file());
                fx.put("line", e.line());
                fx.put("errorClass", e.errorClass().id());
                fx.put("symbol", e.symbol());
                fixes.add(fx);
            }
            for (Map.Entry<String, String> t : texts.entrySet()) {
                writeWithBackup(t.getKey(), t.getValue());
            }
            appliedTotal += pending.size();
            it.put("fixesApplied", fixes);
            it.put("deferred", deferred);
            it.put("notApplied", notApplied);
            iterations.add(it);
            if (pending.isEmpty()) {
                status = "needs-agent";
                break;
            }
        }
        // Escalate what is left.
        List<Map<String, Object>> tasks = writeTasks(errors, escalated, lastReason, tasksDir);
        // Aggregate candidate logs and draft rules.
        Map<String, Object> agg = aggregate();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("project", cfg.project().getFileName() == null ? cfg.project().toString() : cfg.project().getFileName().toString());
        report.put("status", status);
        report.put("maxIter", cfg.maxIter());
        report.put("compiles", iterations.size());
        report.put("rules", cfg.rules().ruleCount());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("errorsInitial", initial == null ? 0 : initial.size());
        totals.put("errorsInitialByClass", initial == null ? Map.of() : byClass(initial));
        totals.put("errorsFinal", errors.size());
        totals.put("fixesApplied", appliedTotal);
        totals.put("fixesVerified", verifiedTotal);
        totals.put("fixesPersisted", persistedTotal);
        totals.put("appliedByRule", appliedByRule);
        totals.put("tasks", tasks.size());
        int initialErrors = initial == null ? 0 : initial.size();
        totals.put("closedByRulesShare", initialErrors == 0 ? 0.0 : Math.round(1000.0 * (initialErrors - errors.size()) / initialErrors) / 1000.0);
        report.put("totals", totals);
        report.put("iterations", iterations);
        report.put("tasks", tasks.stream().map(t -> Map.of("id", t.get("id"), "reason", t.get("reason"))).toList());
        report.put("candidates", agg);
        return report;
    }

    private static String typeKey(ClassifiedError e) {
        String s = e.symbol() == null ? "" : e.symbol();
        return e.file() + "|" + s.substring(Math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1);
    }

    private static int lines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Integer> byClass(List<ClassifiedError> errors) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ErrorClass c : ErrorClass.values()) {
            int n = (int) errors.stream().filter(e -> e.errorClass() == c).count();
            if (n > 0) {
                m.put(c.id(), n);
            }
        }
        return m;
    }

    private void writeWithBackup(String rel, String text) throws IOException {
        Path abs = cfg.project().resolve(rel);
        Path backup = cfg.workDir().resolve("backup").resolve(rel);
        if (!Files.exists(backup)) {
            Files.createDirectories(backup.getParent());
            Files.copy(abs, backup);
        }
        Files.writeString(abs, text, StandardCharsets.UTF_8);
    }

    private void logCandidate(AppliedFix f) {
        if (cfg.candidateLog() == null) {
            return;
        }
        String norm = Normalizer.normalize(f.symbol(), f.before(), f.after());
        String id = "c-" + sha(f.fingerprint() + "|" + f.ruleId() + "|" + f.after()).substring(0, 12);
        Candidate c = new Candidate(1, id, cfg.from(), cfg.to(), f.error(), f.errorClass(), f.file(), f.line(), f.before(), f.after(),
                f.symbol(), norm, "rule:" + f.ruleId(), 1, f.ruleId());
        try {
            Files.createDirectories(cfg.candidateLog().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CandidateIo.append(cfg.candidateLog(), c);
    }

    // ---------------------------------------------------------------- tasks

    private List<Map<String, Object>> writeTasks(List<ClassifiedError> errors, Map<String, String> escalated,
            Map<String, String> lastReason, Path dir) throws IOException {
        Map<String, ClassifiedError> unique = new LinkedHashMap<>();
        for (ClassifiedError e : errors) {
            unique.putIfAbsent(e.fingerprint(), e);
        }
        Map<String, Object> graph = graphStatus(unique.values());
        List<Candidate> known = knownCandidates();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, ClassifiedError> en : unique.entrySet()) {
            ClassifiedError e = en.getValue();
            String fp = en.getKey();
            String reason = escalated.containsKey(e.key()) ? escalated.get(e.key()) : lastReason.getOrDefault(fp, "not-attempted");
            Map<String, Object> t = new LinkedHashMap<>();
            String id = "t-" + sha(fp).substring(0, 12);
            t.put("id", id);
            t.put("reason", reason);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("file", e.file());
            err.put("line", e.line());
            err.put("column", e.diagnostic().column());
            err.put("message", e.diagnostic().message());
            err.put("details", e.diagnostic().details());
            err.put("code", e.code());
            err.put("errorClass", e.errorClass().id());
            err.put("symbol", e.symbol());
            err.put("owner", e.owner());
            err.put("member", e.member());
            err.put("argTypes", e.argTypes());
            t.put("error", err);
            t.put("context", context(e));
            if (e.symbol() != null && graph != null) {
                Object gs = graph.get(e.symbol());
                t.put("graph", gs == null ? Map.of("status", "not-queried") : gs);
            } else {
                t.put("graph", Map.of("status", graph == null ? "no-graph" : "no-symbol"));
            }
            t.put("similar", similar(e, known));
            t.put("instructions", "Fix this error by hand in " + e.file() + ". Then append one candidate per fix (contracts/candidate.schema.json:"
                    + " error, errorClass, file, line, before, after, symbol, author agent:<you>, count 1) to "
                    + (cfg.candidateLog() == null ? "a *.candidates.jsonl log" : cfg.candidateLog().getParent().resolve("agent.candidates.jsonl").toString().replace('\\', '/'))
                    + " and rerun porter loop. Never guess an API: check it in the target jar or the symbol graph.");
            Files.writeString(dir.resolve(id + ".task.json"), Json.json().writeValueAsString(t).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
            out.add(t);
        }
        return out;
    }

    private Map<String, Object> context(ClassifiedError e) {
        SourceFacts f = factsCache.computeIfAbsent(cfg.project().resolve(e.file()), SourceFacts::read);
        int from = Math.max(1, e.line() - 4);
        int to = Math.min(f.lineCount(), e.line() + 4);
        List<String> lines = new ArrayList<>();
        for (int l = from; l <= to; l++) {
            lines.add(f.line(l));
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("firstLine", from);
        c.put("lines", lines);
        c.put("imports", new ArrayList<>(f.imports().values()));
        c.put("supertypes", f.supertypes());
        return c;
    }

    private Map<String, Object> graphStatus(java.util.Collection<ClassifiedError> errors) {
        if (cfg.graph() == null) {
            return null;
        }
        GraphSource g = cfg.graph().get();
        if (g == null) {
            return null;
        }
        Set<String> symbols = new java.util.TreeSet<>();
        for (ClassifiedError e : errors) {
            if (e.symbol() != null) {
                symbols.add(e.symbol());
            }
        }
        return symbols.isEmpty() ? Map.of() : g.lookupAll(symbols);
    }

    private List<Candidate> knownCandidates() {
        List<Path> srcs = new ArrayList<>();
        for (Path p : cfg.candidateSources()) {
            if (Files.exists(p)) {
                srcs.add(p);
            }
        }
        return srcs.isEmpty() ? List.of() : CandidateAggregator.readAll(srcs);
    }

    /** Up to 3 solved candidates similar to the error: same symbol, same member, or overlapping tokens. */
    static List<Map<String, Object>> similar(ClassifiedError e, List<Candidate> known) {
        Set<String> et = tokens((e.symbol() == null ? "" : e.symbol()) + " " + e.diagnostic().message() + " "
                + (e.diagnostic().sourceLine() == null ? "" : e.diagnostic().sourceLine()));
        record Scored(Candidate c, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : known) {
            double s;
            if (e.symbol() != null && e.symbol().equals(c.symbol())) {
                s = 1.0;
            } else if (e.member() != null && c.symbol() != null && c.symbol().contains("#" + e.member() + "(")) {
                s = 0.8;
            } else {
                Set<String> ct = tokens((c.symbol() == null ? "" : c.symbol()) + " " + c.error() + " " + c.before());
                Set<String> inter = new HashSet<>(et);
                inter.retainAll(ct);
                Set<String> union = new HashSet<>(et);
                union.addAll(ct);
                s = union.isEmpty() ? 0 : 0.7 * inter.size() / union.size();
            }
            if (s >= 0.15) {
                scored.add(new Scored(c, Math.round(s * 1000) / 1000.0));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparing(x -> String.valueOf(x.c().id())));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (Scored s : scored) {
            if (out.size() == 3) {
                break;
            }
            String key = CandidateAggregator.key(s.c());
            if (!seenKeys.add(key)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.c().id());
            m.put("score", s.score());
            m.put("symbol", s.c().symbol());
            m.put("before", s.c().before());
            m.put("after", s.c().after());
            m.put("normalized", key);
            m.put("author", s.c().author());
            out.add(m);
        }
        return out;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.split("[^A-Za-z0-9_$]+")) {
            if (t.length() > 2) {
                out.add(t.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    private Map<String, Object> aggregate() {
        List<Candidate> all = knownCandidates();
        List<CandidateAggregator.Group> groups = CandidateAggregator.aggregate(all);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidates", all.size());
        m.put("groups", groups.size());
        if (cfg.proposedDir() != null) {
            var drafts = CandidateAggregator.writeDrafts(groups, cfg.proposedDir(), CandidateAggregator.DRAFT_THRESHOLD, cfg.from(), cfg.to());
            m.put("drafts", drafts.stream().map(r -> r.id()).toList());
        }
        return m;
    }

    static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
