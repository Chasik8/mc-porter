package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.json.Json;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.port.closure.ClassIndex;
import dev.porter.port.closure.Closure;
import dev.porter.port.jar.ObfCheck;
import dev.porter.port.javac.CastFix;
import dev.porter.port.javac.JavacLog;
import dev.porter.port.javac.Symbols;
import dev.porter.port.passes.HubStubs;
import dev.porter.port.passes.SourcePasses;
import dev.porter.port.passes.SrgMap;
import dev.porter.rewrite.engine.TypeFacts;
import dev.porter.rewrite.graph.GraphRuleGenerator;
import dev.porter.rewrite.graph.SymbolGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The building blocks of {@code porter port}, usable on their own. */
public final class ToolCommands {
    private ToolCommands() {
    }

    abstract static class Base implements PorterSubcommand, Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        RunReport report() {
            return new RunReport(path(), spec.commandLine().getParseResult().originalArgs());
        }

        static SrgMap srg(Path tiny) {
            return tiny == null ? SrgMap.empty() : SrgMap.fromTiny(tiny);
        }
    }

    /** {@code porter closure}. */
    @Command(name = "closure", description = {"Dependency closure of seed classes: what to copy, which hubs to stub, which known classes are identical on the target side.",
            "Unknown classes are copied recursively; known and identical ones are left alone; known and different ones are copied and resolved per distinct symbol."})
    public static final class ClosureCmd extends Base {
        @Option(names = "--src", required = true, paramLabel = "<dir>", description = "Source-version sources of the mod.")
        Path src;
        @Option(names = "--prefix", required = true, paramLabel = "<pkg.>", description = "Package prefix of the mod (repeatable).")
        List<String> prefixes;
        @Option(names = "--seed", required = true, paramLabel = "<fqn>", description = "Seed class (repeatable).")
        List<String> seeds;
        @Option(names = "--include", paramLabel = "<fqn>", description = "Extra class to copy (repeatable).")
        List<String> include = new ArrayList<>();
        @Option(names = "--cut", paramLabel = "<prefix>", description = "Hub prefix: stop and stub (repeatable).")
        List<String> cut = new ArrayList<>();
        @Option(names = "--drop", paramLabel = "<prefix>", description = "Stop, do not stub (repeatable).")
        List<String> drop = new ArrayList<>();
        @Option(names = "--stub-extra", paramLabel = "<fqn>", description = "Extra class to stub (repeatable).")
        List<String> stubExtra = new ArrayList<>();
        @Option(names = "--known", paramLabel = "<dir>", description = "Target-side source root for the identical-API check (repeatable).")
        List<Path> known = new ArrayList<>();
        @Option(names = "--out", paramLabel = "<dir>", description = "Write port.txt, frontier.txt, closure.json here.")
        Path out;

        @Override
        public String path() {
            return "closure";
        }

        @Override
        public Integer call() {
            Closure.Result r = Closure.compute(new ClassIndex(src), new Closure.Spec(prefixes, seeds, include, cut, drop, stubExtra, known));
            Map<String, Object> s = new LinkedHashMap<>(r.summary());
            if (out != null) {
                Text.writeLines(out.resolve("port.txt"), r.port());
                Text.writeLines(out.resolve("frontier.txt"), r.frontier());
                Map<String, Object> rep = new LinkedHashMap<>(s);
                rep.put("known", r.known());
                Text.write(out.resolve("closure.json"), Json.json().writeValueAsString(rep) + "\n");
            }
            System.out.println("closure: " + r.port().size() + " classes (" + r.portLines() + " lines), frontier " + r.frontier().size()
                    + ", known identical " + s.get("knownIdentical") + " / different " + s.get("knownDifferent"));
            r.missingSeeds().forEach(m -> System.out.println("missing seed: " + m));
            report().put("summary", s).write(r.missingSeeds().isEmpty() ? RunReport.STATUS_OK : RunReport.STATUS_FAILED,
                    r.missingSeeds().isEmpty() ? ExitCodes.OK : ExitCodes.FAILED);
            return r.missingSeeds().isEmpty() ? ExitCodes.OK : ExitCodes.FAILED;
        }
    }

    /** {@code porter stubs}. */
    @Command(name = "stubs", description = "Hub stubs: frontier classes reduced to the members the closure references.")
    public static final class StubsCmd extends Base {
        @Option(names = "--rw", required = true, paramLabel = "<dir>", description = "porter rewrite output of closure + frontier.")
        Path rw;
        @Option(names = "--port", required = true, paramLabel = "<port.txt>")
        Path port;
        @Option(names = "--frontier", required = true, paramLabel = "<frontier.txt>")
        Path frontier;
        @Option(names = "--relocate", paramLabel = "<from>=<to>", description = "As given to porter rewrite (repeatable).")
        List<String> relocate = new ArrayList<>();
        @Option(names = "--own-prefix", required = true, paramLabel = "<pkg.>", description = "Package prefix of the target mod.")
        String ownPrefix;
        @Option(names = "--unported", paramLabel = "<pkg.>", description = "Relocated package that was not ported (repeatable).")
        List<String> unported = new ArrayList<>();
        @Option(names = "--oracle", paramLabel = "<classes.txt>", description = "Class names valid on the target (one per line).")
        Path oracle;
        @Option(names = "--overrides", paramLabel = "<dir>", description = "Hand-written hubs used instead of stubs.")
        Path overrides;
        @Option(names = "--out", required = true, paramLabel = "<dir>")
        Path out;

        @Override
        public String path() {
            return "stubs";
        }

        @Override
        public Integer call() {
            List<Relocation> rel = relocate.stream().map(Relocation::parse).toList();
            Set<String> valid = oracle == null ? Set.of() : new TreeSet<>(Text.words(oracle));
            List<String> fr = Text.words(frontier);
            HubStubs hs = new HubStubs(rw, Text.words(port), fr, valid, new HubStubs.Options(rel, ownPrefix, unported, HubStubs.DEFAULT_HEADER));
            Map<String, Integer> r = hs.write(rw, fr, out, overrides);
            System.out.println("stubs: " + r);
            report().put("summary", r).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    /** {@code porter passes}. */
    @Command(name = "passes", description = "Source passes on a tree: srgmoj, java8, tector, edits, imports (in the given order).")
    public static final class PassesCmd extends Base {
        @Parameters(index = "0", paramLabel = "<dir>", description = "Source tree (edited in place).")
        Path dir;
        @Option(names = "--pass", required = true, paramLabel = "<pass>", description = "srgmoj | java8 | tector | edits | imports (repeatable).")
        List<String> passes;
        @Option(names = "--srg-tiny", paramLabel = "<file>", description = "tiny v2 with mojmap+srg namespaces (srgmoj).")
        Path tiny;
        @Option(names = "--edits", paramLabel = "<edits.yaml>", description = "Counted call-site edits (edits).")
        Path edits;

        @Override
        public String path() {
            return "passes";
        }

        @Override
        public Integer call() {
            Map<String, Object> r = new LinkedHashMap<>();
            for (String p : passes) {
                switch (p) {
                    case "srgmoj" -> r.put(p, SourcePasses.srgToMojmap(dir, srg(tiny)));
                    case "java8" -> r.put(p, SourcePasses.java8(dir));
                    case "tector" -> r.put(p, SourcePasses.tileConstructors(dir));
                    case "edits" -> r.put(p, SourcePasses.edits(dir, SourcePasses.readEdits(edits)));
                    case "imports" -> r.put(p, SourcePasses.unusedImports(dir));
                    default -> {
                        System.err.println("porter passes: unknown pass " + p);
                        return ExitCodes.USAGE;
                    }
                }
            }
            System.out.println("passes: " + r);
            report().put("summary", r).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    /** {@code porter castfix}. */
    @Command(name = "castfix", description = "Error-driven fixes of decompiler cast/generic artefacts from a javac log.")
    public static final class CastFixCmd extends Base {
        @Parameters(index = "0", paramLabel = "<javac.log>")
        Path log;
        @Parameters(index = "1", paramLabel = "<root>", description = "Directory the log's file paths are relative to.")
        Path root;

        @Override
        public String path() {
            return "castfix";
        }

        @Override
        public Integer call() {
            int n = CastFix.apply(Text.read(log), root);
            System.out.println("castfix: " + n + " cast fixes");
            report().put("fixes", n).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    /** {@code porter symbols}. */
    @Command(name = "symbols", description = "Aggregate javac errors by distinct symbol (the unit of AI resolution).")
    public static final class SymbolsCmd extends Base {
        @Parameters(index = "0", paramLabel = "<javac.log>")
        Path log;
        @Option(names = "--srg-tiny", paramLabel = "<file>", description = "Map SRG ids to mojmap names (tiny v2, mojmap+srg).")
        Path tiny;
        @Option(names = "--version", defaultValue = "1.19.2", description = "Label of the source version (default ${DEFAULT-VALUE}).")
        String version;
        @Option(names = "--top", defaultValue = "80", description = "Rows to print (default ${DEFAULT-VALUE}).")
        int top;
        @Option(names = "--json", paramLabel = "<out.json>")
        Path json;

        @Override
        public String path() {
            return "symbols";
        }

        @Override
        public Integer call() {
            Symbols.Result r = Symbols.aggregate(JavacLog.parse(Text.read(log)), srg(tiny), version);
            System.out.println("errors " + r.errors() + "  distinct symbols " + r.distinct() + "  by kind " + r.byKind() + "  files " + r.files());
            for (Symbols.Entry e : r.symbols().subList(0, Math.min(top, r.symbols().size()))) {
                System.out.printf("%5d  %s%n", e.count(), e.key());
            }
            if (json != null) {
                Text.write(json, Json.json().writeValueAsString(r.toJson()) + "\n");
            }
            report().put("errors", r.errors()).put("distinct", r.distinct()).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    /** {@code porter obfcheck}. */
    @Command(name = "obfcheck", description = "Reobf collision check of a built jar (AbstractMethodError risks).")
    public static final class ObfCheckCmd extends Base {
        @Parameters(index = "0", paramLabel = "<jar>")
        Path jar;
        @Option(names = "--prefix", defaultValue = "", paramLabel = "<pkg/>", description = "Internal-name prefix of the classes to check.")
        String prefix;

        @Override
        public String path() {
            return "obfcheck";
        }

        @Override
        public Integer call() {
            List<String> bad = ObfCheck.check(jar, prefix);
            bad.forEach(System.out::println);
            System.out.println("obfcheck: " + bad.size() + " unimplemented interface methods");
            int code = bad.isEmpty() ? ExitCodes.OK : ExitCodes.FAILED;
            report().put("problems", bad).write(bad.isEmpty() ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            return code;
        }
    }

    /** {@code porter coverage}. */
    @Command(name = "coverage", description = {"Reuse metric: share of a mod's distinct platform API symbols that existing rules resolve before any AI work.",
            "Counts imported platform types and SRG-named members; --graph adds the mechanical rules porter rewrite generates from the symbol graph."})
    public static final class CoverageCmd extends Base {
        @Option(names = "--src", required = true, paramLabel = "<dir>", description = "Source-version sources.")
        Path src;
        @Option(names = "--classes", paramLabel = "<list.txt>", description = "Only these classes (e.g. a closure's port.txt + frontier.txt; repeatable).")
        List<Path> classes = new ArrayList<>();
        @Option(names = "--rules", paramLabel = "<dir|file>", description = "Rule files/directories (default ./rules; repeatable).")
        List<Path> rules = new ArrayList<>();
        @Option(names = "--graph", paramLabel = "<dir|file>", description = "Symbol-graph documents: adds the mechanical rules porter rewrite generates.")
        List<Path> graphs = new ArrayList<>();
        @Option(names = "--srg-tiny", paramLabel = "<file>", description = "Count SRG-named members (tiny v2, mojmap+srg).")
        Path tiny;
        @Option(names = "--platform", paramLabel = "<prefix>", description = "Extra platform package prefix (repeatable).")
        List<String> platform = new ArrayList<>();
        @Option(names = "--from", defaultValue = "forge-1.19.2")
        String from;
        @Option(names = "--to", defaultValue = "forge-1.7.10")
        String to;
        @Option(names = "--top", defaultValue = "30", description = "Unresolved symbols to print (default ${DEFAULT-VALUE}).")
        int top;
        @Option(names = "--json", paramLabel = "<out.json>")
        Path json;

        @Override
        public String path() {
            return "coverage";
        }

        @Override
        public Integer call() {
            List<Path> files = new ArrayList<>();
            if (classes.isEmpty()) {
                files.addAll(Text.javaFiles(src));
            } else {
                for (Path l : classes) {
                    for (String c : Text.words(l)) {
                        Path p = src.resolve(c.replace('.', '/') + ".java");
                        if (Files.exists(p)) {
                            files.add(p);
                        }
                    }
                }
            }
            Coverage.Result r = Coverage.measure(files, loadRules(rules, graphs, from, to), srg(tiny), platform,
                    TypeFacts.bundledPlus(from, List.of()));
            System.out.printf(java.util.Locale.ROOT, "coverage: %d distinct API symbols in %d files, %d resolved by existing rules (%.1f%%)%n",
                    r.total(), files.size(), r.resolved(), r.share() * 100);
            System.out.printf(java.util.Locale.ROOT, "  types %d/%d, members exact %d + probable %d of %d%n", r.typesResolved(), r.types(), r.membersExact(),
                    r.membersProbable(), r.members());
            r.unresolved().stream().limit(top).forEach(s -> System.out.printf("  %5d  %-10s %s%n", s.uses(), s.status(), s.key()));
            if (json != null) {
                Text.write(json, Json.json().writeValueAsString(r.toJson(true)) + "\n");
            }
            report().put("summary", r.toJson(false)).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    /** {@code porter drafts}: the "good first issue" flow. */
    @Command(name = "drafts", description = "Export the loop's draft rules as issue-ready markdown (good first issues).")
    public static final class DraftsCmd extends Base {
        @Parameters(arity = "1..*", paramLabel = "<dir|file>", description = "Draft rules (porter loop --proposed output).")
        List<Path> inputs;
        @Option(names = "--out", required = true, paramLabel = "<dir>", description = "One <rule-id>.md per draft + index.md.")
        Path out;

        @Override
        public String path() {
            return "drafts";
        }

        @Override
        public Integer call() {
            List<Rule> drafts = new ArrayList<>();
            for (Path p : inputs) {
                drafts.addAll(Files.isDirectory(p) ? RuleIo.readAll(p) : RuleIo.read(p));
            }
            StringBuilder index = new StringBuilder("# Draft rules ready for review\n\n| issue | symbol | from -> to |\n|---|---|---|\n");
            for (Rule r : drafts) {
                Rule.Match m = r.match();
                String symbol = m.call() != null ? m.call() : m.field() != null ? m.field() : m.newCall() != null ? m.newCall()
                        : m.type() != null ? m.type() : m.override() != null ? m.override() : m.pattern();
                StringBuilder md = new StringBuilder();
                md.append("---\ntitle: \"Rule: ").append(symbol.replace("\"", "'")).append(" (").append(r.from()).append(" -> ").append(r.to())
                        .append(")\"\nlabels: [good first issue, rules, ").append(r.from()).append("__").append(r.to()).append("]\n---\n\n");
                md.append("The compile loop fixed the same symbol the same way several times. Turn this draft into a tested rule.\n\n");
                md.append("- Draft id: `").append(r.id()).append("`\n");
                md.append("- Symbol: `").append(symbol).append("`\n");
                md.append("- Proposed replacement: `").append(r.replace()).append("`\n");
                md.append("- Why: ").append(r.description() == null ? "" : r.description().replace("\n", " ")).append("\n\n");
                md.append("Observed fixes (before -> after):\n\n");
                for (Rule.Test t : r.tests()) {
                    md.append("```java\n// before\n").append(t.before()).append("\n// after\n").append(t.after()).append("\n```\n\n");
                }
                md.append("To do:\n\n1. Check the target API exists (`porter graph query` or `javap`), never invent it.\n");
                md.append("2. Write the rule in `rules/composite/").append(bare(r.from())).append("__").append(bare(r.to()))
                        .append("/` with a symbol-level `match`, preconditions if needed, and at least one before/after test.\n");
                md.append("3. Replace mod-specific names in the tests with synthetic ones; no mod code in public rules.\n");
                md.append("4. `porter rewrite --from ").append(r.from()).append(" --to ").append(r.to())
                        .append(" --check-rules --rules rules/` and `scripts/check.sh` pass.\n");
                Text.write(out.resolve(r.id() + ".md"), md.toString());
                index.append("| [").append(r.id()).append("](").append(r.id()).append(".md) | `").append(symbol.replace("|", "\\|"))
                        .append("` | ").append(r.from()).append(" -> ").append(r.to()).append(" |\n");
            }
            Text.write(out.resolve("index.md"), index.toString());
            System.out.println("drafts: " + drafts.size() + " issue file(s) -> " + out);
            report().put("drafts", drafts.size()).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }

        private static String bare(String v) {
            return v.contains("-") ? v.substring(v.indexOf('-') + 1) : v;
        }
    }

    /** Hand rules of the version pair + (optional) mechanical rules generated from the symbol graph. */
    static List<Rule> loadRules(List<Path> ruleSources, List<Path> graphs, String from, String to) {
        String pf = from.contains("-") ? from : "forge-" + from;
        String pt = to.contains("-") ? to : "forge-" + to;
        List<Path> src = ruleSources.isEmpty() && Files.isDirectory(Path.of("rules")) ? List.of(Path.of("rules")) : ruleSources;
        List<Rule> out = new ArrayList<>();
        for (Path p : src) {
            for (Rule r : Files.isDirectory(p) ? RuleIo.readAll(p) : RuleIo.read(p)) {
                if (r.from().equals(pf) && r.to().equals(pt)) {
                    out.add(r);
                }
            }
        }
        if (!graphs.isEmpty()) {
            String bf = pf.substring(pf.indexOf('-') + 1);
            String bt = pt.substring(pt.indexOf('-') + 1);
            out.addAll(new GraphRuleGenerator(bf, bt, pf, pt).generate(SymbolGraph.load(graphs, bf)).rules());
        }
        return out;
    }
}
