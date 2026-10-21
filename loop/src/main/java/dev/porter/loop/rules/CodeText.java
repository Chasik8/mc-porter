package dev.porter.loop.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text helpers that keep string literals and comments untouched. */
final class CodeText {
    private CodeText() {
    }

    /** Marks every char of {@code s} that belongs to a string/char literal or a comment. */
    static boolean[] nonCode(String s) {
        boolean[] mask = new boolean[s.length()];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int start = i;
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, s.length());
            } else {
                i++;
                continue;
            }
            for (int k = start; k < i; k++) {
                mask[k] = true;
            }
        }
        return mask;
    }

    /** Replaces every match that starts in code (not in a literal/comment). */
    static String replaceInCode(String s, Pattern p, Function<MatchResult, String> repl) {
        boolean[] mask = nonCode(s);
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (mask.length > m.start() && mask[m.start()]) {
                continue;
            }
            out.append(s, last, m.start()).append(repl.apply(m.toMatchResult()));
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    /** Start offsets of matches in code. */
    static List<Integer> findInCode(String s, Pattern p) {
        boolean[] mask = nonCode(s);
        List<Integer> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            if (!mask[m.start()]) {
                out.add(m.start());
            }
        }
        return out;
    }

    /** Index of the bracket closing the one at {@code open} (one of ( [ { <), or -1. Skips literals. */
    static int matching(String s, int open) {
        char o = s.charAt(open);
        char c = switch (o) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> '>';
        };
        boolean[] mask = nonCode(s);
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (mask[i]) {
                continue;
            }
            char ch = s.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Splits an argument list (without the outer parentheses) at top-level commas; "" -> empty list. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s.isBlank()) {
            return out;
        }
        boolean[] mask = nonCode(s);
        int depth = 0;
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            if (mask[i]) {
                continue;
            }
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(s.substring(last, i).trim());
                last = i + 1;
            }
        }
        out.add(s.substring(last).trim());
        return out;
    }

    /**
     * Scans backwards from {@code dot} (the '.' before a member name) over a receiver expression: identifier chains,
     * {@code this}, balanced (...) and [...] groups. Returns the receiver start, or -1 when it is not a simple shape
     * (e.g. {@code new Foo().bar()}).
     */
    static int receiverStart(String s, int dot) {
        int i = dot - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        int end = i;
        while (i >= 0) {
            char ch = s.charAt(i);
            if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                i--;
            } else if (ch == ')' || ch == ']') {
                int depth = 0;
                char open = ch == ')' ? '(' : '[';
                while (i >= 0) {
                    char x = s.charAt(i);
                    if (x == ch) {
                        depth++;
                    } else if (x == open) {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    i--;
                }
                if (i < 0) {
                    return -1;
                }
                i--;
            } else {
                break;
            }
        }
        int start = i + 1;
        if (start > end) {
            return -1;
        }
        // "new Foo().bar()" or "new Foo[3].length": receiver would start with the type after 'new'.
        String before = s.substring(0, start).stripTrailing();
        if (before.endsWith("new") && (before.length() == 3 || !Character.isJavaIdentifierPart(before.charAt(before.length() - 4)))) {
            return -1;
        }
        return start;
    }
}
