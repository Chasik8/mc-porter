package dev.porter.port.javac;

import dev.porter.port.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Error-driven fixes for decompiler (CFR) declaration artefacts, no mod content. Each fix is keyed on a javac error
 * and rewrites the source lines the error points into:
 * <ol>
 *   <li>lost local declaration: {@code cannot find symbol ... variable v} where the first use is {@code v = new T(..);}:
 *       {@code T v;} is inserted before the statement of the shallowest block that holds all uses;</li>
 *   <li>leftover generic name: {@code cannot find symbol ... class K} (one capital letter) on a line with {@code <K>}:
 *       the type argument is dropped (raw type);</li>
 *   <li>for-each over a value cast to {@code Object}: {@code for (X v : (Object) e)} with {@code e} declared in the same
 *       file as an array or {@code Iterable<E>}: the cast goes and the loop variable gets the element type;</li>
 *   <li>an iterator declared as {@code Object} ({@code hasNext}/{@code next} not found on a variable of type Object):
 *       the declaration becomes a raw {@code java.util.Iterator};</li>
 *   <li>a for-each variable declared as {@code Object} whose {@code getKey}/{@code getValue} are not found: it becomes a raw
 *       {@code java.util.Map.Entry}.</li>
 * </ol>
 */
public final class DeclFix {
    private static final Pattern SYMBOL_VAR = Pattern.compile("\\s*symbol:\\s+variable (\\w+)");
    private static final Pattern SYMBOL_CLASS = Pattern.compile("\\s*symbol:\\s+class ([A-Z])\\s*$");
    private static final Pattern SYMBOL_METHOD = Pattern.compile("\\s*symbol:\\s+method (\\w+)\\(");
    private static final Pattern LOCATION_OBJECT = Pattern.compile("\\s*location:\\s+variable (\\w+) of type Object\\s*$");
    private static final Pattern NEW_ASSIGN = Pattern.compile("\\s*(\\w+) = new ([\\w.]+)(?:<[^()]*>)?\\(.*");
    private static final Pattern FOREACH_OBJ = Pattern.compile("(\\s*for \\()(?:final )?([\\w.<>?, \\[\\]]+?) (\\w+) : \\(Object\\)([\\w.]+)\\) \\{\\s*");
    private static final Pattern FOREACH_OBJECT_VAR = Pattern.compile("(\\s*for \\()Object (\\w+) : (.+)");
    private static final Pattern OBJECT_DECL = Pattern.compile("(\\s*)Object (\\w+) = (.+)");
    private static final Set<String> ITERABLES = Set.of("List", "Set", "Collection", "Iterable", "ObjectList", "ObjectSet", "IntList",
            "ObjectCollection", "ArrayList", "ObjectArrayList", "LinkedList", "Queue", "Deque");

    private DeclFix() {
    }

    private record Hit(int line, String symbolKind, String symbol, String location) {
    }

    /** Applies the declaration fixes for one javac log; returns the number of fixes. */
    public static int apply(String log, Path root) {
        Map<String, List<Hit>> byFile = new TreeMap<>();
        List<String> l = JavacLog.lines(log);
        for (int i = 0; i < l.size(); i++) {
            Matcher m = JavacLog.HDR.matcher(l.get(i));
            if (!m.matches()) {
                continue;
            }
            String msg = m.group(3);
            int ln = Integer.parseInt(m.group(1 + 1));
            String sym = null;
            String kind = null;
            String loc = null;
            for (int j = i + 1; j < Math.min(l.size(), i + 7) && !JavacLog.HDR.matcher(l.get(j)).matches(); j++) {
                Matcher a = SYMBOL_VAR.matcher(l.get(j));
                Matcher b = SYMBOL_CLASS.matcher(l.get(j));
                Matcher c = SYMBOL_METHOD.matcher(l.get(j));
                Matcher d = LOCATION_OBJECT.matcher(l.get(j));
                if (a.find()) {
                    kind = "variable";
                    sym = a.group(1);
                } else if (b.find()) {
                    kind = "class";
                    sym = b.group(1);
                } else if (c.find()) {
                    kind = "method";
                    sym = c.group(1);
                } else if (d.find()) {
                    loc = d.group(1);
                }
            }
            if (msg.startsWith("for-each not applicable")) {
                kind = "foreach";
                sym = "";
            }
            if (kind != null) {
                byFile.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(new Hit(ln, kind, sym, loc));
            }
        }
        int n = 0;
        for (Map.Entry<String, List<Hit>> en : byFile.entrySet()) {
            Path p = root.resolve(en.getKey().replace('\\', '/'));
            if (!Files.exists(p)) {
                continue;
            }
            List<String> lines = new ArrayList<>(List.of(Text.read(p).split("\n", -1)));
            int before = n;
            n += leftoverGenerics(lines, en.getValue());
            n += foreachCasts(lines, en.getValue());
            n += objectIterators(lines, en.getValue());
            n += objectEntries(lines, en.getValue());
            n += lostDeclarations(lines, en.getValue()); // last: it inserts a line, the others use the log's line numbers
            if (n != before) {
                Text.write(p, String.join("\n", lines));
            }
        }
        return n;
    }

    // 1. lost local declaration -----------------------------------------------------------------------------------

    static int lostDeclarations(List<String> lines, List<Hit> hits) {
        int n = 0;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (!h.symbolKind().equals("variable") || !done.add(h.symbol())) {
                continue;
            }
            int first = Integer.MAX_VALUE;
            int last = -1;
            for (Hit o : hits) {
                if (o.symbolKind().equals("variable") && o.symbol().equals(h.symbol())) {
                    first = Math.min(first, o.line());
                    last = Math.max(last, o.line());
                }
            }
            String firstText = lines.get(first - 1).replaceAll("\r+$", "");
            Matcher m = NEW_ASSIGN.matcher(firstText);
            if (!m.matches() || !m.group(1).equals(h.symbol())) {
                continue;
            }
            int[] depth = depthsAtLineStart(lines);
            int min = Integer.MAX_VALUE;
            for (int i = first - 1; i <= last - 1; i++) {
                min = Math.min(min, depthAfterLeadingClosers(lines.get(i), depth[i]));
            }
            int start = -1;
            for (int i = first - 1; i >= 0; i--) {
                String t = lines.get(i).strip();
                if (t.isEmpty() || t.startsWith("}") || t.startsWith("else") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                    continue;
                }
                if (depthAfterLeadingClosers(lines.get(i), depth[i]) == min && statementStart(lines, i)) {
                    start = i;
                    break;
                }
                if (depthAfterLeadingClosers(lines.get(i), depth[i]) < min) {
                    break;
                }
            }
            if (start < 0) {
                continue;
            }
            String indent = lines.get(start).replaceAll("^(\\s*).*$", "$1");
            lines.add(start, indent + m.group(2) + " " + h.symbol() + ";");
            n++;
            // line numbers of later hits shift by one: the caller re-runs on the next javac round, so stop here
            break;
        }
        return n;
    }

    private static boolean statementStart(List<String> lines, int i) {
        for (int k = i - 1; k >= 0; k--) {
            String t = lines.get(k).strip();
            if (t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            return t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.endsWith(":") || t.startsWith("@");
        }
        return true;
    }

    static int[] depthsAtLineStart(List<String> lines) {
        int[] d = new int[lines.size()];
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            d[i] = depth;
            String s = stripStrings(lines.get(i));
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return d;
    }

    static int depthAfterLeadingClosers(String line, int depthAtStart) {
        String t = line.strip();
        int d = depthAtStart;
        for (int k = 0; k < t.length() && t.charAt(k) == '}'; k++) {
            d--;
        }
        return d;
    }

    private static String stripStrings(String s) {
        return s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(\\\\.|[^'\\\\])'", "' '").replaceAll("//.*$", "");
    }

    // 2. leftover generic name -----------------------------------------------------------------------------------

    static int leftoverGenerics(List<String> lines, List<Hit> hits) {
        int n = 0;
        for (Hit h : hits) {
            if (!h.symbolKind().equals("class")) {
                continue;
            }
            String s = lines.get(h.line() - 1);
            String t = s.replace("<" + h.symbol() + ">", "");
            if (!t.equals(s)) {
                lines.set(h.line() - 1, t);
                n++;
            }
        }
        return n;
    }

    // 3. for-each over (Object) e -----------------------------------------------------------------------------------

    static int foreachCasts(List<String> lines, List<Hit> hits) {
        int n = 0;
        String all = String.join("\n", lines);
        for (Hit h : hits) {
            if (!h.symbolKind().equals("foreach")) {
                continue;
            }
            String s = lines.get(h.line() - 1);
            Matcher m = FOREACH_OBJ.matcher(s.replaceAll("\r+$", ""));
            if (!m.matches()) {
                continue;
            }
            String expr = m.group(4);
            String name = expr.substring(expr.lastIndexOf('.') + 1);
            String type = declaredType(all, name);
            String elem = type == null ? null : elementType(type);
            if (elem == null) {
                continue;
            }
            lines.set(h.line() - 1, m.group(1) + elem + " " + m.group(3) + " : " + expr + ") {" + (s.endsWith("\r") ? "\r" : ""));
            n++;
        }
        return n;
    }

    /** Declared type text of a field/local/parameter called {@code name} in the file, or null. */
    static String declaredType(String src, String name) {
        Pattern p = Pattern.compile("(?m)(?:^|[;{(,]|\\bfinal )\\s*(?:(?:private|public|protected|static|final|transient|volatile)\\s+)*([A-Za-z_][\\w.]*(?:<[^;=(){}]*>)?(?:\\[\\])*)(\\s*\\.\\.\\.)?\\s+"
                + Pattern.quote(name) + "\\s*(?:[=;,)]|:)");
        Matcher m = p.matcher(src);
        while (m.find()) {
            String t = m.group(1);
            if (!t.equals("return") && !t.equals("new") && !t.equals("else") && !t.equals("throw")) {
                return m.group(2) != null ? t + "[]" : t;
            }
        }
        return null;
    }

    static String elementType(String type) {
        if (type.endsWith("[]")) {
            return type.substring(0, type.length() - 2);
        }
        Matcher m = Pattern.compile("([\\w.]+)<(.+)>").matcher(type);
        if (m.matches() && ITERABLES.contains(m.group(1).substring(m.group(1).lastIndexOf('.') + 1)) && !m.group(2).contains(",")) {
            return m.group(2).trim();
        }
        if (type.equals("IntList") || type.equals("it.unimi.dsi.fastutil.ints.IntList")) {
            return "int";
        }
        return null;
    }

    // 4. Object iterator -----------------------------------------------------------------------------------------

    static int objectIterators(List<String> lines, List<Hit> hits) {
        int n = 0;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (!h.symbolKind().equals("method") || h.location() == null || !(h.symbol().equals("hasNext") || h.symbol().equals("next"))
                    || !done.add(h.location())) {
                continue;
            }
            for (int i = h.line() - 1; i >= 0 && i >= h.line() - 40; i--) {
                Matcher m = OBJECT_DECL.matcher(lines.get(i).replaceAll("\r+$", ""));
                if (m.matches() && m.group(2).equals(h.location())) {
                    lines.set(i, m.group(1) + "java.util.Iterator " + m.group(2) + " = " + m.group(3) + (lines.get(i).endsWith("\r") ? "\r" : ""));
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    // 5. Object for-each variable used as a map entry -----------------------------------------------------------------

    static int objectEntries(List<String> lines, List<Hit> hits) {
        int n = 0;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (!h.symbolKind().equals("method") || h.location() == null || !(h.symbol().equals("getKey") || h.symbol().equals("getValue"))
                    || !done.add(h.location())) {
                continue;
            }
            for (int i = h.line() - 1; i >= 0 && i >= h.line() - 15; i--) {
                Matcher m = FOREACH_OBJECT_VAR.matcher(lines.get(i).replaceAll("\r+$", ""));
                if (m.matches() && m.group(2).equals(h.location())) {
                    lines.set(i, m.group(1) + "java.util.Map.Entry " + m.group(2) + " : " + m.group(3) + (lines.get(i).endsWith("\r") ? "\r" : ""));
                    n++;
                    break;
                }
            }
        }
        return n;
    }
}
