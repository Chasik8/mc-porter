package dev.porter.port.upfront;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Acceptance check of the concept table and the type plan on real data (WP4). Reads the api-index files
 * ({@code lineage.tsv}, optionally {@code target.tsv}) and a symbol list (tab-separated, first columns
 * {@code kind owner name desc}; kind {@code type|class|method|field}; a header line is skipped) and reports:
 * <ul>
 *   <li>types without a plan (must be 0) and the plan counts by deciding step;</li>
 *   <li>lineage rows that reach 1.7.10 with a changed signature, and how many of them the concepts explain, with
 *       the hit count of every concept (spec: at least 40 of 108 for the addon, 50 of 172 for the whole mod).</li>
 * </ul>
 * Usage: {@code ConceptAcceptance <concepts.yaml> <lineage.tsv> <symbols.tsv> [--target target.tsv] [--rules dir]...
 * [--min-explained N] [--min-signature-changed N]}. Exit code 1 if a type has no plan or a threshold is missed.
 */
public final class ConceptAcceptance {

    /** minimum composed lineage confidence for a chain to count (same as the matcher) */
    static final double LINEAGE_MIN = 0.30;

    /** The measured numbers. */
    public record Result(int types, int typesWithoutPlan, Map<String, Integer> planByMethod, int signatureChanged,
            int explained, Map<String, Integer> conceptHits) {
    }

    private ConceptAcceptance() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: ConceptAcceptance <concepts.yaml> <lineage.tsv> <symbols.tsv> [--target target.tsv]"
                    + " [--rules dir]... [--min-explained N] [--min-signature-changed N]");
            System.exit(2);
        }
        Path target = null;
        List<Path> rules = new ArrayList<>();
        int minExplained = 0;
        int minChanged = 0;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> target = Path.of(args[++i]);
                case "--rules" -> rules.add(Path.of(args[++i]));
                case "--min-explained" -> minExplained = Integer.parseInt(args[++i]);
                case "--min-signature-changed" -> minChanged = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Result r = run(Concepts.load(Path.of(args[0])), Path.of(args[1]), Path.of(args[2]), target, rules);
        System.out.println("types: " + r.types() + ", without plan: " + r.typesWithoutPlan());
        System.out.println("type plan by step: " + r.planByMethod());
        System.out.println("lineage rows reaching 1.7.10 with a changed signature: " + r.signatureChanged());
        System.out.println("explained by concepts: " + r.explained());
        System.out.println("concept hits (rows using the concept): " + r.conceptHits());
        boolean ok = r.typesWithoutPlan() == 0 && r.explained() >= minExplained && r.signatureChanged() >= minChanged;
        System.out.println(ok ? "ACCEPTED" : "NOT ACCEPTED");
        System.exit(ok ? 0 : 1);
    }

    public static Result run(Concepts concepts, Path lineageFile, Path symbolsFile, Path targetFile, List<Path> ruleRoots) {
        // lineage
        Map<String, String[]> lin = new HashMap<>();
        Map<String, String> classMap = new HashMap<>();
        for (String l : lines(lineageFile)) {
            String[] p = l.split("\t", -1);
            if (p.length < 10) {
                continue;
            }
            lin.put(p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + p[3], p);
            if (p[0].equals("class") && p[9].isEmpty() && !p[4].isEmpty()) {
                classMap.put(p[1], p[4]);
            }
        }
        // target (optional): classes and members, for the existence check
        Set<String> targetClasses = null;
        Set<String> targetMembers = null;
        if (targetFile != null) {
            targetClasses = new HashSet<>();
            targetMembers = new HashSet<>();
            for (String l : lines(targetFile)) {
                String[] p = l.split("\t", -1);
                if (p[0].equals("class")) {
                    targetClasses.add(p[1]);
                } else if (p.length >= 6 && (Integer.parseInt(p[5]) & 0x0002) == 0) {
                    targetMembers.add(p[1] + "\t" + p[3] + "\t" + p[4]);
                }
            }
        }
        Map<String, String> lineageTypes = new HashMap<>();
        for (Map.Entry<String, String> e : classMap.entrySet()) {
            if (targetClasses == null || targetClasses.contains(e.getValue())) {
                lineageTypes.put(e.getKey(), e.getValue());
            }
        }
        Map<String, String> hand = TypePlanner.handTypeRules(ruleRoots);
        // class map for descriptors: lineage plus hand rules naming a target class (as the matcher does)
        Map<String, String> descMap = new HashMap<>(classMap);
        for (Map.Entry<String, String> e : hand.entrySet()) {
            if (!e.getValue().startsWith(TypePlanner.DEFAULT_SHIM_PREFIX) && (targetClasses == null || targetClasses.contains(e.getValue()))) {
                descMap.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        List<String> types = new ArrayList<>();
        int changed = 0;
        int explained = 0;
        Map<String, Integer> hits = new TreeMap<>();
        for (String l : lines(symbolsFile)) {
            String[] p = l.split("\t", -1);
            if (p.length < 2 || p[0].equals("kind")) {
                continue;
            }
            if (p[0].equals("type") || p[0].equals("class")) {
                types.add(p[1]);
                continue;
            }
            if (p.length < 4) {
                continue;
            }
            String[] e = lin.get(p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + p[3]);
            if (e == null || !e[9].isEmpty() || Double.parseDouble(e[8]) < LINEAGE_MIN) {
                continue;
            }
            if (targetMembers != null && !targetMembers.contains(e[4] + "\t" + e[5] + "\t" + e[6])) {
                continue;
            }
            if (sameRenamedMoved(e[7])) {
                continue;
            }
            changed++;
            String cd = concepts.conceptDesc(p[3], descMap);
            if (cd != null && cd.equals(e[6])) {
                explained++;
                for (String c : new HashSet<>(concepts.conceptsIn(p[3]))) {
                    hits.merge(c, 1, Integer::sum);
                }
            }
        }
        TypePlanner planner = new TypePlanner(concepts, lineageTypes, hand, null);
        Map<String, TypePlanner.TypePlan> plans = planner.plan(types);
        int without = 0;
        Map<String, Integer> byMethod = new LinkedHashMap<>();
        for (String t : types) {
            TypePlanner.TypePlan tp = plans.get(t);
            if (tp == null || tp.target() == null || tp.target().isEmpty()) {
                without++;
            } else {
                byMethod.merge(tp.method() + "/" + tp.plan(), 1, Integer::sum);
            }
        }
        return new Result(types.size(), without, new TreeMap<>(byMethod), changed, explained, hits);
    }

    private static boolean sameRenamedMoved(String statuses) {
        for (String s : statuses.split(",")) {
            if (!(s.equals("same") || s.equals("renamed") || s.equals("moved"))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> lines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
