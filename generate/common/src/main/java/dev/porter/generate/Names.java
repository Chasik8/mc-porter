package dev.porter.generate;

import java.util.Locale;
import java.util.Set;

/** Deterministic name derivation: resource ids → Java identifiers. */
public final class Names {
    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "true", "false", "null", "var", "record", "yield");

    private Names() {
    }

    public static String namespace(String id) {
        int c = id.indexOf(':');
        return c < 0 ? "minecraft" : id.substring(0, c);
    }

    public static String path(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    /** {@code copper_press} / {@code machines/copper_press} → {@code CopperPress} / {@code MachinesCopperPress}. */
    public static String pascal(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                up = true;
            }
        }
        if (b.isEmpty() || Character.isDigit(b.charAt(0))) {
            b.insert(0, 'N');
        }
        return b.toString();
    }

    /** Same as {@code porter assets} NS_CAMEL: {@code steam_turbine} → {@code steamTurbine}; other chars kept. */
    public static String lowerCamel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    /** Java field name in lower camel case, e.g. {@code copperPress}. */
    /** Words for display: {@code potion_brew} → {@code Potion Brew}, {@code minValue} → {@code Min Value}. */
    public static String humanize(String s) {
        StringBuilder b = new StringBuilder();
        boolean start = true;
        char prev = 0;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-' || c == '/' || c == '.' || c == ' ') {
                start = true;
            } else {
                if (!start && Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                    start = true;
                }
                if (start && b.length() > 0) {
                    b.append(' ');
                }
                b.append(start ? Character.toUpperCase(c) : c);
                start = false;
            }
            prev = c;
        }
        return b.toString();
    }

    public static String field(String s) {
        String p = pascal(s);
        String f = Character.toLowerCase(p.charAt(0)) + p.substring(1);
        return KEYWORDS.contains(f) ? f + "_" : f;
    }

    /** {@code copper_press} → {@code COPPER_PRESS}. */
    public static String constant(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(Character.isLetterOrDigit(c) && c < 128 ? Character.toUpperCase(c) : '_');
        }
        String r = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) {
            r = "N" + r;
        }
        return r;
    }

    /** Java identifier for a free-form name (packet fields, palette entries), keywords suffixed with {@code _}. */
    public static String ident(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' ? c : '_');
        }
        String r = b.toString();
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) {
            r = "_" + r;
        }
        return KEYWORDS.contains(r) ? r + "_" : r;
    }

    /** Package-safe segment from a mod id. */
    public static String packageSegment(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) {
            r = "m" + r;
        }
        return KEYWORDS.contains(r) ? r + "_" : r;
    }

    /** Simple name of a fully qualified (binary) class name. */
    public static String simpleName(String fqn) {
        String s = fqn.substring(fqn.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    /** Package of a fully qualified class name ("" for the default package). */
    public static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }
}
