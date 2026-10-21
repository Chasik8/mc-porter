package dev.porter.rewrite.engine;

import java.util.ArrayList;
import java.util.List;

/** JVM descriptor helpers. Class names come out in binary form ({@code a.b.Outer$Inner}). */
public final class Descriptors {
    private Descriptors() {
    }

    public static List<String> params(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int[] end = new int[1];
            out.add(type(desc, i, end));
            i = end[0];
        }
        return out;
    }

    public static String returnType(String desc) {
        return type(desc, desc.indexOf(')') + 1, new int[1]);
    }

    private static String type(String d, int i, int[] end) {
        int dims = 0;
        while (d.charAt(i) == '[') {
            dims++;
            i++;
        }
        String base;
        char c = d.charAt(i);
        if (c == 'L') {
            int j = d.indexOf(';', i);
            base = d.substring(i + 1, j).replace('/', '.');
            i = j + 1;
        } else {
            base = switch (c) {
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'S' -> "short";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                case 'V' -> "void";
                default -> throw new IllegalArgumentException("bad descriptor " + d);
            };
            i++;
        }
        end[0] = i;
        return base + "[]".repeat(dims);
    }
}
