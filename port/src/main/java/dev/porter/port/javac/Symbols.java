package dev.porter.port.javac;

import dev.porter.port.passes.SrgMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregates javac errors BY DISTINCT SYMBOL — the progress metric of a port and the unit of AI resolution (one
 * rule / adapter / edit per distinct symbol, not per error). Keys: missing class or package -> its FQN (from the
 * import or the qualified use); missing method/field -> {@code Owner#name}, SRG ids mapped to the source version's
 * mojmap name so all spellings of one symbol merge; any other error -> its message with literals normalised.
 */
public final class Symbols {
    private static final Pattern LOCATION = Pattern.compile("\\s*location: (?:variable \\w+ of type |class |interface |enum )?([\\w.<>?, \\[\\]]+)");
    private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern SYMBOL = Pattern.compile("(class|interface|variable|method|static|enum)\\s+(\\w+)(\\(.*\\))?");
    private static final Pattern SRG_MEMBER = Pattern.compile("(field|method) (.*)#([mf]_\\d+_)");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");

    /** One distinct symbol: key, number of errors, up to 20 files. */
    public record Entry(String key, int count, List<String> files) {
    }

    /** Aggregation result. */
    public record Result(int errors, int distinct, Map<String, Integer> byKind, int files, List<Entry> symbols) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("errors", errors);
            m.put("distinct", distinct);
            m.put("byKind", byKind);
            m.put("files", files);
            List<Object> rows = new ArrayList<>();
            for (Entry e : symbols) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", e.key());
                r.put("count", e.count());
                r.put("files", e.files());
                rows.add(r);
            }
            m.put("symbols", rows);
            return m;
        }
    }

    private Symbols() {
    }

    static String locOwner(List<String> extra) {
        for (String e : extra) {
            Matcher m = LOCATION.matcher(e);
            if (m.lookingAt()) {
                return m.group(1).replaceAll("<.*", "").strip();
            }
        }
        return "?";
    }

    static String key(String msg, List<String> extra) {
        String src = extra.isEmpty() ? "" : extra.get(0);
        if (msg.startsWith("package ") && msg.endsWith(" does not exist")) {
            String pkg = msg.substring(8, msg.length() - 15);
            Matcher m = IMPORT.matcher(src);
            if (m.find()) {
                return "class " + m.group(1);
            }
            Matcher q = Pattern.compile(Pattern.quote(pkg) + "\\.(\\w+)").matcher(src);
            return "class " + pkg + "." + (q.find() ? q.group(1) : "?");
        }
        if (msg.startsWith("cannot find symbol")) {
            String sym = "?";
            for (String e : extra) {
                String t = e.strip();
                if (t.startsWith("symbol:")) {
                    sym = t.length() > 8 ? t.substring(8) : "";
                    break;
                }
            }
            sym = sym.strip();
            String owner = locOwner(extra);
            Matcher m = SYMBOL.matcher(sym.replace("static ", ""));
            if (m.lookingAt()) {
                String kind = m.group(1);
                String name = m.group(2);
                if (kind.equals("class") || kind.equals("interface") || kind.equals("enum")) {
                    Matcher m2 = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+\\." + name + ")\\s*;").matcher(src);
                    return "class " + (m2.find() ? m2.group(1) : name);
                }
                if (kind.equals("variable") && Character.isUpperCase(name.charAt(0)) && owner.equals("?")) {
                    return "class " + name;
                }
                return (kind.equals("variable") ? "field" : "method") + " " + owner + "#" + name;
            }
            return "symbol " + sym;
        }
        String norm = QUOTED.matcher(msg).replaceAll("\"\"");
        norm = NUMBER.matcher(norm).replaceAll("N");
        return "other " + norm;
    }

    static String canon(String k, SrgMap srg, String version) {
        Matcher m = SRG_MEMBER.matcher(k);
        if (m.matches()) {
            String[] v = srg.get(m.group(3));
            if (v != null) {
                String o = v[0];
                return m.group(1) + " " + o.substring(o.lastIndexOf('.') + 1) + "#" + v[1] + " (" + version + ")";
            }
        }
        return k;
    }

    /**
     * @param srg     SRG id map of the source version (may be empty)
     * @param version label of the source version used in mapped keys, e.g. {@code 1.19.2}
     */
    public static Result aggregate(List<JavacLog.Error> errs, SrgMap srg, String version) {
        Map<String, List<String[]>> raw = new LinkedHashMap<>();
        for (JavacLog.Error e : errs) {
            raw.computeIfAbsent(canon(key(e.message(), e.extra()), srg, version), x -> new ArrayList<>())
                    .add(new String[] {e.file(), Integer.toString(e.line())});
        }
        Map<String, Set<String>> fq = new LinkedHashMap<>();
        for (String k : raw.keySet()) {
            if (k.startsWith("class ") && k.substring(6).contains(".")) {
                String name = k.substring(6);
                String last = name.substring(name.lastIndexOf('.') + 1);
                fq.computeIfAbsent(last, x -> new TreeSet<>()).add(name);
            }
        }
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<String[]>> en : raw.entrySet()) {
            String k = en.getKey();
            if (k.startsWith("class ")) {
                String name = k.substring(6);
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (!name.contains(".") && fq.getOrDefault(simple, Set.of()).size() == 1) {
                    k = "class " + fq.get(simple).iterator().next();
                }
            }
            groups.computeIfAbsent(k, x -> new ArrayList<>()).addAll(en.getValue());
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> en : groups.entrySet()) {
            List<String> files = new ArrayList<>(new TreeSet<>(en.getValue().stream().map(x -> x[0]).toList()));
            entries.add(new Entry(en.getKey(), en.getValue().size(), files.subList(0, Math.min(20, files.size()))));
        }
        // stable: ties keep first-seen order (as Python's Counter.most_common)
        entries.sort((a, b) -> Integer.compare(b.count(), a.count()));
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (Entry e : entries) {
            kinds.merge(e.key().split(" ", 2)[0], 1, Integer::sum);
        }
        Set<String> files = new TreeSet<>();
        errs.forEach(e -> files.add(e.file()));
        return new Result(errs.size(), entries.size(), kinds, files.size(), List.copyOf(entries));
    }
}
