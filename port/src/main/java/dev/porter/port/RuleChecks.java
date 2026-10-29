package dev.porter.port;

import dev.porter.core.contract.Contract;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import dev.porter.rewrite.engine.RuleBook;
import dev.porter.rewrite.engine.Selector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Static checks of the rule files a port loads, before any rewrite runs: schema, duplicate ids, rules the engine cannot
 * apply, and rules that would be ambiguous on the same site (same selector, same priority, different replacement,
 * nothing telling them apart). Every problem is {@code <class>: <detail>}; the classes are the ones the precheck counts
 * ({@code rules:schema}, {@code rules:duplicate-id}, {@code rules:unsupported}, {@code rules:conflict}).
 */
public final class RuleChecks {
    private RuleChecks() {
    }

    /** A rule and the file it came from. */
    public record Loaded(Rule rule, Path file) {
    }

    public record Result(List<Loaded> rules, List<String> problems) {
    }

    /** Reads every rule file under {@code sources} (dirs are walked), keeps those of the version pair; never throws on a bad file. */
    public static Result load(List<Path> sources, String from, String to) {
        List<Loaded> rules = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path file : ruleFiles(sources)) {
            try {
                for (Rule r : RuleIo.read(file)) {
                    if (r.from().equals(from) && r.to().equals(to)) {
                        rules.add(new Loaded(r, file));
                    }
                }
            } catch (RuntimeException e) {
                problems.add("rules:schema: " + file.toString().replace('\\', '/') + ": " + firstLine(e.getMessage()));
            }
        }
        Map<String, Path> seen = new LinkedHashMap<>();
        List<Loaded> unique = new ArrayList<>();
        for (Loaded l : rules) {
            Path prev = seen.putIfAbsent(l.rule().id(), l.file());
            if (prev != null) {
                problems.add("rules:duplicate-id: " + l.rule().id() + " in " + name(prev) + " and " + name(l.file()));
            } else {
                unique.add(l);
            }
        }
        try {
            RuleBook book = new RuleBook(unique.stream().map(Loaded::rule).toList(), RuleBook.SHIM_PACKAGE);
            for (RuleBook.Prepared p : book.unsupported()) {
                problems.add("rules:unsupported: " + p.id() + ": " + book.problem(p.id()));
            }
        } catch (RuntimeException e) {
            problems.add("rules:schema: " + firstLine(e.getMessage()));
        }
        problems.addAll(conflicts(unique));
        return new Result(unique, problems);
    }

    static List<Path> ruleFiles(List<Path> sources) {
        List<Path> out = new ArrayList<>();
        for (Path p : sources) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(Files::isRegularFile)
                            .filter(f -> Contract.bySuffix(f.getFileName().toString()).orElse(null) == Contract.RULE)
                            .sorted().forEach(out::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Same selector and priority, different replacement (placeholders compared by position), same preconditions: the
     * rewriter would leave the site as {@code TODO(porter:ambiguous)}. A call/override rule on {@code java.lang.Object}
     * competes with every owner of the same member (the fallback for an unproven receiver).
     */
    static List<String> conflicts(List<Loaded> rules) {
        Map<String, List<Loaded>> groups = new LinkedHashMap<>();
        for (Loaded l : rules) {
            Rule r = l.rule();
            if (r.match() == null || r.replace() == null) {
                continue;
            }
            Selector s;
            try {
                s = Selector.of(r.match());
            } catch (RuntimeException e) {
                continue; // reported as a schema/unsupported problem elsewhere
            }
            String key = switch (s.kind()) {
                case CALL, OVERRIDE -> s.kind() + " " + s.name() + s.params() + " p" + r.priorityOrDefault();
                case NEW, FIELD, TYPE -> s.kind() + " " + s.owner() + "#" + s.name() + s.params() + " p" + r.priorityOrDefault();
                default -> null; // patterns and declarations are matched structurally; not comparable by text
            };
            if (key != null) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Loaded>> g : groups.entrySet()) {
            List<Loaded> l = g.getValue();
            for (int a = 0; a < l.size(); a++) {
                for (int b = a + 1; b < l.size(); b++) {
                    Rule x = l.get(a).rule();
                    Rule y = l.get(b).rule();
                    Selector sx = Selector.of(x.match());
                    Selector sy = Selector.of(y.match());
                    boolean callLike = sx.kind() == Selector.Kind.CALL || sx.kind() == Selector.Kind.OVERRIDE;
                    boolean sameSite = !callLike || sx.owner().equals(sy.owner())
                            || "java.lang.Object".equals(sx.owner()) || "java.lang.Object".equals(sy.owner());
                    if (sameSite && !same(x, y) && java.util.Objects.equals(x.preconditions(), y.preconditions())) {
                        out.add("rules:conflict: " + x.id() + " vs " + y.id() + " (" + g.getKey() + ")");
                    }
                }
            }
        }
        return out;
    }

    private static boolean same(Rule x, Rule y) {
        RuleBook book = new RuleBook(List.of(x), RuleBook.SHIM_PACKAGE);
        RuleBook book2 = new RuleBook(List.of(y), RuleBook.SHIM_PACKAGE);
        RuleBook.Prepared px = book.all().get(0);
        RuleBook.Prepared py = book2.all().get(0);
        return RuleBook.canonicalReplace(px).equals(RuleBook.canonicalReplace(py))
                && java.util.Objects.equals(px.imports(), py.imports());
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    /** The exception text on one line (the contract violations are listed one per line). */
    private static String firstLine(String s) {
        if (s == null) {
            return "invalid";
        }
        return s.strip().replaceAll("[ \t]*\n[ \t]*", "; ");
    }
}
