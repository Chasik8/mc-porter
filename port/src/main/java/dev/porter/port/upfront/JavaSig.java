package dev.porter.port.upfront;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** JVM descriptors to Java source types and parameter names (spec WP7 step 3). */
public final class JavaSig {
    private JavaSig() {
    }

    /** Maps a dotted binary class name (nested with {@code $}) to a source name; never null. */
    public interface Resolver {
        String javaName(String binaryName);
    }

    /** A parsed method descriptor. */
    public record Desc(List<String> params, String ret) {
    }

    public static Desc method(String desc, Resolver r) {
        int[] pos = {1};
        List<String> params = new ArrayList<>();
        while (desc.charAt(pos[0]) != ')') {
            params.add(next(desc, pos, r));
        }
        pos[0]++;
        String ret = desc.charAt(pos[0]) == 'V' ? "void" : next(desc, pos, r);
        return new Desc(params, ret);
    }

    public static String field(String desc, Resolver r) {
        return next(desc, new int[] {0}, r);
    }

    private static String next(String d, int[] pos, Resolver r) {
        int dims = 0;
        while (d.charAt(pos[0]) == '[') {
            dims++;
            pos[0]++;
        }
        char c = d.charAt(pos[0]++);
        String t;
        switch (c) {
            case 'B' -> t = "byte";
            case 'C' -> t = "char";
            case 'D' -> t = "double";
            case 'F' -> t = "float";
            case 'I' -> t = "int";
            case 'J' -> t = "long";
            case 'S' -> t = "short";
            case 'Z' -> t = "boolean";
            case 'L' -> {
                int end = d.indexOf(';', pos[0]);
                t = r.javaName(d.substring(pos[0], end).replace('/', '.'));
                pos[0] = end + 1;
            }
            default -> throw new IllegalArgumentException("bad descriptor: " + d);
        }
        return t + "[]".repeat(dims);
    }

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "self");

    /** A deterministic parameter name from the type: {@code BlockPos -> blockPos}, {@code int -> i}; unique in {@code used}. */
    public static String paramName(String type, Set<String> used) {
        String base = switch (type) {
            case "boolean" -> "flag";
            case "byte" -> "b";
            case "short" -> "s";
            case "char" -> "c";
            case "int" -> "i";
            case "long" -> "l";
            case "float" -> "f";
            case "double" -> "d";
            default -> {
                boolean arr = type.endsWith("[]");
                String t = type.replace("[]", "");
                t = t.substring(t.lastIndexOf('.') + 1);
                String n = t.isEmpty() ? "arg" : Character.toLowerCase(t.charAt(0)) + t.substring(1);
                yield arr ? n + "s" : n;
            }
        };
        if (KEYWORDS.contains(base)) {
            base = base.equals("class") ? "clazz" : base + "_";
        }
        String name = base;
        for (int n = 2; !used.add(name); n++) {
            name = base + n;
        }
        return name;
    }

    /** Capitalised first letter, for getter and setter names. */
    public static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
