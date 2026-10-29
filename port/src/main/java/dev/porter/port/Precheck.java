package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.port.config.PortConfig;
import dev.porter.port.javac.JavacLog;
import dev.porter.port.passes.SourcePasses;
import dev.porter.rewrite.engine.Selector;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * {@code porter precheck}: the fast check before a full {@code porter port} run. It stops where the full run would stop
 * (rules, rewrite, install passes, compile) but only for what changed since the last full output:
 * <ol>
 *   <li>the rule files are validated (schema, duplicate ids, unsupported rules, same-priority conflicts);</li>
 *   <li>the changed rules / overlays / edits are found against {@code work/precheck/baseline.json} (written by the
 *       rewrite and install stages) or given with {@code --changed};</li>
 *   <li>the classes they touch are found from the last rewrite report (which rules fired in which class) plus a
 *       word scan of the source for the symbols the rules select;</li>
 *   <li>only those classes are rewritten, put through the install passes, the loop and javac+castfix on a scratch
 *       project; the rest of the project is the {@code -sourcepath} (and the last build's classes).</li>
 * </ol>
 * Nothing in the real project or in {@code work/rw}, {@code work/stage} is written: all output is {@code work/precheck}.
 * Not covered: classes that only <em>use</em> a changed class (reverse dependencies are compiled only when a checked
 * class pulls them in), hub stubs, generated sources, the build stage.
 */
final class Precheck {
    private static final Set<String> KEYWORDS = Set.of("new", "this", "super", "return", "null", "true", "false", "int", "long", "void",
            "float", "double", "boolean", "char", "byte", "short", "if", "else", "for", "while", "var", "final", "static", "class");

    private final PortConfig cfg;
    private final PortRunner runner;
    private final PrintStream out;
    private final Path work;
    private final Path dir;
    private final List<String> errorClasses = new ArrayList<>();
    private final Map<String, Integer> classCounts = new TreeMap<>();
    private final Map<String, String> classExamples = new LinkedHashMap<>();

    Precheck(PortConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
        this.runner = new PortRunner(cfg, out);
        this.work = cfg.work();
        this.dir = work.resolve("precheck");
    }

    /** Options of one precheck. */
    record Options(List<Path> changed, List<String> classes, boolean baseline, boolean rulesOnly, int maxClasses) {
    }

    // ------------------------------------------------------------------------------------------------ baseline

    private static Path baselineFile(Path work) {
        return work.resolve("precheck/baseline.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBaseline(Path work) {
        Path f = baselineFile(work);
        return Files.exists(f) ? Json.json().readValue(Text.read(f), LinkedHashMap.class) : new LinkedHashMap<>();
    }

    private static void writeBaseline(Path work, Map<String, Object> b) {
        Text.write(baselineFile(work), Json.json().writeValueAsString(b) + "\n");
    }

    /** Called by the rewrite stage: remembers what each rule looked like when the output was made. Never fails the run. */
    static void recordRules(PortConfig cfg, Path work) {
        try {
            Map<String, Object> b = readBaseline(work);
            b.put("rules", ruleDigests(loadRules(cfg).rules()));
            writeBaseline(work, b);
        } catch (RuntimeException e) {
            // the precheck then asks for --baseline; the run itself is not affected
        }
    }

    /** Called by the install stage: remembers the overlays and edits that were installed. Never fails the run. */
    static void recordFiles(PortConfig cfg, Path work) {
        try {
            Map<String, Object> b = readBaseline(work);
            b.put("files", fileDigests(cfg));
            writeBaseline(work, b);
        } catch (RuntimeException e) {
            // see recordRules
        }
    }

    private static RuleChecks.Result loadRules(PortConfig cfg) {
        JsonNode rw = cfg.section("rewrite");
        return RuleChecks.load(cfg.paths(rw.path("rules")), cfg.from(), cfg.to());
    }

    private static Map<String, Object> ruleDigests(List<RuleChecks.Loaded> rules) {
        Map<String, Object> m = new TreeMap<>();
        for (RuleChecks.Loaded l : rules) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("h", sha1(Json.json().writeValueAsString(l.rule())));
            e.put("n", needles(l.rule()));
            m.put(l.rule().id(), e);
        }
        return m;
    }

    private static List<Path> overlayDirs(PortConfig cfg) {
        return cfg.has("install") ? cfg.paths(cfg.section("install").path("overlays")) : List.of();
    }

    private static Path editsFile(PortConfig cfg) {
        return cfg.has("install") && cfg.section("install").has("edits") ? cfg.path(cfg.section("install").get("edits").asString()) : null;
    }

    /** overlay:<path under the overlay dir> -> sha1; edit:<file> -> sha1 of that file's edits. */
    private static Map<String, Object> fileDigests(PortConfig cfg) {
        Map<String, Object> m = new TreeMap<>();
        for (Path d : overlayDirs(cfg)) {
            for (Path f : filesUnder(d)) {
                m.put("overlay:" + rel(d, f), sha1(Text.read(f)));
            }
        }
        Path e = editsFile(cfg);
        if (e != null && Files.exists(e)) {
            Map<String, StringBuilder> per = new TreeMap<>();
            for (SourcePasses.SourceEdit ed : SourcePasses.readEdits(e)) {
                per.computeIfAbsent(ed.file(), k -> new StringBuilder()).append(ed.pattern()).append('\u0000').append(ed.replace()).append('\u0001');
            }
            per.forEach((k, v) -> m.put("edit:" + k, sha1(v.toString())));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ run

    int run(Options o) {
        long t0 = System.nanoTime();
        out.println("precheck " + cfg.name() + ": work " + work);
        // 1. rules
        long t = System.nanoTime();
        RuleChecks.Result rules = loadRules(cfg);
        Map<String, Object> rsum = new LinkedHashMap<>();
        rsum.put("rules", rules.rules().size());
        rsum.put("problems", rules.problems().size());
        out.println(PortRunner.stageLine("rules", rules.problems().isEmpty(), secs(t), rsum));
        for (String p : rules.problems()) {
            out.println("   " + p);
            countProblem(p);
        }
        if (!rules.problems().isEmpty()) {
            return finish(false, t0);
        }
        if (o.baseline()) {
            Map<String, Object> b = readBaseline(work);
            b.put("rules", ruleDigests(rules.rules()));
            b.put("files", fileDigests(cfg));
            writeBaseline(work, b);
            out.println("baseline written: the current rules, overlays and edits are now 'built' (" + baselineFile(work) + ")");
            return finish(true, t0);
        }
        if (o.rulesOnly()) {
            return finish(true, t0);
        }
        // 2. what changed -> affected classes
        List<String> port = Text.words(work.resolve("closure/port.txt").toFile().exists() ? work.resolve("closure/port.txt") : missing("closure/port.txt"));
        List<Relocation> rel = runner.relocations();
        Map<String, String> pathToClass = new LinkedHashMap<>();
        for (String c : port) {
            pathToClass.put(Relocation.path(rel, c), c);
        }
        Map<String, Object> base = readBaseline(work);
        boolean explicit = !o.changed().isEmpty() || !o.classes().isEmpty();
        if (!explicit && (!base.containsKey("rules") || !base.containsKey("files"))) {
            out.println("precheck: no baseline in " + baselineFile(work) + " (the rewrite/install stages write it). Run once with");
            out.println("          --baseline (marks the current rules/overlays/edits as built) or name the changes with --changed/--class.");
            return ExitCodes.USAGE;
        }
        t = System.nanoTime();
        Change ch = detect(o, rules, base, rel);
        Set<String> classes = new LinkedHashSet<>(o.classes());
        Set<String> overlayedPaths = new HashSet<>();
        Map<String, Path> overlayFiles = new TreeMap<>(); // path under the source root -> file
        for (Path d : overlayDirs(cfg)) {
            for (Path f : filesUnder(d)) {
                if (f.toString().endsWith(".java")) {
                    overlayFiles.put(rel(d, f), f);
                    overlayedPaths.add(rel(d, f));
                }
            }
        }
        classes.addAll(classesOfRules(ch.rules, rules, base, port, rel));
        for (String f : ch.editFiles) {
            String c = pathToClass.get(f);
            if (c != null) {
                classes.add(c);
            }
        }
        classes.retainAll(new HashSet<>(port));
        List<String> toRewrite = new ArrayList<>();
        for (String c : classes) {
            if (!overlayedPaths.contains(Relocation.path(rel, c))) { // an overlay replaces the rewritten class anyway
                toRewrite.add(c);
            }
        }
        boolean partial = toRewrite.size() > o.maxClasses();
        int total = toRewrite.size();
        if (partial) {
            toRewrite = new ArrayList<>(toRewrite.subList(0, o.maxClasses()));
        }
        Set<String> changedOverlays = new TreeSet<>(ch.overlays);
        Map<String, Object> csum = new LinkedHashMap<>();
        csum.put("changedRules", ch.rules.size());
        csum.put("changedOverlays", changedOverlays.size());
        csum.put("changedEdits", ch.editFiles.size());
        csum.put("classes", total);
        if (partial) {
            csum.put("checked", toRewrite.size());
        }
        out.println(PortRunner.stageLine("changes", true, secs(t), csum));
        if (partial) {
            out.println("   partial: " + total + " classes are affected, only the first " + toRewrite.size() + " are checked (--max-classes)");
        }
        if (toRewrite.isEmpty() && changedOverlays.isEmpty()) {
            out.println("nothing to compile: the changes touch no class of the closure");
            return finish(true, t0);
        }
        // 3. scratch project
        Path proj = dir.resolve("proj");
        Path stageRoot = proj.resolve("src/main/java");
        Path ctx = dir.resolve("ctx");
        PortRunner.deleteTree(proj);
        PortRunner.deleteTree(ctx);
        PortRunner.deleteTree(dir.resolve("rw"));
        // 4. rewrite the affected classes
        if (!toRewrite.isEmpty()) {
            t = System.nanoTime();
            Map<String, Object> sum = new LinkedHashMap<>();
            JsonNode r = cfg.section("rewrite");
            // the closure's sources only (the rest of the decompiled jar is never referenced by it): a third of the model to build
            Path pruned = dir.resolve("src-1");
            PortRunner.deleteTree(pruned);
            Path srcRoot = cfg.path(cfg.section("closure").path("source").asString());
            List<String> all = new ArrayList<>(port);
            if (Files.exists(work.resolve("closure/frontier.txt"))) {
                all.addAll(Text.words(work.resolve("closure/frontier.txt")));
            }
            for (String c : all) {
                Path f = srcRoot.resolve(c.replace('.', '/') + ".java");
                if (Files.exists(f)) {
                    PortRunner.copy(f, pruned.resolve(c.replace('.', '/') + ".java"));
                }
            }
            // the types those classes import resolve the member accesses (a hub field's type decides which rule fires)
            addImported(srcRoot, pruned, 2);
            Path known = dir.resolve("known.txt");
            Text.writeLines(known, all);
            dev.porter.port.run.Proc.Result res = runner.porter("precheck-rewrite", runner.rewriteArgs(toRewrite, dir.resolve("rw"), pruned, known),
                    PortConfig.strings(r.path("jvmArgs")));
            sum.put("classes", toRewrite.size());
            sum.put("exitCode", res.exitCode());
            out.println(PortRunner.stageLine("rewrite", res.ok(), secs(t), sum));
            if (!res.ok()) {
                count("rewrite:failed", "porter rewrite exit " + res.exitCode() + ", log " + work.resolve("logs/precheck-rewrite.log"));
                out.println("   logs: " + work.resolve("logs/precheck-rewrite.log"));
                return finish(false, t0);
            }
            for (String c : toRewrite) {
                Path src = dir.resolve("rw").resolve(Relocation.path(rel, c));
                if (!Files.exists(src)) {
                    count("rewrite:output-missing", c);
                    out.println("   rewriter output missing for " + c);
                    return finish(false, t0);
                }
                PortRunner.copy(src, stageRoot.resolve(Relocation.path(rel, c)));
            }
        }
        // 5. install passes + overlays on the subset
        t = System.nanoTime();
        Map<String, Object> isum = new LinkedHashMap<>();
        try {
            if (cfg.has("install") && !toRewrite.isEmpty()) {
                isum.put("passes", runner.applyPasses(stageRoot, cfg.section("install"), true));
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            String cls = m.contains("no longer matches") ? "edit:no-longer-matches" : m.contains("target missing") ? "edit:target-missing" : "install:pass-failed";
            count(cls, m);
            out.println(PortRunner.stageLine("install", false, secs(t), isum));
            out.println("   error: " + clip(m));
            return finish(false, t0);
        }
        int ov = 0;
        for (Map.Entry<String, Path> e : overlayFiles.entrySet()) {
            if (changedOverlays.contains(e.getKey()) || Files.exists(stageRoot.resolve(e.getKey()))) {
                PortRunner.copy(e.getValue(), stageRoot.resolve(e.getKey()));
                ov++;
            }
        }
        isum.put("overlayFiles", ov);
        isum.put("files", Text.javaFiles(stageRoot).size());
        out.println(PortRunner.stageLine("install", true, secs(t), isum));
        // the rest of the project, as the source path (a copy: nothing here may touch the real project)
        copyTree(cfg.sources(), ctx, stageRoot);
        Path lastClasses = work.resolve("classes");
        List<Path> extraCp = Files.isDirectory(lastClasses) ? List.of(lastClasses) : List.of();
        // 6. loop
        if (cfg.has("loop")) {
            t = System.nanoTime();
            Map<String, Object> lsum = new LinkedHashMap<>();
            boolean ok = runner.loopOn("precheck-loop", proj, stageRoot, dir.resolve("loop"), dir.resolve("gen-src"), extraCp,
                    List.of("-sourcepath", ctx.toString()), lsum);
            out.println(PortRunner.stageLine("loop", ok, secs(t), lsum));
        }
        // 7. compile
        t = System.nanoTime();
        Map<String, Object> ksum = new LinkedHashMap<>();
        PortRunner.Target target = new PortRunner.Target(proj, stageRoot, dir.resolve("classes"), dir, dir.resolve("gen-src"), "precheck-",
                List.of("-sourcepath", ctx.toString(), "-implicit:none"), extraCp);
        String log;
        try {
            log = runner.compileOn(target, dir.resolve("symbols.json"), ksum);
        } catch (IllegalStateException e) {
            count("compile:javac-did-not-run", e.getMessage());
            out.println("compile   FAILED  " + clip(e.getMessage()));
            return finish(false, t0);
        }
        out.println(PortRunner.stageLine("compile", log != null, secs(t), ksum));
        if (log != null) {
            return finish(true, t0);
        }
        var errs = runner.lastErrors;
        for (var e : errs.symbols()) {
            count("javac:" + e.key(), e.key(), e.count(), e.files().isEmpty() ? "" : e.files().get(0));
        }
        int shown = 0;
        for (JavacLog.Error e : JavacLog.parse(Text.read(dir.resolve("precheck-compile.log")))) {
            if (shown++ >= 15) {
                break;
            }
            out.println("   " + e.file() + ":" + e.line() + ": " + e.message());
        }
        out.println("   logs: " + dir + "   (symbols.json, precheck-compile.log)");
        return finish(false, t0);
    }

    private static String clip(String s) {
        return dev.porter.core.cli.Compact.clip(s == null ? "" : s, 300);
    }

    private Path missing(String what) {
        throw new IllegalStateException("precheck needs the output of a full run first: " + work.resolve(what) + " is missing");
    }

    private int finish(boolean ok, long t0) {
        long secs = secs(t0);
        if (!classCounts.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : classCounts.entrySet()) {
                b.append(ErrorClasses.line(e.getKey(), e.getValue(), classExamples.get(e.getKey())));
            }
            Text.write(dir.resolve("error-classes.tsv"), b.toString());
            out.println("error classes (" + classCounts.size() + ", for status/RECURRING.md; file " + dir.resolve("error-classes.tsv") + "):");
            int n = 0;
            for (Map.Entry<String, Integer> e : classCounts.entrySet()) {
                if (n++ >= 20) {
                    out.println("   ... " + (classCounts.size() - 20) + " more");
                    break;
                }
                out.println(String.format(java.util.Locale.ROOT, "   %5d  %s", e.getValue(), clip(e.getKey())));
            }
        } else {
            Text.write(dir.resolve("error-classes.tsv"), "");
        }
        out.println("precheck " + (ok ? "OK" : "FAILED") + " in " + PortRunner.duration(secs));
        return ok ? ExitCodes.OK : ExitCodes.FAILED;
    }

    /** A rule problem {@code rules:<kind>: <detail>}. */
    private void countProblem(String problem) {
        int c = problem.indexOf(": ");
        count(problem.substring(0, c), problem.substring(c + 2), 1, null);
    }

    private void count(String cls, String example) {
        count(cls, example, 1, null);
    }

    private void count(String cls, String example, int n, String where) {
        classCounts.merge(cls, n, Integer::sum);
        classExamples.putIfAbsent(cls, where == null || where.isEmpty() ? example : where);
    }

    private static long secs(long t0) {
        return Math.round((System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------------------------------------------ changes

    private record Change(Set<String> rules, Set<String> overlays, Set<String> editFiles) {
    }

    @SuppressWarnings("unchecked")
    private Change detect(Options o, RuleChecks.Result rules, Map<String, Object> base, List<Relocation> rel) {
        Set<String> cr = new TreeSet<>();
        Set<String> co = new TreeSet<>();
        Set<String> ce = new TreeSet<>();
        if (!o.changed().isEmpty()) {
            Path edits = editsFile(cfg);
            for (Path c : o.changed()) {
                Path f = c.toAbsolutePath().normalize();
                boolean known = false;
                for (RuleChecks.Loaded l : rules.rules()) {
                    if (l.file().toAbsolutePath().normalize().startsWith(f)) {
                        cr.add(l.rule().id());
                        known = true;
                    }
                }
                for (Path d : overlayDirs(cfg)) {
                    Path dn = d.toAbsolutePath().normalize();
                    if (f.startsWith(dn)) {
                        for (Path x : Files.isDirectory(f) ? filesUnder(f) : List.of(f)) {
                            if (x.toString().endsWith(".java")) {
                                co.add(rel(dn, x.toAbsolutePath().normalize()));
                            }
                        }
                        known = true;
                    }
                }
                if (edits != null && f.equals(edits.toAbsolutePath().normalize())) {
                    SourcePasses.readEdits(edits).forEach(e -> ce.add(e.file()));
                    known = true;
                }
                if (!known) {
                    out.println("   --changed " + c + ": not a rule file, overlay or edits file of this profile (ignored)");
                }
            }
            return new Change(cr, co, ce);
        }
        if (o.classes().isEmpty()) {
            Map<String, Object> was = (Map<String, Object>) base.getOrDefault("rules", Map.of());
            Map<String, Object> now = ruleDigests(rules.rules());
            for (Map.Entry<String, Object> e : now.entrySet()) {
                Object prev = was.get(e.getKey());
                if (prev == null || !((Map<String, Object>) prev).get("h").equals(((Map<String, Object>) e.getValue()).get("h"))) {
                    cr.add(e.getKey());
                }
            }
            for (String id : was.keySet()) {
                if (!now.containsKey(id)) {
                    cr.add(id);
                }
            }
            Map<String, Object> wasF = (Map<String, Object>) base.getOrDefault("files", Map.of());
            Map<String, Object> nowF = fileDigests(cfg);
            Set<String> keys = new TreeSet<>(nowF.keySet());
            keys.addAll(wasF.keySet());
            for (String k : keys) {
                if (java.util.Objects.equals(wasF.get(k), nowF.get(k))) {
                    continue;
                }
                if (k.startsWith("overlay:")) {
                    co.add(k.substring(8));
                } else if (k.startsWith("edit:")) {
                    ce.add(k.substring(5));
                }
            }
        }
        return new Change(cr, co, ce);
    }

    /** Classes (dotted) the changed rules touch: where they fired in the last rewrite report, plus where their symbols occur. */
    @SuppressWarnings("unchecked")
    private Set<String> classesOfRules(Set<String> changed, RuleChecks.Result current, Map<String, Object> base, List<String> port,
            List<Relocation> rel) {
        Set<String> out = new LinkedHashSet<>();
        if (changed.isEmpty()) {
            return out;
        }
        Map<String, String> pathToClass = new java.util.HashMap<>();
        for (String c : port) {
            pathToClass.put(Relocation.path(rel, c), c);
        }
        Path report = work.resolve("rw/porter-rewrite.json");
        if (Files.exists(report)) {
            JsonNode files = Json.json().readTree(Text.read(report)).path("files");
            for (JsonNode f : files) {
                for (String id : changed) {
                    if (f.path("rules").has(id)) {
                        String c = pathToClass.get(f.path("path").asString());
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
            }
        }
        // needle sets (all words must occur in the class): the current rule and, when it changed, the one that was built
        List<List<String>> needleSets = new ArrayList<>();
        Map<String, Object> was = (Map<String, Object>) base.getOrDefault("rules", Map.of());
        Map<String, Rule> now = new LinkedHashMap<>();
        current.rules().forEach(l -> now.put(l.rule().id(), l.rule()));
        for (String id : changed) {
            if (now.containsKey(id)) {
                needleSets.add(needles(now.get(id)));
            }
            Object w = was.get(id);
            if (w != null) {
                needleSets.add(((List<String>) ((Map<String, Object>) w).get("n")));
            }
        }
        needleSets.removeIf(List::isEmpty);
        if (!needleSets.isEmpty()) {
            Path srcRoot = cfg.path(cfg.section("closure").path("source").asString());
            for (String c : port) {
                if (out.contains(c)) {
                    continue;
                }
                Path f = srcRoot.resolve(c.replace('.', '/') + ".java");
                if (!Files.exists(f)) {
                    continue;
                }
                String text = Text.read(f);
                for (List<String> set : needleSets) {
                    if (set.stream().allMatch(n -> Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(text).find())) {
                        out.add(c);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** The words of the source a rule selects: all of them must occur in a class for the rule to possibly fire there. */
    static List<String> needles(Rule r) {
        if (r.match() == null) {
            return List.of();
        }
        Selector s;
        try {
            s = Selector.of(r.match());
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> n = new ArrayList<>();
        switch (s.kind()) {
            case CALL, OVERRIDE, FIELD -> n.add(s.name());
            case NEW, TYPE -> n.add(simple(s.owner()));
            case DECLARATION -> {
                n.add(simple(s.owner()));
                if (s.name() != null) {
                    n.add(s.name());
                }
            }
            case PATTERN -> {
                String p = s.pattern().replaceAll("\\$\\w+", " ");
                java.util.regex.Matcher m = Pattern.compile("[A-Za-z_]\\w{2,}").matcher(p);
                while (m.find()) {
                    if (!KEYWORDS.contains(m.group()) && !n.contains(m.group())) {
                        n.add(m.group());
                    }
                }
            }
        }
        return n;
    }

    private static String simple(String owner) {
        int i = Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$'));
        return owner.substring(i + 1);
    }

    // ------------------------------------------------------------------------------------------------ files

    private static List<Path> filesUnder(Path d) {
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);

    /**
     * Copies from {@code srcRoot} into {@code pruned} the sources that the files already there import, {@code depth}
     * rounds deep (a source root that holds only the closure leaves the types of its hub members unresolved).
     */
    static void addImported(Path srcRoot, Path pruned, int depth) {
        for (int round = 0; round < depth; round++) {
            boolean added = false;
            for (Path f : Text.javaFiles(pruned)) {
                var m = IMPORT.matcher(Text.read(f));
                while (m.find()) {
                    String rel = m.group(1).replace('.', '/') + ".java";
                    Path src = srcRoot.resolve(rel);
                    Path dst = pruned.resolve(rel);
                    if (Files.isRegularFile(src) && !Files.exists(dst)) {
                        PortRunner.copy(src, dst);
                        added = true;
                    }
                }
            }
            if (!added) {
                return;
            }
        }
    }

    private static String rel(Path root, Path f) {
        return root.toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Copies the java sources of {@code from} to {@code to}, except those that also exist under {@code except} (the new versions). */
    private static void copyTree(Path from, Path to, Path except) {
        for (Path f : Text.javaFiles(from)) {
            String r = rel(from, f);
            if (Files.exists(except.resolve(r))) {
                continue;
            }
            PortRunner.copy(f, to.resolve(r));
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
