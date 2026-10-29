package dev.porter.port.javac;

import dev.porter.port.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Error-driven fixes for decompiler (CFR) artefacts around casts and generics, no mod content. CFR pins overloads
 * with {@code (Object)} casts, erases generics in for-each loops and leaves casts that only compiled against the
 * source version's types. For each matching javac error, at the reported position:
 * <ol>
 *   <li>for-each over a raw iterable: {@code for (T x : (Iterable<T>) (Iterable) (expr))};</li>
 *   <li>an {@code (Object)}/{@code (Object[])} cast near the caret is removed;</li>
 *   <li>"P cannot be converted to B" with P primitive and a preceding (B) cast: the cast is removed;</li>
 *   <li>"A cannot be converted to B" with a preceding cast: {@code (Object)} is inserted after it;</li>
 *   <li>"A cannot be converted to T" (simple target, no cast): {@code (T)} is inserted before the expression;</li>
 *   <li>"x might not have been initialized" on {@code a[x.m()] = x = e;}: split into {@code x = e; a[x.m()] = x;}.</li>
 *   <li>"x might not have been initialized" after CFR's {@code T y = c ? a : (x = b);} (the assignment moved into the
 *       else branch, {@code y} unused): {@code x = c ? a : b;}.</li>
 * </ol>
 */
public final class CastFix {
    private static final java.util.regex.Pattern TERNARY_ASSIGN_HEAD = Pattern.compile("^(\\s*)[\\w.<>\\[\\]]+\\s+\\w+\\s*=\\s*(.+)$");

    /**
     * Finds, above line {@code from} (0-based), {@code T y = cond ? a : (var = b);} and rewrites it into
     * {@code var = cond ? a : b;}. Returns the rewritten line index, or -1.
     */
    static int misplacedTernaryAssign(List<String> lines, int from, String var) {
        String tail = ": (" + var + " = ";
        for (int i = from - 1; i >= Math.max(0, from - 15); i--) {
            String s = lines.get(i).replaceAll("\r+$", "");
            int t = s.lastIndexOf(tail);
            if (t < 0 || !s.endsWith(");")) {
                continue;
            }
            Matcher h = TERNARY_ASSIGN_HEAD.matcher(s.substring(0, t));
            if (!h.matches() || !h.group(2).contains("?")) {
                continue;
            }
            String b = s.substring(t + tail.length(), s.length() - 2);
            lines.set(i, h.group(1) + var + " = " + h.group(2).strip() + " : " + b + ";" + (lines.get(i).endsWith("\r") ? "\r" : ""));
            return i;
        }
        return -1;
    }
    private static final Set<String> PRIMS = Set.of("int", "long", "short", "byte", "char", "float", "double", "boolean");
    private static final Pattern FOREACH = Pattern.compile("(\\s*for \\((?:final )?)([\\w.<>?, \\[\\]]+?) (\\w+) : (.+)\\) \\{(\\s*)");
    private static final Pattern CFR_ASSIGN = Pattern.compile("(\\s*)(.+?)\\[(\\w+)\\.(\\w+)\\(\\)\\] = \\3 = (.+);");
    private static final Pattern INCOMPATIBLE = Pattern.compile("incompatible types: (.+?) cannot be converted to (.+)$");
    private static final Pattern CAST_TYPE = Pattern.compile("\\s*[A-Za-z_][\\w.$]*(?:<[^()]*>)?(?:\\[\\])*\\s*");
    private static final Pattern SIMPLE_TYPE = Pattern.compile("[A-Z]\\w*(?:\\[\\])?");

    private record Err(int col, String msg) {
    }

    private CastFix() {
    }

    /** Applies the fixes for one javac log to the project the log's (relative) paths point into; returns the count. */
    public static int apply(String log, Path root) {
        List<String> l = JavacLog.lines(log);
        Map<String, List<Err>> todo = new TreeMap<>(Comparator.comparing((String k) -> k.substring(0, k.lastIndexOf('\u0000')))
                .thenComparingInt(k -> Integer.parseInt(k.substring(k.lastIndexOf('\u0000') + 1))));
        for (int i = 0; i < l.size(); i++) {
            Matcher m = JavacLog.HDR.matcher(l.get(i));
            if (!m.matches()) {
                continue;
            }
            String f = m.group(1);
            int ln = Integer.parseInt(m.group(2));
            String msg = m.group(3);
            String caret = i + 2 < l.size() ? l.get(i + 2) : "";
            int col = caret.indexOf('^');
            boolean rel = msg.contains("cannot be converted to") || msg.contains("inference variable")
                    || msg.contains("bad type in conditional expression")
                    || (msg.startsWith("no suitable method found for") && msg.contains("Object"))
                    || (msg.contains("cannot be applied to given types") && String.join("", l.subList(Math.min(i + 1, l.size()), Math.min(i + 8, l.size()))).contains("Object"))
                    || msg.contains("might not have been initialized");
            if (rel && col >= 0) {
                todo.computeIfAbsent(f + '\u0000' + ln, k -> new ArrayList<>()).add(new Err(col, msg));
            }
        }
        int n = 0;
        for (Map.Entry<String, List<Err>> en : todo.entrySet()) {
            String k = en.getKey();
            String f = k.substring(0, k.lastIndexOf('\u0000'));
            int ln = Integer.parseInt(k.substring(k.lastIndexOf('\u0000') + 1));
            List<Err> errs = en.getValue();
            Path p = root.resolve(f.replace('\\', '/'));
            if (!Files.exists(p) || !p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
                continue; // missing, or outside the project (e.g. found on a source path): never edited
            }
            List<String> lines = new ArrayList<>(List.of(Text.read(p).split("\n", -1)));
            String s = lines.get(ln - 1);
            String cr = s.endsWith("\r") ? "\r" : "";
            s = s.replaceAll("\r+$", "");
            boolean changed = false;
            Matcher fe = FOREACH.matcher(s);
            boolean isFe = fe.matches();
            Matcher ast = CFR_ASSIGN.matcher(s);
            boolean isAst = ast.matches();
            String uninit = errs.stream().map(Err::msg).filter(x -> x.startsWith("variable ") && x.contains(" might not have been initialized"))
                    .map(x -> x.substring("variable ".length(), x.indexOf(' ', "variable ".length()))).findFirst().orElse(null);
            if (!isAst && uninit != null && misplacedTernaryAssign(lines, ln - 1, uninit) >= 0) {
                n++;
                Text.write(p, String.join("\n", lines));
                continue;
            }
            if (isAst && errs.stream().anyMatch(e -> e.msg().contains("might not have been initialized"))) {
                String ind = ast.group(1);
                s = ind + ast.group(3) + " = " + ast.group(5) + ";" + cr + "\n" + ind + ast.group(2) + "[" + ast.group(3) + "." + ast.group(4) + "()] = " + ast.group(3) + ";";
                changed = true;
                n++;
            } else if (isFe && !s.contains("(Iterable)") && !fe.group(4).contains("(Object)")
                    && errs.stream().anyMatch(e -> e.msg().contains("cannot be converted to"))) {
                s = fe.group(1) + fe.group(2) + " " + fe.group(3) + " : (Iterable<" + fe.group(2) + ">) (Iterable) (" + fe.group(4) + ")) {";
                changed = true;
                n++;
            } else {
                List<Err> sorted = new ArrayList<>(new LinkedHashSet<>(errs));
                sorted.sort(Comparator.comparingInt(Err::col).thenComparing(Err::msg).reversed());
                for (Err e : sorted) {
                    int col = e.col();
                    int[] hit = null;
                    for (String tok : new String[] {"(Object)", "(Object[])"}) {
                        int idx = s.indexOf(tok, Math.max(0, col - 1));
                        if (idx < 0) {
                            int end = Math.max(0, col);
                            idx = end - tok.length() < 0 ? -1 : s.lastIndexOf(tok, Math.min(end, s.length()) - tok.length());
                        }
                        if (idx >= 0 && Math.abs(idx - col) < 60 && (hit == null || Math.abs(idx - col) < Math.abs(hit[0] - col))) {
                            hit = new int[] {idx, tok.length()};
                        }
                    }
                    if (hit != null) {
                        s = s.substring(0, hit[0]) + s.substring(hit[0] + hit[1]);
                        changed = true;
                        n++;
                        continue;
                    }
                    Matcher mm = INCOMPATIBLE.matcher(e.msg());
                    if (!mm.find()) {
                        continue;
                    }
                    String src = mm.group(1).strip();
                    String dst = mm.group(2).strip();
                    int[] pc = precedingCast(s, col);
                    if (pc == null) {
                        pc = precedingCast(s, exprStart(s, col));
                    }
                    if (pc != null && PRIMS.contains(src)) {
                        s = s.substring(0, pc[0]) + s.substring(pc[1]).replaceFirst("^ +", "");
                        changed = true;
                        n++;
                    } else if (pc != null) {
                        s = s.substring(0, pc[1]) + "(Object)" + s.substring(pc[1]);
                        changed = true;
                        n++;
                    } else if (src.contains("<") && dst.contains("<") && !dst.contains("CAP#")) {
                        // generic arguments that only compiled against the source's types: a raw cast (unchecked) bridges them
                        int at = exprStart(s, col);
                        s = s.substring(0, at) + "(" + dst.substring(0, dst.indexOf('<')) + ") " + s.substring(at);
                        changed = true;
                        n++;
                    } else if (SIMPLE_TYPE.matcher(dst).matches()) {
                        int at = exprStart(s, col);
                        s = s.substring(0, at) + "(" + dst + ") " + s.substring(at);
                        changed = true;
                        n++;
                    }
                }
            }
            if (changed) {
                lines.set(ln - 1, s + cr);
                Text.write(p, String.join("\n", lines));
            }
        }
        return n + DeclFix.apply(log, root);
    }

    /** {start, end} of a cast written right before position col, else null. */
    static int[] precedingCast(String s, int col) {
        int j = Math.min(col, s.length());
        while (j > 0 && s.charAt(j - 1) == ' ') {
            j--;
        }
        if (j == 0 || s.charAt(j - 1) != ')') {
            return null;
        }
        int depth = 0;
        int k = j - 1;
        while (k >= 0) {
            if (s.charAt(k) == ')') {
                depth++;
            } else if (s.charAt(k) == '(') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            k--;
        }
        if (k < 0) {
            return null;
        }
        String inner = s.substring(k + 1, j - 1);
        if (!CAST_TYPE.matcher(inner).matches()) {
            return null;
        }
        return new int[] {k, j};
    }

    private static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Start of the primary expression (a.b(c).d...) javac's caret points into. */
    static int exprStart(String s, int col) {
        int j = Math.min(col, s.length());
        while (j < s.length() && ident(s.charAt(j))) {
            j++;
        }
        while (j > 0) {
            char c = s.charAt(j - 1);
            if (ident(c) || c == '.') {
                j--;
            } else if (c == ')') {
                int depth = 0;
                int k = j - 1;
                while (k >= 0) {
                    if (s.charAt(k) == ')') {
                        depth++;
                    } else if (s.charAt(k) == '(') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    k--;
                }
                if (k <= 0 || !ident(s.charAt(k - 1))) {
                    break; // a parenthesised expression or a cast: stop before it
                }
                j = k;
            } else {
                break;
            }
        }
        return j;
    }
}
