package dev.porter.port;

import java.util.List;

/** A package-prefix move {@code from=to} (the rewriter's {@code --relocate}), e.g. {@code com.example.=mymod.ported.}. */
public record Relocation(String from, String to) {
    public static Relocation parse(String s) {
        int eq = s.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("relocation must be <from>=<to>: " + s);
        }
        return new Relocation(s.substring(0, eq), s.substring(eq + 1));
    }

    /** Applies the first relocation whose prefix matches; unchanged otherwise. */
    public static String apply(List<Relocation> rs, String fqn) {
        for (Relocation r : rs) {
            if (fqn.startsWith(r.from)) {
                return r.to + fqn.substring(r.from.length());
            }
        }
        return fqn;
    }

    /**
     * Relocates every qualified name in a Java expression ({@code new a.b.C(a.b.D.X, "a.b.")}); string and character
     * literals are left alone.
     */
    public static String applyInSource(List<Relocation> rs, String src) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                .matcher(src);
        int last = 0;
        while (m.find()) {
            out.append(src, last, m.start());
            String t = m.group();
            out.append(t.startsWith("\"") || t.startsWith("'") ? t : apply(rs, t));
            last = m.end();
        }
        return out.append(src.substring(last)).toString();
    }

    /** Source path ({@code a/b/C.java}) of a relocated top-level class. */
    public static String path(List<Relocation> rs, String fqn) {
        return apply(rs, fqn).replace('.', '/') + ".java";
    }

    @Override
    public String toString() {
        return from + "=" + to;
    }
}
