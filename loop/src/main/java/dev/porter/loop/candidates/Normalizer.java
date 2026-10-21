package dev.porter.loop.candidates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the candidate aggregation key ({@code normalized}). Two fixes of the same shape on different variables
 * get the same key:
 * <ul>
 *   <li>a pure type move ({@code import a.X;} -> {@code import b.Y;}, or {@code X} -> {@code Y}) becomes
 *       {@code type <from> -> <to>};</li>
 *   <li>everything else becomes {@code <symbol> :: <before> -> <after>} where local identifiers (lower-case names
 *       that are neither member names after a dot nor called methods) and literals of {@code before} are replaced by
 *       {@code $v0, $v1 ...} / {@code $l0 ...} in order of first appearance, consistently in {@code after}; tokens are
 *       re-joined canonically (whitespace and a trailing semicolon do not matter).</li>
 * </ul>
 */
public final class Normalizer {
    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\d[\\w.]*|[A-Za-z_$][\\w$]*|::|->|&&|\\|\\||[=!<>]=|\\S");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.$]+)\\s*;?$");
    private static final Pattern QUALIFIED = Pattern.compile("^[a-z][\\w]*(\\.[\\w$]+)+$");
    private static final Pattern SIMPLE_TYPE = Pattern.compile("^[A-Z][\\w$]*(\\.[A-Z][\\w$]*)*$");
    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var",
            "record", "yield");

    private Normalizer() {
    }

    public static String normalize(String symbol, String before, String after) {
        String b = clean(before);
        String a = clean(after);
        String typeFrom = typeOf(b, symbol);
        String typeTo = typeOf(a, null);
        if (typeFrom != null && typeTo != null && (symbol == null || !symbol.contains("#"))) {
            return "type " + typeFrom + " -> " + typeTo;
        }
        String[] parts = abstractSides(b, a);
        String key = parts[0] + " -> " + parts[1];
        return symbol == null || symbol.isBlank() ? key : symbol.strip() + " :: " + key;
    }

    /** The abstracted {before, after} of a non-type fix (what {@link #normalize} joins with " -> "). */
    public static String[] abstractSides(String before, String after) {
        Map<String, String> ph = new LinkedHashMap<>();
        int[] counters = new int[2];
        List<String> nb = abstractTokens(tokens(clean(before)), ph, counters, true);
        List<String> na = abstractTokens(tokens(clean(after)), ph, counters, false);
        return new String[] {join(nb), join(na)};
    }

    /** The {@code before}/{@code after} part of a normalized key ({@code [symbol :: ]before -> after}). */
    public static String[] sides(String normalized) {
        String s = normalized;
        int sep = s.indexOf(" :: ");
        if (sep >= 0) {
            s = s.substring(sep + 4);
        }
        int arrow = s.indexOf(" -> ");
        if (arrow < 0) {
            return new String[] {s, ""};
        }
        return new String[] {s.substring(0, arrow), s.substring(arrow + 4)};
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        while (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).strip();
        }
        return t;
    }

    /** The type a snippet names when it is only an import or only a type name; else null. */
    private static String typeOf(String s, String symbol) {
        Matcher im = IMPORT.matcher(s + ";");
        if (im.matches() && im.group(1) == null) {
            return im.group(2);
        }
        if (QUALIFIED.matcher(s).matches() && s.chars().anyMatch(Character::isUpperCase)) {
            return s;
        }
        if (SIMPLE_TYPE.matcher(s).matches()) {
            return symbol != null && !symbol.contains("#") && symbol.endsWith(s) ? symbol : s;
        }
        return null;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static List<String> abstractTokens(List<String> toks, Map<String, String> ph, int[] counters, boolean define) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            String prev = i > 0 ? toks.get(i - 1) : "";
            String next = i + 1 < toks.size() ? toks.get(i + 1) : "";
            boolean literal = t.startsWith("\"") || t.startsWith("'") || Character.isDigit(t.charAt(0));
            boolean local = Character.isLowerCase(t.charAt(0)) && Character.isJavaIdentifierStart(t.charAt(0))
                    && !KEYWORDS.contains(t) && !prev.equals(".") && !prev.equals("::") && !next.equals("(")
                    && !(next.equals(".") && isPackageChain(toks, i));
            if (ph.containsKey(t) && (literal || local)) {
                out.add(ph.get(t));
            } else if (define && literal) {
                String p = "$l" + counters[1]++;
                ph.put(t, p);
                out.add(p);
            } else if (define && local) {
                String p = "$v" + counters[0]++;
                ph.put(t, p);
                out.add(p);
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * {@code net.minecraft.Foo}: a token followed by at least one more lower-case segment and then a capitalised one
     * is a package prefix, not a local ({@code a.B} alone stays a local {@code a} with field {@code B}).
     */
    private static boolean isPackageChain(List<String> toks, int i) {
        int j = i;
        int lowerSegments = 0;
        while (j + 2 < toks.size() && toks.get(j + 1).equals(".")) {
            String seg = toks.get(j + 2);
            char c = seg.charAt(0);
            if (Character.isUpperCase(c)) {
                return lowerSegments >= 1;
            }
            if (!Character.isLowerCase(c) || (j + 3 < toks.size() && toks.get(j + 3).equals("("))) {
                return false;
            }
            lowerSegments++;
            j += 2;
        }
        return false;
    }

    private static String join(List<String> toks) {
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String t : toks) {
            if (prev != null && needsSpace(prev, t)) {
                sb.append(' ');
            }
            sb.append(t);
            prev = t;
        }
        return sb.toString();
    }

    private static boolean needsSpace(String prev, String t) {
        if (prev.equals(",")) {
            return true;
        }
        if (t.equals(",") || t.equals(".") || t.equals(")") || t.equals("]") || t.equals(";") || prev.equals(".")
                || prev.equals("(") || prev.equals("[") || t.equals("(") && word(prev) || t.equals("[")) {
            return false;
        }
        return word(prev) && word(t) || isOperator(prev) || isOperator(t);
    }

    private static boolean word(String t) {
        char c = t.charAt(0);
        return Character.isJavaIdentifierPart(c) || c == '"' || c == '\'';
    }

    private static boolean isOperator(String t) {
        return Set.of("=", "==", "!=", "<=", ">=", "&&", "||", "->", "+", "-", "*", "/", "?", ":", "|", "&", "%").contains(t);
    }
}
