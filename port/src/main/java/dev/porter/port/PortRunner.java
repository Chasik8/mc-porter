package dev.porter.port;

import dev.porter.core.cli.Compact;
import dev.porter.core.json.Json;
import dev.porter.port.closure.ClassIndex;
import dev.porter.port.closure.Closure;
import dev.porter.port.config.PortConfig;
import dev.porter.port.jar.ObfCheck;
import dev.porter.port.javac.CastFix;
import dev.porter.port.javac.JavacLog;
import dev.porter.port.javac.Symbols;
import dev.porter.port.passes.Adapters;
import dev.porter.port.passes.HubStubs;
import dev.porter.port.passes.InertHubCalls;
import dev.porter.port.passes.SourcePasses;
import dev.porter.port.passes.SrgMap;
import dev.porter.port.run.Proc;
import dev.porter.rewrite.engine.TypeFacts;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Runs the stages of one port config. Stages (in this order; a stage runs only when its section is present):
 * <pre>
 *   setup     one-time prerequisites (steps skipped when what they create exists): downloads, decompiles, toolchains
 *   derive    steps that derive the inputs of generate on every run: extract, IR selection, assets, patches
 *   generate  porter generate per unit -> install generated files into the project (regen policy)
 *   closure   dependency closure of the seeds: classes to port + frontier hubs (+ known-identical check), coverage
 *   upfront   optional: porter upfront on the closure (emitted rules, generated shims, TODO batch)
 *   rewrite   porter rewrite of closure + frontier with the rules (relocated, shim package)
 *   install   stage = closure classes + hub stubs + shim + passes + edits + adapters/overlays -> project
 *   loop      porter loop (compile/classify/fix) on the project
 *   compile   javac on the project, decompiler-artefact cast fixes, distinct-symbol aggregation
 *   build     the project's own build (e.g. gradlew build), reobf collision check of the jar
 *   test      scenario runs (porter test ...)
 * </pre>
 * Progress is kept in {@code <work>/port-state.json}; by default a run resumes at the first stage that has not
 * finished successfully, and re-running a stage invalidates the stages after it. After every stage the port report
 * ({@code <work>/port-report.json} and {@code .md}) is rewritten.
 */
public final class PortRunner {
    public static final List<String> STAGES = List.of("setup", "derive", "generate", "closure", "upfront", "rewrite", "install", "loop", "compile", "build", "test");

    private final PortConfig cfg;
    private final PrintStream out;
    private final Path work;
    private final Path state;
    private final Map<String, Object> st;

    public PortRunner(PortConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
        this.work = cfg.work();
        this.state = work.resolve("port-state.json");
        this.st = loadState();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadState() {
        if (Files.exists(state)) {
            return Json.json().readValue(Text.read(state), LinkedHashMap.class);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", cfg.file().toString());
        m.put("stages", new LinkedHashMap<String, Object>());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stages() {
        return (Map<String, Object>) st.get("stages");
    }

    private void saveState() {
        Text.write(state, Json.json().writeValueAsString(st) + "\n");
    }

    public List<String> enabled() {
        return STAGES.stream().filter(cfg::has).toList();
    }

    @SuppressWarnings("unchecked")
    public String status(String stage) {
        Object o = stages().get(stage);
        return o == null ? "pending" : String.valueOf(((Map<String, Object>) o).get("status"));
    }

    /** Stages a run would execute: [from..to] of the enabled ones; from defaults to the first not-ok stage. */
    public List<String> plan(String from, String to, String only, boolean restart) {
        List<String> en = enabled();
        if (only != null) {
            requireStage(only);
            return en.contains(only) ? List.of(only) : List.of();
        }
        int a = 0;
        if (from != null) {
            requireStage(from);
            a = Math.max(0, en.indexOf(from));
        } else if (!restart) {
            while (a < en.size() && "ok".equals(status(en.get(a)))) {
                a++;
            }
        }
        int b = en.size() - 1;
        if (to != null) {
            requireStage(to);
            b = en.indexOf(to) < 0 ? b : en.indexOf(to);
        }
        return a > b ? List.of() : en.subList(a, b + 1);
    }

    private static void requireStage(String s) {
        if (!STAGES.contains(s)) {
            throw new IllegalArgumentException("unknown stage '" + s + "' (stages: " + String.join(", ", STAGES) + ")");
        }
    }

    /** Runs the planned stages; returns true when all succeeded. */
    public boolean run(List<String> plan) {
        boolean verbose = Compact.verbose();
        for (String s : plan) {
            if (verbose) {
                out.println("== " + s);
            }
            long t0 = System.nanoTime();
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("startedAt", Instant.now().toString());
            boolean ok;
            Map<String, Object> summary = new LinkedHashMap<>();
            String error = null;
            try {
                ok = runStage(s, summary);
                if (ok) {
                    ok = applyPatches(s, summary);
                }
            } catch (RuntimeException e) {
                summary.put("error", e.toString());
                error = e.getMessage();
                ok = false;
            }
            long secs = Math.round((System.nanoTime() - t0) / 1e9);
            rec.put("finishedAt", Instant.now().toString());
            rec.put("status", ok ? "ok" : "failed");
            rec.put("summary", summary);
            stages().put(s, rec);
            // a (re)run stage invalidates the later ones
            boolean after = false;
            for (String t : STAGES) {
                if (after) {
                    stages().remove(t);
                }
                after |= t.equals(s);
            }
            saveState();
            if (verbose) {
                if (error != null) {
                    out.println("   error: " + error);
                }
                out.println("   " + (ok ? "ok" : "FAILED") + " " + Json.json().writeValueAsString(summary));
            } else {
                out.println(stageLine(s, ok, secs, summary));
                if (!ok) {
                    if (error != null) {
                        out.println("   error: " + Compact.clip(error, 200));
                    }
                    out.println("   logs: " + work.resolve("logs") + "   report: " + work.resolve("port-report.md")
                            + "   (--verbose prints everything)");
                }
            }
            writeReport();
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void detail(String line) {
        if (Compact.verbose()) {
            out.println(line);
        }
    }

    /** One console line per stage: stage, status, duration, the scalar metrics of its summary. */
    static String stageLine(String stage, boolean ok, long secs, Map<String, Object> summary) {
        StringBuilder m = new StringBuilder();
        for (Map.Entry<String, Object> e : summary.entrySet()) {
            Object v = e.getValue();
            String text;
            if (v instanceof Number || v instanceof Boolean) {
                text = String.valueOf(v);
            } else if (v instanceof java.util.Collection<?> c) {
                text = c.size() <= 6 && c.stream().allMatch(x -> x instanceof Number) ? String.valueOf(c).replace(" ", "") : null;
            } else if (v instanceof Map<?, ?> mp) {
                text = mp.size() <= 8 && mp.values().stream().allMatch(x -> x instanceof Number) ? String.valueOf(mp).replace(" ", "") : null;
            } else if (v instanceof String str && str.length() <= 40 && !str.contains("/") && !str.contains("\\")) {
                text = str;
            } else {
                text = null;
            }
            if (text == null) {
                continue;
            }
            if (m.length() > 0) {
                m.append(", ");
            }
            m.append(e.getKey()).append(' ').append(text);
            if (m.length() > 110) {
                m.append(", ...");
                break;
            }
        }
        return String.format(java.util.Locale.ROOT, "%-9s %-6s %5s  %s", stage, ok ? "ok" : "FAILED", duration(secs), m);
    }

    static String duration(long secs) {
        return secs >= 60 ? (secs / 60) + "m" + String.format(java.util.Locale.ROOT, "%02d", secs % 60) + "s" : secs + "s";
    }

    private boolean runStage(String s, Map<String, Object> summary) {
        return switch (s) {
            case "setup", "derive" -> new Steps(cfg, work, childEnv(), this::detail).run(s, cfg.section(s), summary);
            case "generate" -> generate(summary);
            case "closure" -> closure(summary);
            case "upfront" -> upfront(summary);
            case "rewrite" -> rewrite(summary);
            case "install" -> install(summary);
            case "loop" -> loop(summary);
            case "compile" -> compile(summary);
            case "build" -> build(summary);
            case "test" -> test(summary);
            default -> throw new IllegalArgumentException(s);
        };
    }

    // ------------------------------------------------------------------------------------------------- report

    private static String cell(Object o) {
        return String.valueOf(o).replace("|", "\\|").replace("\n", " ");
    }

    /**
     * {@code <work>/port-report.json} + {@code .md}: stage status and metrics, reuse (coverage), and everything that
     * was NOT ported, each with a reason: nothing is dropped silently.
     */
    @SuppressWarnings("unchecked")
    public void writeReport() {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("name", cfg.name());
        rep.put("mode", cfg.root().path("mode").asString("full"));
        rep.put("from", cfg.from());
        rep.put("to", cfg.to());
        rep.put("stages", stages());
        List<Map<String, Object>> notPorted = new ArrayList<>();
        Path gen = work.resolve("gen");
        if (Files.isDirectory(gen)) {
            try (Stream<Path> s = Files.list(gen)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".not-generated.json")).sorted().toList()) {
                    for (Object o : Json.json().readValue(Text.read(f), List.class)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("what", "content (generate)");
                        row.putAll((Map<String, Object>) o);
                        notPorted.add(row);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (cfg.has("closure")) {
            for (String d : PortConfig.strings(cfg.section("closure").path("drop"))) {
                notPorted.add(row("code (closure drop)", d, "cut from the closure and not stubbed (closure.drop)"));
            }
            JsonNode stubs = cfg.section("install").path("stubs");
            Path ov = stubs.has("overrides") ? cfg.path(stubs.path("overrides").asString()) : null;
            List<Relocation> rel = relocations();
            for (String f : frontierList()) {
                boolean hand = ov != null && Files.exists(ov.resolve(Relocation.path(rel, f)));
                notPorted.add(row("code (hub)", f, hand
                        ? "hub cut from the closure; hand-written replacement (install.stubs.overrides)"
                        : "hub cut from the closure; generated stub keeps the referenced members with inert bodies (feature not ported)"));
            }
        }
        Path sym = work.resolve("symbols.json");
        if (Files.exists(sym)) {
            for (JsonNode e : Json.json().readTree(Text.read(sym)).path("symbols")) {
                notPorted.add(row("code (unresolved symbol)", e.path("key").asString(),
                        e.path("count").asInt() + " javac error(s) left; resolve once per distinct symbol (rule/adapter/edit)"));
            }
        }
        rep.put("notPorted", notPorted);
        List<Object> inert = new ArrayList<>();
        Path inertFile = work.resolve("inert-hub-calls.json");
        if (Files.exists(inertFile)) {
            inert.addAll(Json.json().readValue(Text.read(inertFile), List.class));
        }
        rep.put("inertHubCallCount", inert.size());
        rep.put("inertHubCalls", inert);
        Text.write(work.resolve("port-report.json"), Json.json().writeValueAsString(rep) + "\n");
        StringBuilder md = new StringBuilder();
        md.append("# Port report: ").append(cfg.name()).append("\n\n");
        md.append("Mode: ").append(rep.get("mode")).append(", ").append(cfg.from()).append(" -> ").append(cfg.to()).append("\n\n");
        md.append("| stage | status | summary |\n|---|---|---|\n");
        for (String s : STAGES) {
            Object o = stages().get(s);
            if (o != null) {
                Map<String, Object> m = (Map<String, Object>) o;
                md.append("| ").append(s).append(" | ").append(m.get("status")).append(" | `")
                        .append(cell(Json.json().writeValueAsString(m.get("summary")))).append("` |\n");
            }
        }
        md.append("\n## Not ported (").append(notPorted.size()).append(")\n\n");
        md.append("| what | subject | reason |\n|---|---|---|\n");
        for (Map<String, Object> r : notPorted) {
            md.append("| ").append(cell(r.get("what"))).append(" | ").append(cell(r.get("subject"))).append(" | ")
                    .append(cell(r.get("reason"))).append(" |\n");
        }
        if (!inert.isEmpty()) {
            md.append("\n## Calls into inert hub-stub members (").append(inert.size()).append(")\n\n");
            md.append("Kept code calls a member whose stub body does nothing: the feature is silently missing.\n\n");
            md.append("| stub member | file:line | kind | client |\n|---|---|---|---|\n");
            for (Object o : inert) {
                Map<String, Object> r = (Map<String, Object>) o;
                md.append("| ").append(cell(r.get("stub") + "#" + r.get("member"))).append(" | ").append(cell(r.get("file")))
                        .append(":").append(r.get("line")).append(" | ").append(r.get("kind")).append(" | ")
                        .append(r.get("client")).append(" |\n");
            }
        }
        Text.write(work.resolve("port-report.md"), md.toString());
    }

    private static Map<String, Object> row(String what, String subject, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("what", what);
        m.put("subject", subject);
        m.put("reason", reason);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    Proc.Result porter(String step, List<String> args, List<String> jvmArgs) {
        List<String> jvm = jvmArgs.isEmpty() ? cfg.jvmArgs() : jvmArgs;
        List<String> cmd = Proc.porter(cfg.queue(), jvm, work.resolve("reports"), work.resolve("args/" + step + ".args"), args);
        detail("   porter " + args.get(0) + " ... (log: " + work.resolve("logs/" + step + ".log") + ")");
        return Proc.run(cmd, cfg.dir(), childEnv(), work.resolve("logs/" + step + ".log"), null);
    }

    /**
     * Environment of child steps: porter's classpath (child porter JVMs and the queue's exec helper) and the
     * plugins of the config ({@code plugins:}, joined to {@code PORTER_PLUGINS}).
     */
    Map<String, String> childEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLASSPATH", System.getProperty("java.class.path"));
        List<String> plugins = new ArrayList<>();
        for (Path p : cfg.paths(cfg.section("plugins"))) {
            plugins.add(p.toString());
        }
        String inherited = System.getenv("PORTER_PLUGINS");
        if (inherited != null && !inherited.isBlank()) {
            plugins.add(inherited);
        }
        if (!plugins.isEmpty()) {
            env.put("PORTER_PLUGINS", String.join(java.io.File.pathSeparator, plugins));
        }
        return env;
    }

    /**
     * {@code patches:} unified diffs applied after a stage ({@code after}); {@code base} is the root of the
     * {@code ---} paths (default: the target), {@code target} the root of the {@code +++} paths (default: the project).
     */
    private boolean applyPatches(String stage, Map<String, Object> summary) {
        Map<String, Object> done = new LinkedHashMap<>();
        for (JsonNode p : cfg.section("patches")) {
            if (!stage.equals(p.path("after").asString())) {
                continue;
            }
            Path target = p.has("target") ? cfg.path(p.get("target").asString()) : cfg.project();
            Path base = p.has("base") ? cfg.path(p.get("base").asString()) : target;
            String name = p.path("name").asString(p.path("patches").asString());
            dev.porter.port.patch.PatchSet.Applied a = dev.porter.port.patch.PatchSet.apply(cfg.path(p.path("patches").asString()), base, target);
            done.put(name, a.written().size() + a.deleted().size());
        }
        if (!done.isEmpty()) {
            summary.put("patches", done);
        }
        return true;
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Overlays every file of {@code from} onto {@code to}; returns the relative paths written. */
    static List<String> overlay(Path from, Path to) {
        List<String> res = new ArrayList<>();
        if (!Files.isDirectory(from)) {
            return res;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                copy(p, to.resolve(rel));
                res.add(rel);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return res;
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.mismatch(a, b) == -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Relocation> relocations() {
        return PortConfig.strings(cfg.section("rewrite").path("relocate")).stream().map(Relocation::parse).toList();
    }

    String shimPackage() {
        return cfg.section("rewrite").path("shimPackage").asString("dev.porter.shim");
    }

    SrgMap srgMap() {
        JsonNode in = cfg.section("install");
        return in.has("srgMappings") ? SrgMap.fromTiny(cfg.path(in.get("srgMappings").asString())) : SrgMap.empty();
    }

    // ----------------------------------------------------------------------------------------------- generate

    /**
     * Merges a generated {@code .lang} file into the project's under {@code # --- porter:<unit> ---} ...
     * {@code # --- end porter:<unit> ---}: the section is replaced in place (appended when missing); keys the file
     * defines outside the section are not repeated, so hand-written entries win.
     */
    static void mergeLangSection(Path generated, Path project, String unit) {
        String mark = "# --- porter:" + unit + " ---";
        String end = "# --- end porter:" + unit + " ---";
        String host = Files.exists(project) ? Text.read(project) : "";
        int a = host.indexOf(mark + "\n");
        int b = a < 0 ? -1 : host.indexOf(end, a);
        String before = a < 0 ? host : host.substring(0, a);
        String after = "";
        if (b >= 0) {
            int nl = host.indexOf('\n', b);
            after = nl < 0 ? "" : host.substring(nl + 1);
        }
        Set<String> keys = new HashSet<>();
        for (String l : (before + after).split("\n")) {
            if (l.contains("=") && !l.startsWith("#")) {
                keys.add(l.substring(0, l.indexOf('=')));
            }
        }
        List<String> lines = new ArrayList<>();
        for (String l : Text.read(generated).split("\n")) {
            if (!l.isEmpty() && !l.startsWith("#") && !keys.contains(l.contains("=") ? l.substring(0, l.indexOf('=')) : l)) {
                lines.add(l);
            }
        }
        if (!before.isEmpty() && !before.endsWith("\n")) {
            before += "\n";
        }
        Text.write(project, before + mark + "\n" + String.join("\n", lines) + "\n" + end + "\n" + after);
    }

    private boolean generate(Map<String, Object> summary) {
        JsonNode g = cfg.section("generate");
        Set<String> preserve = new HashSet<>(PortConfig.strings(g.path("preserve")));
        boolean langSections = g.path("langSections").asBoolean(false);
        Map<String, Object> units = new LinkedHashMap<>();
        boolean ok = true;
        for (JsonNode u : g.path("units")) {
            String name = u.path("name").asString();
            Path dir = work.resolve("gen/" + name);
            Path prev = work.resolve("gen/" + name + ".prev");
            deleteTree(prev);
            if (Files.exists(dir)) {
                try {
                    Files.move(dir, prev);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            List<String> args = new ArrayList<>(List.of("generate"));
            args.addAll(PortConfig.strings(u.path("args")));
            args.addAll(List.of("--out", dir.toString()));
            Proc.Result r = porter("generate-" + name, args, PortConfig.strings(u.path("jvmArgs")));
            if (!r.ok()) {
                units.put(name, Map.of("error", "porter generate exited " + r.exitCode() + " (" + r.log() + ")"));
                ok = false;
                continue;
            }
            Set<String> exclude = new HashSet<>(PortConfig.strings(u.path("exclude")));
            Map<String, List<String>> res = new LinkedHashMap<>();
            for (String k : List.of("installed", "updated", "unchanged", "kept", "excluded", "preserved")) {
                res.put(k, new ArrayList<>());
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Path f : files) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (rel.equals("porter-generate.json") || rel.startsWith("porter-rules/")) {
                    continue; // the run's manifest; rewrite rules for the logic (porter rewrite --rules), not project files
                }
                Path dst = cfg.project().resolve(rel);
                Path old = prev.resolve(rel);
                if (exclude.contains(rel)) {
                    res.get("excluded").add(rel);
                } else if (langSections && rel.endsWith(".lang")) {
                    mergeLangSection(f, dst, name);
                    res.computeIfAbsent("langMerged", k -> new ArrayList<>()).add(rel);
                } else if (preserve.contains(rel)) {
                    res.get("preserved").add(rel);
                } else if (!Files.exists(dst)) {
                    copy(f, dst);
                    res.get("installed").add(rel);
                } else if (sameContent(f, old) || sameContent(f, dst)) {
                    res.get("unchanged").add(rel);
                } else if (!Files.exists(old) || rel.endsWith(".lang") || Text.read(f).contains("porter:stub")) {
                    // no previous generation to compare with, a merged lang file, or a stub replaced by ported logic
                    res.get("kept").add(rel);
                } else {
                    copy(f, dst);
                    res.get("updated").add(rel);
                }
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            res.forEach((k, v) -> counts.put(k, v.size()));
            Path gj = dir.resolve("porter-generate.json");
            if (Files.exists(gj)) {
                List<Object> skipped = new ArrayList<>();
                for (JsonNode d : Json.json().readTree(Text.read(gj)).path("decisions")) {
                    if ("skip".equals(d.path("level").asString())) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("unit", name);
                        row.put("subject", d.path("subject").asString());
                        row.put("code", d.path("code").asString());
                        row.put("reason", d.path("message").asString());
                        skipped.add(row);
                    }
                }
                counts.put("notGenerated", skipped.size());
                Text.write(work.resolve("gen/" + name + ".not-generated.json"), Json.json().writeValueAsString(skipped) + "\n");
            }
            units.put(name, counts);
            Text.write(work.resolve("gen/" + name + ".install.json"), Json.json().writeValueAsString(res) + "\n");
        }
        summary.put("units", units);
        return ok;
    }

    // ------------------------------------------------------------------------------------------------ closure

    private boolean closure(Map<String, Object> summary) {
        JsonNode c = cfg.section("closure");
        Path source = cfg.path(c.path("source").asString());
        Closure.Spec spec = new Closure.Spec(PortConfig.strings(c.path("prefixes")), PortConfig.strings(c.path("seeds")),
                PortConfig.strings(c.path("include")), PortConfig.strings(c.path("cut")), PortConfig.strings(c.path("drop")),
                PortConfig.strings(c.path("stubExtra")), cfg.paths(c.path("known")));
        Closure.Result r = Closure.compute(new ClassIndex(source), spec);
        Path d = work.resolve("closure");
        Text.writeLines(d.resolve("port.txt"), r.port());
        Text.writeLines(d.resolve("frontier.txt"), r.frontier());
        Map<String, Object> rep = new LinkedHashMap<>(r.summary());
        rep.put("known", r.known());
        Text.write(d.resolve("closure.json"), Json.json().writeValueAsString(rep) + "\n");
        summary.putAll(r.summary());
        if (cfg.has("rewrite")) {
            JsonNode rw = cfg.section("rewrite");
            List<Path> files = new ArrayList<>();
            for (String k : r.port()) {
                files.add(source.resolve(k.replace('.', '/') + ".java"));
            }
            Coverage.Result cov = Coverage.measure(files, ToolCommands.loadRules(cfg.paths(rw.path("rules")), cfg.paths(rw.path("graph")),
                    cfg.from(), cfg.to()), srgMap(), List.of(), TypeFacts.bundledPlus(cfg.from(), List.of()));
            Text.write(d.resolve("coverage.json"), Json.json().writeValueAsString(cov.toJson(true)) + "\n");
            summary.put("coverage", cov.toJson(false));
        }
        return r.missingSeeds().isEmpty();
    }

    private List<String> portList() {
        return Text.words(work.resolve("closure/port.txt"));
    }

    private List<String> frontierList() {
        Path f = work.resolve("closure/frontier.txt");
        return Files.exists(f) ? Text.words(f) : List.of();
    }

    // ------------------------------------------------------------------------------------------------ upfront

    /** Output directory of the upfront stage ({@code porter upfront --out}). */
    private Path upfrontDir() {
        return work.resolve("upfront");
    }

    private boolean upfront(Map<String, Object> summary) {
        JsonNode u = cfg.section("upfront");
        Path o = upfrontDir();
        List<String> args = new ArrayList<>(List.of("upfront", "--jar", cfg.path(u.path("jar").asString()).toString(),
                "--out", o.toString(), "--classes", work.resolve("closure/port.txt").toString()));
        if (cfg.has("closure")) {
            args.addAll(List.of("--src", cfg.path(cfg.section("closure").path("source").asString()).toString()));
        }
        if (cfg.has("rewrite")) {
            JsonNode r = cfg.section("rewrite");
            for (Path p : cfg.paths(r.path("rules"))) {
                args.addAll(List.of("--rules", p.toString()));
            }
            List<Path> graph = cfg.paths(r.path("graph"));
            if (!graph.isEmpty()) {
                args.addAll(List.of("--graph", graph.get(0).toString()));
            }
        }
        for (String k : List.of("data", "tables", "concepts", "bodies", "sounds")) {
            if (u.has(k)) {
                args.addAll(List.of("--" + k, cfg.path(u.get(k).asString()).toString()));
            }
        }
        if (u.has("workers")) {
            args.addAll(List.of("--workers", Integer.toString(u.get("workers").asInt())));
        }
        args.addAll(PortConfig.strings(u.path("args")));
        Proc.Result res = porter("upfront", args, PortConfig.strings(u.path("jvmArgs")));
        summary.put("exitCode", res.exitCode());
        Path rep = o.resolve("upfront-report.json");
        if (res.ok() && Files.exists(rep)) {
            JsonNode inv = Json.json().readTree(Text.read(rep)).path("inventory");
            int open = 0;
            int total = 0;
            for (Map.Entry<String, JsonNode> e : inv.properties()) {
                total += e.getValue().asInt();
                if (e.getKey().startsWith("todo") || e.getKey().startsWith("registry") || e.getKey().startsWith("emitter skipped")) {
                    open += e.getValue().asInt();
                }
            }
            summary.put("symbols", total);
            summary.put("open", open);
        }
        return res.ok();
    }

    /**
     * Copies the shim sources of {@code porter upfront} ({@code <work>/upfront/gen/src}, package {@code dev.porter.shim.gen})
     * into the stage, relocated to the shim package like the adapter pack.
     */
    private int installGeneratedShims(Path stage) {
        Path gen = upfrontDir().resolve("gen/src");
        if (!Files.isDirectory(gen)) {
            return 0;
        }
        String base = "dev.porter.shim";
        String pkg = shimPackage();
        int n = 0;
        try (Stream<Path> w = Files.walk(gen)) {
            for (Path f : w.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = gen.relativize(f).toString().replace('\\', '/');
                if (!rel.startsWith(base.replace('.', '/') + "/")) {
                    continue;
                }
                String text = Text.read(f).replace(base + ".", pkg + ".").replace("package " + base + ";", "package " + pkg + ";");
                Text.write(stage.resolve(pkg.replace('.', '/') + rel.substring(base.length())), text);
                n++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ rewrite

    private boolean rewrite(Map<String, Object> summary) {
        JsonNode r = cfg.section("rewrite");
        Path rw = work.resolve("rw");
        deleteTree(rw);
        List<String> classes = new ArrayList<>(portList());
        classes.addAll(frontierList());
        Proc.Result res = porter("rewrite", rewriteArgs(classes, rw), PortConfig.strings(r.path("jvmArgs")));
        summary.put("exitCode", res.exitCode());
        Path rep = rw.resolve("porter-rewrite.json");
        if (Files.exists(rep)) {
            summary.put("totals", Json.json().readTree(Text.read(rep)).path("totals"));
        }
        if (res.ok()) {
            Precheck.recordRules(cfg, work); // what the rules looked like when this output was made (porter precheck diffs against it)
        }
        return res.ok();
    }

    /** Arguments of {@code porter rewrite} for the given classes (dotted names) into {@code out}; the stage and precheck share them. */
    List<String> rewriteArgs(List<String> classes, Path out) {
        return rewriteArgs(classes, out, cfg.path(cfg.section("closure").path("source").asString()), null);
    }

    /** As above with another source root and (precheck) the list of all classes of the port, which relocate although not rewritten. */
    List<String> rewriteArgs(List<String> classes, Path out, Path src, Path relocateKnown) {
        JsonNode r = cfg.section("rewrite");
        List<String> args = new ArrayList<>(List.of("rewrite", "--src", src.toString()));
        if (relocateKnown != null) {
            args.addAll(List.of("--relocate-known", relocateKnown.toString()));
        }
        for (String c : classes) {
            args.addAll(List.of("--include", c.replace('.', '/') + ".java"));
        }
        args.addAll(List.of("--from", cfg.from(), "--to", cfg.to()));
        for (String p : PortConfig.strings(r.path("platform"))) {
            args.addAll(List.of("--platform", p));
        }
        for (Path p : cfg.paths(r.path("rules"))) {
            args.addAll(List.of("--rules", p.toString()));
        }
        if (cfg.has("upfront") && cfg.section("upfront").path("emitRules").asBoolean(true) && Files.isDirectory(upfrontDir().resolve("rules"))) {
            args.addAll(List.of("--rules", upfrontDir().resolve("rules").toString())); // generated rules last: the hand rules win
        }
        for (Path p : cfg.globs(r.path("classpath"))) {
            args.addAll(List.of("--classpath", p.toString()));
        }
        for (Path p : cfg.paths(r.path("graph"))) {
            args.addAll(List.of("--graph", p.toString()));
        }
        for (String p : PortConfig.strings(r.path("relocate"))) {
            args.addAll(List.of("--relocate", p));
        }
        if (r.has("shimPackage")) {
            args.addAll(List.of("--shim-package", shimPackage()));
        }
        args.addAll(PortConfig.strings(r.path("args")));
        args.addAll(List.of("--out", out.toString()));
        return args;
    }

    // ------------------------------------------------------------------------------------------------ install

    /** Class names valid on the target (type oracle of the hub stubs): every class of the compile classpath. */
    private Set<String> oracle() {
        Set<String> s = new TreeSet<>();
        for (Path p : cfg.classpath()) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                        String rel = p.relativize(f).toString().replace('\\', '/');
                        s.add(rel.substring(0, rel.length() - 6).replace('/', '.').replace('$', '.'));
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(p)) {
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                    z.stream().map(java.util.zip.ZipEntry::getName)
                            .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF"))
                            .forEach(n -> s.add(n.substring(0, n.length() - 6).replace('/', '.').replace('$', '.')));
                } catch (IOException e) {
                    // not a zip (natives, broken jar): no classes
                }
            }
        }
        return s;
    }

    /**
     * The install passes over {@code stage}. {@code subset}: the stage holds only some of the classes (precheck), so
     * edits aimed at files that are not in it are skipped.
     */
    Map<String, Object> applyPasses(Path stage, JsonNode in, boolean subset) {
        List<String> passes = in.has("passes") ? PortConfig.strings(in.get("passes")) : List.of("srgmoj", "java8", "tector", "edits", "imports");
        Map<String, Object> pc = new LinkedHashMap<>();
        for (String p : passes) {
            switch (p) {
                case "srgmoj" -> {
                    if (in.has("srgMappings")) {
                        pc.put(p, SourcePasses.srgToMojmap(stage, srgMap()));
                    }
                }
                case "java8" -> pc.put(p, SourcePasses.java8(stage));
                case "tector" -> pc.put(p, SourcePasses.tileConstructors(stage));
                case "edits" -> {
                    if (in.has("edits")) {
                        pc.put(p, SourcePasses.edits(stage, subset ? onlyPresent(stage, SourcePasses.readEdits(cfg.path(in.get("edits").asString()))) : SourcePasses.readEdits(cfg.path(in.get("edits").asString()))));
                    }
                }
                case "imports" -> pc.put(p, SourcePasses.unusedImports(stage));
                case "assetns" -> {
                    List<SourcePasses.AssetNamespace> moves = new ArrayList<>();
                    for (JsonNode m : in.path("assetNamespaces")) {
                        moves.add(new SourcePasses.AssetNamespace(m.path("from").asString(), m.path("to").asString(), PortConfig.strings(m.path("paths"))));
                    }
                    pc.put(p, SourcePasses.assetNamespaces(stage, moves));
                }
                default -> throw new IllegalArgumentException("unknown pass '" + p + "' (srgmoj, java8, tector, edits, assetns, imports)");
            }
        }
        return pc;
    }

    private static List<SourcePasses.SourceEdit> onlyPresent(Path stage, List<SourcePasses.SourceEdit> edits) {
        return edits.stream().filter(e -> Files.exists(stage.resolve(e.file()))).toList();
    }

    private boolean install(Map<String, Object> summary) {
        JsonNode in = cfg.section("install");
        List<Relocation> rel = relocations();
        Path rw = work.resolve("rw");
        Path stage = work.resolve("stage");
        deleteTree(stage);
        List<String> port = portList();
        List<String> frontier = frontierList();
        for (String c : port) {
            Path src = rw.resolve(Relocation.path(rel, c));
            if (!Files.exists(src)) {
                throw new IllegalStateException("rewriter output missing for " + c + ": " + src);
            }
            copy(src, stage.resolve(Relocation.path(rel, c)));
        }
        summary.put("closureClasses", port.size());
        JsonNode stubs = in.path("stubs");
        // the mod's own namespace on the target side: relocation target, else the closure's first prefix
        List<String> modPrefixes = PortConfig.strings(cfg.section("closure").path("prefixes"));
        String base = !rel.isEmpty() ? rel.get(0).to() : modPrefixes.isEmpty() ? "" : modPrefixes.get(0);
        String defOwn = base.indexOf('.') < 0 ? base : base.substring(0, base.indexOf('.') + 1);
        String ownPrefix = stubs.path("ownPrefix").asString(defOwn);
        List<String> unported = PortConfig.strings(stubs.path("unportedPrefixes"));
        if (unported.isEmpty()) {
            // relocated top-level packages of the closure: types there that are neither ported nor stubbed are invalid
            Set<String> tops = new TreeSet<>();
            for (String c : port) {
                for (String p : modPrefixes) {
                    if (c.startsWith(p)) {
                        String rest = c.substring(p.length());
                        tops.add(Relocation.apply(rel, p) + (rest.contains(".") ? rest.substring(0, rest.indexOf('.') + 1) : ""));
                    }
                }
            }
            unported = List.copyOf(tops);
        }
        HubStubs.Options so = new HubStubs.Options(rel, ownPrefix, unported, stubs.path("header").asString(HubStubs.DEFAULT_HEADER),
                new java.util.HashSet<>(PortConfig.strings(stubs.path("keep"))));
        HubStubs hs = new HubStubs(rw, port, frontier, oracle(), so);
        Path overrides = stubs.has("overrides") ? cfg.path(stubs.get("overrides").asString()) : null;
        summary.put("hubs", hs.write(rw, frontier, stage, overrides));
        String shimPath = shimPackage().replace('.', '/');
        if (Files.isDirectory(rw.resolve(shimPath))) {
            summary.put("shim", overlay(rw.resolve(shimPath), stage.resolve(shimPath)).size());
        }
        Map<String, Object> pc = applyPasses(stage, in, false);
        summary.put("passes", pc);
        if (in.path("adapters").asString("none").equals("bundled")) {
            summary.put("adapters", Adapters.write(stage, shimPackage()));
        }
        if (cfg.has("upfront") && cfg.section("upfront").path("shimgen").asBoolean(true)) {
            summary.put("upfrontShims", installGeneratedShims(stage));
        }
        int ov = 0;
        for (Path d : cfg.paths(in.path("overlays"))) {
            ov += overlay(d, stage).size();
        }
        summary.put("overlayFiles", ov);
        // calls from kept code into inert hub-stub members: silent feature loss, listed in the report
        List<InertHubCalls.Hit> inertCalls = InertHubCalls.scan(stage);
        Text.write(work.resolve("inert-hub-calls.json"), Json.json().writeValueAsString(
                inertCalls.stream().map(InertHubCalls.Hit::toMap).toList()) + "\n");
        summary.put("inertHubCalls", inertCalls.size());
        if (!inertCalls.isEmpty()) {
            out.println("   WARNING: " + inertCalls.size() + " call site(s) of inert hub-stub members (feature not ported):");
            for (String l : InertHubCalls.summary(inertCalls)) {
                out.println("     " + l);
            }
        }
        // replace the previous install in the project
        Path src = cfg.sources();
        Path manifest = work.resolve("installed.txt");
        if (Files.exists(manifest)) {
            for (String f : Text.words(manifest)) {
                try {
                    Files.deleteIfExists(src.resolve(f));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        List<String> installed = new ArrayList<>(new TreeSet<>(overlay(stage, src)));
        Text.writeLines(manifest, installed);
        summary.put("installed", installed.size());
        Precheck.recordFiles(cfg, work);
        if (in.has("langKeys")) {
            JsonNode lk = in.get("langKeys");
            Map<String, String> locales = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : lk.path("locales").properties()) {
                locales.put(e.getKey(), e.getValue().asString());
            }
            summary.put("langKeys", dev.porter.port.passes.LangKeys.merge(cfg.project().resolve(lk.path("scan").asString("src/main/java")),
                    java.util.regex.Pattern.compile(lk.path("pattern").asString()), cfg.path(lk.path("source").asString()),
                    cfg.project().resolve(lk.path("target").asString()), locales, lk.path("fallback").asString("en_us"),
                    lk.path("marker").asString("porter:lang-keys")));
        }
        return true;
    }

    // --------------------------------------------------------------------------------------------------- loop

    private Path generatedSources() {
        return generatedSources(work.resolve("gen-src"));
    }

    Path generatedSources(Path d) {
        deleteTree(d);
        for (JsonNode g : cfg.section("compile").path("generatedSources")) {
            Text.write(d.resolve(g.path("path").asString()), g.path("content").asString());
        }
        return d;
    }

    private boolean loop(Map<String, Object> summary) {
        return loopOn("loop", cfg.project(), cfg.sources(), work.resolve("loop"), work.resolve("gen-src"), List.of(), List.of(), summary);
    }

    /**
     * The loop stage on {@code project} (sources in {@code sources}). The precheck runs it on a scratch project with
     * {@code extraCp} / {@code extraJavacOpts} (class output and source path of the real project).
     */
    boolean loopOn(String step, Path project, Path sources, Path loopWork, Path genDir, List<Path> extraCp,
            List<String> extraJavacOpts, Map<String, Object> summary) {
        JsonNode l = cfg.section("loop");
        List<String> args = new ArrayList<>(List.of("loop", "--project", project.toString(), "--from", cfg.from(), "--to", cfg.to(),
                "--work", loopWork.toString()));
        if (l.path("useClasspath").asBoolean(false)) {
            args.addAll(List.of("--src", sources.toString()));
            Path gen = generatedSources(genDir);
            if (Files.exists(gen)) {
                args.addAll(List.of("--src", gen.toString()));
            }
            for (Path p : cfg.classpath()) {
                args.addAll(List.of("--classpath", p.toString()));
            }
            for (Path p : extraCp) {
                args.addAll(List.of("--classpath", p.toString()));
            }
        }
        args.addAll(PortConfig.strings(l.path("args")));
        for (String o : extraJavacOpts) {
            args.add("--javac-opt=" + o);
        }
        Proc.Result r = porter(step, args, PortConfig.strings(l.path("jvmArgs")));
        summary.put("exitCode", r.exitCode());
        // the loop's shim-aware rules write dev.porter.shim references: relocate them like the rewriter does
        String pkg = shimPackage();
        int relocated = 0;
        if (!pkg.equals("dev.porter.shim")) {
            List<Relocation> rel = relocations();
            Path root = rel.isEmpty() ? sources : sources.resolve(rel.get(0).to().replace('.', '/'));
            for (Path p : Text.javaFiles(root)) {
                String s = Text.read(p);
                if (s.contains("dev.porter.shim")) {
                    Text.write(p, s.replace("dev.porter.shim.", pkg + "."));
                    relocated++;
                }
            }
        }
        summary.put("shimRelocatedFiles", relocated);
        // porter loop exits 1 when errors remain; the compile stage is the judge
        return r.exitCode() == 0 || r.exitCode() == 1;
    }

    // ------------------------------------------------------------------------------------------------ compile

    private Path javac() {
        JsonNode c = cfg.section("compile");
        Path home = c.has("javaHome") ? cfg.path(c.get("javaHome").asString()) : Path.of(System.getProperty("java.home"));
        return home.resolve("bin").resolve(Proc.isWindows() ? "javac.exe" : "javac");
    }

    /** One javac run over the project; returns the log text. */
    private String javacOnce(int round) {
        return javacOnce(round, Target.full(this));
    }

    /**
     * Where a javac run reads and writes: the whole project (the compile stage) or the scratch project of the precheck.
     * {@code extraArgs}: e.g. {@code -sourcepath}; {@code extraCp}: class dirs of the last build.
     */
    record Target(Path project, Path sources, Path classes, Path logs, Path gen, String tag, List<String> extraArgs, List<Path> extraCp) {
        static Target full(PortRunner r) {
            return new Target(r.cfg.project(), r.cfg.sources(), r.work.resolve("classes"), r.work, r.work.resolve("gen-src"), "", List.of(), List.of());
        }
    }

    String javacOnce(int round, Target t) {
        JsonNode c = cfg.section("compile");
        Path project = t.project();
        Path gen = generatedSources(t.gen());
        List<String> files = new ArrayList<>();
        for (Path p : Text.javaFiles(t.sources())) {
            files.add(project.relativize(p).toString().replace('\\', '/'));
        }
        for (Path p : Text.javaFiles(gen)) {
            files.add(p.toString());
        }
        Path classes = t.classes();
        deleteTree(classes);
        List<String> args = new ArrayList<>(List.of("-Xlint:-options", "-encoding", "UTF-8", "-nowarn", "-Xmaxerrs",
                c.path("maxErrors").asString("100000")));
        args.addAll(PortConfig.strings(c.path("options")));
        List<Path> pp = cfg.globs(c.path("processorpath"));
        if (!pp.isEmpty()) {
            args.addAll(List.of("-processorpath", String.join(java.io.File.pathSeparator, pp.stream().map(Path::toString).toList())));
        }
        args.add("-proc:none");
        List<Path> cp = new ArrayList<>(cfg.classpath());
        cp.addAll(t.extraCp());
        args.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList())));
        args.addAll(t.extraArgs());
        args.addAll(List.of("-d", classes.toString()));
        args.addAll(files);
        List<String> cmd = new ArrayList<>();
        cmd.add(javac().toString());
        for (String j : PortConfig.strings(c.path("jvmArgs"))) {
            cmd.add("-J" + j);
        }
        cmd.add(Proc.argFile(work.resolve("args/" + t.tag() + "javac.args"), args));
        Path log = work.resolve("logs/" + t.tag() + "compile-" + round + ".log");
        Proc.Result res = Proc.run(Proc.queued(cfg.queue(), cmd, work.resolve("args/" + t.tag() + "javac.cmd")), project, Proc.classpathEnv(), log, null);
        String text = Text.read(log);
        if (res.exitCode() != 0 && JavacLog.errorLines(text) == 0) {
            throw new IllegalStateException("javac did not run (exit " + res.exitCode() + "), see " + log);
        }
        Text.write(t.logs().resolve(t.tag() + "compile.log"), text); // the last round's log is the stage result
        return text;
    }

    /** Upper bound of the error-driven fix rounds; the loop also stops when a round leaves javac's errors unchanged. */
    static final int CASTFIX_ROUNDS = 10;

    /**
     * True when another castfix round is worth running: the last round changed the set of javac errors. (The count may
     * stay or even rise: javac reports flow errors such as definite assignment only after the attribution errors are gone.)
     */
    static boolean castfixProgress(String beforeLog, String afterLog) {
        return !errorKeys(beforeLog).equals(errorKeys(afterLog));
    }

    private static java.util.Set<String> errorKeys(String log) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JavacLog.Error e : JavacLog.parse(log)) {
            keys.add(e.file() + ":" + e.line() + ":" + e.message());
        }
        return keys;
    }

    private boolean compile(Map<String, Object> summary) {
        return compileOn(Target.full(this), work.resolve("symbols.json"), summary) != null;
    }

    /** javac + castfix rounds on {@code t}; returns the final log when it compiles, null otherwise (errors in {@code summary}). */
    String compileOn(Target t, Path symbolsFile, Map<String, Object> summary) {
        JsonNode c = cfg.section("compile");
        int rounds = c.path("castfixRounds").asInt(CASTFIX_ROUNDS);
        String log = javacOnce(0, t);
        List<Integer> fixes = new ArrayList<>();
        for (int k = 1; k <= rounds; k++) {
            int n = CastFix.apply(log, t.project());
            fixes.add(n);
            if (n == 0) {
                break;
            }
            String next = javacOnce(k, t);
            boolean progress = castfixProgress(log, next);
            log = next;
            if (!progress) {
                break; // fixes were applied but javac reports exactly the same errors: further rounds would only cycle
            }
        }
        summary.put("castfix", fixes);
        Symbols.Result r = Symbols.aggregate(JavacLog.parse(log), srgMap(), cfg.fromBare());
        Text.write(symbolsFile, Json.json().writeValueAsString(r.toJson()) + "\n");
        Text.write(symbolsFile.resolveSibling(symbolsFile.getFileName().toString().replace(".json", "") + "-classes.tsv"), ErrorClasses.tsv(r));
        summary.put("errors", r.errors());
        summary.put("distinctSymbols", r.distinct());
        detail("   errors " + r.errors() + ", distinct symbols " + r.distinct() + " -> " + symbolsFile);
        for (Symbols.Entry e : r.symbols().subList(0, Math.min(15, r.symbols().size()))) {
            detail(String.format("   %5d  %s", e.count(), e.key()));
        }
        lastErrors = r;
        return r.errors() == 0 ? log : null;
    }

    /** Aggregated errors of the last {@link #compileOn} (null before the first). */
    Symbols.Result lastErrors;

    // -------------------------------------------------------------------------------------------------- build

    /**
     * "./gradlew ..." on Windows: Windows cannot execute the shell script (and "bash" may resolve to WSL), so the
     * project's gradlew.bat is used when it exists; the same config then works on every OS.
     */
    static List<String> windowsWrapper(List<String> command, java.nio.file.Path project) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows && !command.isEmpty() && (command.get(0).equals("./gradlew") || command.get(0).equals("gradlew"))
                && Files.exists(project.resolve("gradlew.bat"))) {
            List<String> c = new ArrayList<>(command);
            c.set(0, project.resolve("gradlew.bat").toString());
            return c;
        }
        return command;
    }

    private boolean build(Map<String, Object> summary) {
        JsonNode b = cfg.section("build");
        List<String> cmd = new ArrayList<>(cfg.queue());
        cmd.addAll(windowsWrapper(PortConfig.strings(b.path("command")), cfg.project()));
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : b.path("env").properties()) {
            env.put(e.getKey(), e.getValue().asString());
        }
        detail("   " + String.join(" ", PortConfig.strings(b.path("command"))) + " (log: " + work.resolve("logs/build.log") + ")");
        Proc.Result r = Proc.run(cmd, cfg.project(), env, work.resolve("logs/build.log"), null);
        summary.put("exitCode", r.exitCode());
        if (!r.ok()) {
            return false;
        }
        if (b.has("jar")) {
            Path jar = cfg.project().resolve(b.get("jar").asString());
            Path dst = work.resolve("out").resolve(jar.getFileName());
            copy(jar, dst);
            summary.put("jar", dst.toString());
            if (b.has("obfcheckPrefix")) {
                List<String> bad = ObfCheck.check(jar, b.get("obfcheckPrefix").asString());
                Text.writeLines(work.resolve("obfcheck.txt"), bad);
                summary.put("obfcheckProblems", bad.size());
                return bad.isEmpty();
            }
        }
        return true;
    }

    // --------------------------------------------------------------------------------------------------- test

    private boolean test(Map<String, Object> summary) {
        boolean ok = true;
        Map<String, Object> runs = new LinkedHashMap<>();
        for (JsonNode t : cfg.section("test").path("runs")) {
            String name = t.path("name").asString();
            List<String> queue = t.has("queue") ? PortConfig.strings(t.get("queue")) : cfg.queue();
            List<String> cmd = Proc.porter(queue, PortConfig.strings(t.path("jvmArgs")), work.resolve("reports"),
                    work.resolve("args/test-" + name + ".args"), PortConfig.strings(t.path("args")));
            detail("   test " + name + " (log: " + work.resolve("logs/test-" + name + ".log") + ")");
            Proc.Result r = Proc.run(cmd, cfg.dir(), childEnv(), work.resolve("logs/test-" + name + ".log"), null);
            runs.put(name, r.exitCode());
            ok &= r.ok();
        }
        summary.put("runs", runs);
        return ok;
    }
}
