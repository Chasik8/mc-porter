package dev.porter.port.upfront;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scored features of a (source symbol, target candidate) pair; weights and synonyms come from {@link MatchTables}. */
final class Features {
    private static final Pattern TOK = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d+");
    private static final Pattern ACCESSOR_PREFIX = Pattern.compile("^(get|is|set)(?=[A-Z])");

    private final MatchTables tables;
    private final Map<String, String> classMap;
    private final Map<String, String> conceptOwners;
    private final Map<String, String> typeRuleClasses;

    Features(MatchTables tables, Map<String, String> classMap, Map<String, String> typeRuleClasses) {
        this.tables = tables;
        this.classMap = classMap;
        this.conceptOwners = tables.conceptOwners;
        this.typeRuleClasses = typeRuleClasses;
    }

    static List<String> toks(String n) {
        List<String> out = new ArrayList<>();
        Matcher m = TOK.matcher(n);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isUpper(String s) {
        return !s.equals(s.toLowerCase(Locale.ROOT)) && s.equals(s.toUpperCase(Locale.ROOT));
    }

    /** Name similarity in 0..1: token overlap with synonyms; 1.0 on case/underscore-insensitive equality. */
    double nameSim(String a, String b) {
        String al = a.toLowerCase(Locale.ROOT);
        String bl = b.toLowerCase(Locale.ROOT);
        if (al.replace("_", "").equals(bl.replace("_", ""))) {
            return 1.0;
        }
        if (isUpper(a)) {
            for (String p : tables.constantPrefixes) {
                if (bl.equals(p + al.replace("_", ""))) {
                    return 0.9;
                }
            }
        }
        List<String> ta = toks(a);
        Set<String> tb = new HashSet<>(toks(b));
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String t : ta) {
            boolean ok = tb.contains(t);
            if (!ok) {
                for (String s : tables.synonyms.getOrDefault(t, List.of())) {
                    if (tb.contains(s) || bl.contains(s)) {
                        ok = true;
                        break;
                    }
                }
            }
            if (ok) {
                hit++;
            }
        }
        return hit / (double) Math.max(ta.size(), tb.size());
    }

    String mapT(String t) {
        return Descs.mapType(t, classMap, conceptOwners, typeRuleClasses);
    }

    /** Features of a same-kind candidate: name, arity, ret, params. */
    Map<String, Double> pair(String name, String desc, String candName, String candDesc) {
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("name", nameSim(name, candName));
        List<String> pa = Descs.params(desc);
        List<String> pb = Descs.params(candDesc);
        String ra = Descs.ret(desc);
        String rb = Descs.ret(candDesc);
        if (pa == null) {
            f.put("arity", 1.0);
            double r = mapT(ra).equals(rb) ? 1.0 : 0.0;
            f.put("ret", r);
            f.put("params", r);
            return f;
        }
        f.put("arity", pb != null && pa.size() == pb.size() ? 1.0 : 0.0);
        f.put("ret", mapT(ra).equals(rb) ? 1.0 : 0.0);
        if (pb == null) {
            f.put("params", 0.0);
        } else if (pa.isEmpty() && pb.isEmpty()) {
            f.put("params", 1.0);
        } else {
            int n = 0;
            for (String x : pa) {
                if (pb.contains(mapT(x))) {
                    n++;
                }
            }
            f.put("params", n / (double) Math.max(pa.size(), pb.size()));
        }
        return f;
    }

    /** Features of an accessor (0-arg getter or 1-arg void setter) against a target field. */
    Map<String, Double> accessor(String name, String desc, boolean getter, String fieldName, String fieldDesc) {
        String prop = ACCESSOR_PREFIX.matcher(name).replaceFirst("");
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("name", nameSim(prop, fieldName));
        f.put("arity", 1.0);
        String t = getter ? Descs.ret(desc) : Descs.params(desc).get(0);
        double r = mapT(t).equals(fieldDesc) ? 1.0 : 0.0;
        f.put("ret", r);
        f.put("params", r);
        return f;
    }

    double score(Map<String, Double> f) {
        double s = 0.0;
        for (Map.Entry<String, Double> e : f.entrySet()) {
            s += tables.weights.getOrDefault(e.getKey(), 0.0) * e.getValue();
        }
        return s;
    }
}
