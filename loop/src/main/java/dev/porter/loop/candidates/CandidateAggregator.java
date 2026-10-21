package dev.porter.loop.candidates;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.rule.Rule;
import dev.porter.core.rule.RuleIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Aggregates candidate logs by {@code normalized} and drafts rules for groups that reached the threshold
 * (count &gt;= 3 with the same normalized form). Candidates authored by {@code rule:<id>} are audit records
 * of rules that already exist and do not count towards a draft.
 */
public final class CandidateAggregator {
    public static final int DRAFT_THRESHOLD = 3;

    /** One aggregation group. */
    public record Group(String normalized, int count, int ruleCount, List<Candidate> candidates) {
        public boolean draftable(int threshold) {
            return count >= threshold;
        }
    }

    private CandidateAggregator() {
    }

    /** Reads every candidate in the given files / directories ({@code *.candidates.jsonl}, {@code *.candidate.json}), sorted by path. */
    public static List<Candidate> readAll(List<Path> paths) {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(Files::isRegularFile).filter(CandidateAggregator::isCandidateFile).sorted().forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(p)) {
                files.add(p);
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (Path f : files) {
            out.addAll(CandidateIo.read(f));
        }
        return out;
    }

    private static boolean isCandidateFile(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".candidates.jsonl") || n.endsWith(".candidate.json");
    }

    /** The candidate's key: its {@code normalized}, else computed by {@link Normalizer}. */
    public static String key(Candidate c) {
        return c.normalized() != null && !c.normalized().isBlank() ? c.normalized() : Normalizer.normalize(c.symbol(), c.before(), c.after());
    }

    /** Groups by key, sorted by key. Candidates with the same id (re-logged by a rerun) count once. */
    public static List<Group> aggregate(List<Candidate> candidates) {
        Map<String, List<Candidate>> by = new TreeMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Candidate c : candidates) {
            if (c.id() != null && !ids.add(c.id())) {
                continue;
            }
            by.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c);
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> e : by.entrySet()) {
            int count = 0;
            int ruleCount = 0;
            for (Candidate c : e.getValue()) {
                if (c.author().startsWith("rule:")) {
                    ruleCount += c.count();
                } else {
                    count += c.count();
                }
            }
            out.add(new Group(e.getKey(), count, ruleCount, List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Drafts a rule for every group with {@code count >= threshold}, writing {@code <dir>/<id>.rule.yaml} (validated).
     *
     * @return the drafted rules, in group order
     */
    public static List<Rule> writeDrafts(List<Group> groups, Path dir, int threshold, String defaultFrom, String defaultTo) {
        List<Rule> drafts = new ArrayList<>();
        for (Group g : groups) {
            if (!g.draftable(threshold)) {
                continue;
            }
            Rule r = draft(g, defaultFrom, defaultTo);
            if (r == null) {
                continue;
            }
            try {
                RuleIo.write(dir.resolve(r.id() + ".rule.yaml"), List.of(r));
                drafts.add(r);
            } catch (dev.porter.core.contract.ContractViolationException e) {
                // a draft that would violate the rule contract is not written (the group stays in the aggregation)
            }
        }
        return drafts;
    }

    private static final Pattern MEMBER = Pattern.compile("^([\\w$.]+)#([\\w$<>]+)\\((.*)\\)$");
    private static final Pattern PH = Pattern.compile("^\\$[vl]\\d+$");

    /** Builds a draft rule from a group, or null when the group has nothing usable (e.g. an empty after side). */
    public static Rule draft(Group g, String defaultFrom, String defaultTo) {
        List<Candidate> human = g.candidates().stream().filter(c -> !c.author().startsWith("rule:")).toList();
        String from = majority(human.stream().map(Candidate::from).toList(), defaultFrom);
        String to = majority(human.stream().map(Candidate::to).toList(), defaultTo);
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        String symbol = majority(human.stream().map(Candidate::symbol).toList(), null);
        String norm = g.normalized();
        Rule.Match match;
        String replace;
        Rule.Kind kind;
        if (norm.startsWith("type ")) {
            String[] parts = norm.substring(5).split(" -> ", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                return null;
            }
            match = new Rule.Match(null, null, null, parts[0], null, null, null);
            replace = parts[1];
            kind = Rule.Kind.MECHANICAL;
        } else {
            // Recompute the sides from a member of the group: the text itself may contain " -> ".
            String[] sides = human.stream().filter(c -> key(c).equals(norm)).findFirst()
                    .map(c -> Normalizer.abstractSides(c.before(), c.after())).orElse(Normalizer.sides(norm));
            if (sides[0].isBlank() || sides[1].isBlank()) {
                return null;
            }
            replace = sides[1];
            match = memberSelector(symbol, sides[0]);
            if (match == null) {
                match = new Rule.Match(null, null, null, null, null, sides[0], null);
            }
            kind = sides[0].contains(";") || sides[0].contains("{") || replace.contains(";") || replace.contains("{")
                    ? Rule.Kind.IDIOM : Rule.Kind.ADAPTER;
        }
        TreeSet<String> ids = new TreeSet<>();
        for (Candidate c : human) {
            ids.add(c.id() != null ? c.id() : "c-" + sha(c.file() + "|" + c.line() + "|" + c.before()).substring(0, 10));
        }
        List<Rule.Test> tests = new ArrayList<>();
        Map<String, Candidate> distinct = new LinkedHashMap<>();
        human.stream().sorted(Comparator.comparing(Candidate::before).thenComparing(Candidate::after))
                .forEach(c -> distinct.putIfAbsent(c.before() + "\u0000" + c.after(), c));
        for (Candidate c : distinct.values()) {
            if (tests.size() == 3) {
                break;
            }
            if (!c.before().isBlank() && !c.after().isBlank()) {
                tests.add(new Rule.Test(c.id(), null, c.before(), c.after()));
            }
        }
        if (tests.isEmpty()) {
            return null;
        }
        String id = "r-" + ver(from) + "-" + ver(to) + "-draft-" + slug(symbol, norm) + "-" + sha(norm).substring(0, 8);
        String desc = "DRAFT (loop): " + g.count() + " occurrence(s) of the same normalized fix; needs review by rules-curator. normalized: " + norm;
        return new Rule(1, id, desc, from, to, kind, match, replace, null, null, null, null, 0.5,
                new Rule.Provenance("agent", "candidates " + String.join(", ", ids), null), tests);
    }

    /** {@code call}/{@code new} selector with binds when {@code before} is exactly {@code $v.name($a, ...)} / {@code new T($a, ...)}. */
    private static Rule.Match memberSelector(String symbol, String before) {
        if (symbol == null) {
            return null;
        }
        Matcher sm = MEMBER.matcher(symbol);
        if (!sm.matches()) {
            return null;
        }
        String owner = sm.group(1);
        String name = sm.group(2);
        if (name.equals("<init>")) {
            String simple = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1);
            Matcher nm = Pattern.compile("^new " + Pattern.quote(simple) + "\\((.*)\\)$").matcher(before);
            if (!nm.matches()) {
                return null;
            }
            List<String> args = placeholders(nm.group(1));
            return args == null ? null : new Rule.Match(null, owner + "(" + sm.group(3) + ")", null, null, null, null,
                    new Rule.Bind(null, args, null, null));
        }
        Matcher cm = Pattern.compile("^(\\$v\\d+)\\." + Pattern.quote(name) + "\\((.*)\\)$").matcher(before);
        if (!cm.matches()) {
            return null;
        }
        List<String> args = placeholders(cm.group(2));
        return args == null ? null : new Rule.Match(symbol, null, null, null, null, null, new Rule.Bind(cm.group(1), args, null, null));
    }

    private static List<String> placeholders(String argList) {
        if (argList.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String a : argList.split(",")) {
            String t = a.strip();
            if (!PH.matcher(t).matches() || out.contains(t)) {
                return null;
            }
            out.add(t);
        }
        return out;
    }

    private static String majority(List<String> values, String dflt) {
        Map<String, Integer> n = new TreeMap<>();
        for (String v : values) {
            if (v != null) {
                n.merge(v, 1, Integer::sum);
            }
        }
        return n.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse(dflt);
    }

    private static String ver(String platform) {
        return platform.substring(platform.indexOf('-') + 1);
    }

    private static String slug(String symbol, String norm) {
        String base = symbol;
        if (base == null) {
            base = norm.startsWith("type ") ? norm.substring(5).split(" ")[0] : "pattern";
        }
        String member = base.contains("#") ? base.substring(base.indexOf('#') + 1).replaceAll("\\(.*", "") : base;
        if (member.equals("<init>")) {
            member = "new-" + base.substring(0, base.indexOf('#'));
        }
        member = member.substring(member.lastIndexOf('.') + 1);
        String s = member.replace('$', '-').replaceAll("[^A-Za-z0-9._-]", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("^[^a-z0-9]+", "");
        if (s.isEmpty()) {
            s = "x";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
