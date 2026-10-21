package dev.porter.port.closure;

import dev.porter.port.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Known and semantically identical?" check of the closure: compares the declared API of two versions of one class
 * after normalisation (comments, annotations, imports and whitespace dropped; member modifiers and parameter names
 * dropped; generic arguments kept). Equal header + equal member signature sets = identical, and the class is left
 * to the target side instead of being copied.
 */
public final class ApiCompare {
    private static final Pattern BLOCK_COMMENT = Text.re("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Text.re("//[^\\n]*");
    private static final Pattern ANNOTATION = Text.re("@\\w+(\\([^)]*\\))?");
    private static final Pattern IMPORT = Text.re("^\\s*(import|package)[^;]*;", Pattern.MULTILINE);
    private static final Pattern WS = Text.re("\\s+");
    private static final Pattern HEADER = Text.re("\\b(class|interface|enum)\\s+(\\w+)([^{]*)\\{");
    private static final Pattern METHOD = Text.re("([\\w<>\\[\\],.? ]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(throws [\\w., ]+)?$");
    private static final Pattern FIELD = Text.re("([\\w<>\\[\\],.?]+)\\s+(\\w+)\\s*(=.*)?$");
    private static final Pattern PARAM_SPLIT = Text.re(",(?![^<]*>)");
    private static final Set<String> MODS = Set.of("public", "private", "protected", "static", "final", "abstract", "default",
            "synchronized", "native", "transient", "volatile");

    private ApiCompare() {
    }

    static String normalise(String src) {
        String s = BLOCK_COMMENT.matcher(src).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = ANNOTATION.matcher(s).replaceAll(" ");
        s = IMPORT.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ");
    }

    /** Normalised declared API (header + members) of one source file. */
    public static Set<String> signatures(String src) {
        String s = normalise(src);
        Set<String> out = new TreeSet<>();
        Matcher hdr = HEADER.matcher(s);
        if (!hdr.find()) {
            out.add("unparsed");
            return out;
        }
        out.add("header|" + hdr.group(1) + "|" + hdr.group(3).replaceAll("\\s", ""));
        String body = s.substring(hdr.end());
        int depth = 0;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{') {
                if (depth == 0) {
                    Matcher m = METHOD.matcher(buf.toString().strip());
                    if (m.find()) {
                        out.add(method(m));
                    }
                }
                depth++;
                buf.setLength(0);
            } else if (ch == '}') {
                depth--;
                buf.setLength(0);
            } else if (ch == ';' && depth == 0) {
                String decl = buf.toString().strip();
                buf.setLength(0);
                Matcher m = METHOD.matcher(decl);
                if (m.find()) {
                    out.add(method(m));
                    continue;
                }
                Matcher f = FIELD.matcher(decl);
                if (f.find()) {
                    out.add("f|" + f.group(1) + "|" + f.group(2));
                }
            } else if (depth == 0) {
                buf.append(ch);
            }
        }
        return out;
    }

    private static String method(Matcher m) {
        return "m|" + ptype(m.group(1)) + "|" + m.group(2) + "|" + String.join(",", params(m.group(3)));
    }

    private static String ptype(String t) {
        List<String> w = new ArrayList<>();
        for (String x : t.strip().split("\\s+")) {
            if (!x.isEmpty() && !MODS.contains(x)) {
                w.add(x);
            }
        }
        return String.join(" ", w);
    }

    private static List<String> params(String p) {
        List<String> out = new ArrayList<>();
        for (String x : PARAM_SPLIT.split(p, -1)) {
            x = x.strip();
            if (x.isEmpty()) {
                continue;
            }
            int sp = x.lastIndexOf(' ');
            String type = sp < 0 ? x : x.substring(0, sp);
            List<String> w = new ArrayList<>();
            for (String y : type.strip().split("\\s+")) {
                if (!y.isEmpty() && !y.equals("final")) {
                    w.add(y);
                }
            }
            out.add(String.join(" ", w));
        }
        return out;
    }

    /** True when both sources declare the same normalised API. */
    public static boolean identical(String a, String b) {
        return signatures(a).equals(signatures(b));
    }
}
