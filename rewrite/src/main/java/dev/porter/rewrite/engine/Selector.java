package dev.porter.rewrite.engine;

import dev.porter.core.rule.Rule;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code match} selector of a rule: {@code Owner#name(P1, P2)}, {@code Owner(P1)}, {@code Owner#field},
 * {@code a.b.C} or a pattern. Owners are fully qualified binary names ({@code a.b.Outer$Inner}); parameter types
 * are kept as written (simple or qualified) with generics erased.
 */
public record Selector(Kind kind, String owner, String name, List<String> params, String pattern) {

    public enum Kind { CALL, NEW, FIELD, TYPE, OVERRIDE, PATTERN, DECLARATION }

    public static final String CTOR = "<init>";

    public static Selector of(Rule.Match m) {
        if (m.call() != null) {
            return member(Kind.CALL, m.call());
        }
        if (m.newCall() != null) {
            String s = m.newCall();
            int p = s.indexOf('(');
            return new Selector(Kind.NEW, s.substring(0, p).trim(), CTOR, params(s.substring(p + 1, s.lastIndexOf(')'))), null);
        }
        if (m.field() != null) {
            String[] p = m.field().split("#", 2);
            return new Selector(Kind.FIELD, p[0].trim(), p[1].trim(), null, null);
        }
        if (m.type() != null) {
            return new Selector(Kind.TYPE, m.type().trim(), null, null, null);
        }
        if (m.override() != null) {
            return member(Kind.OVERRIDE, m.override());
        }
        if (m.declaration() != null) {
            String d = m.declaration().trim();
            int hash = d.indexOf('#');
            if (hash < 0) {
                return new Selector(Kind.DECLARATION, d, null, null, null);
            }
            int p = d.indexOf('(', hash);
            return p < 0 ? new Selector(Kind.DECLARATION, d.substring(0, hash), d.substring(hash + 1), null, null)
                    : new Selector(Kind.DECLARATION, d.substring(0, hash), d.substring(hash + 1, p).trim(),
                            params(d.substring(p + 1, d.lastIndexOf(')'))), null);
        }
        return new Selector(Kind.PATTERN, null, null, null, m.pattern());
    }

    private static Selector member(Kind kind, String s) {
        int hash = s.indexOf('#');
        int p = s.indexOf('(', hash);
        return new Selector(kind, s.substring(0, hash).trim(), s.substring(hash + 1, p).trim(),
                params(s.substring(p + 1, s.lastIndexOf(')'))), null);
    }

    /** Splits a parameter list on top-level commas and erases generics. */
    static List<String> params(String list) {
        List<String> out = new ArrayList<>();
        if (list.isBlank()) {
            return out;
        }
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            if (depth == 0 && c != '>') {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out.stream().map(t -> t.replaceAll("\\s+", "").replace("...", "[]")).toList();
    }

    /** Simple name of a (possibly qualified, possibly nested) type name: {@code a.b.C$D} -> {@code D}. */
    public static String simple(String type) {
        String t = type;
        int i = Math.max(t.lastIndexOf('.'), t.lastIndexOf('$'));
        return i < 0 ? t : t.substring(i + 1);
    }

    /** {@code a.b.C$D} -> {@code a.b.C.D} (source form). */
    public static String canonical(String binary) {
        return binary.replace('$', '.');
    }

    /** Readable form, e.g. {@code net.minecraft.nbt.CompoundTag#getInt(String)}. */
    public String display() {
        return switch (kind) {
            case CALL, OVERRIDE -> owner + "#" + name + "(" + String.join(", ", params) + ")";
            case NEW -> owner + "(" + String.join(", ", params) + ")";
            case FIELD -> owner + "#" + name;
            case TYPE -> owner;
            case PATTERN -> pattern;
            case DECLARATION -> owner + (name == null ? "" : "#" + name) + (params == null ? "" : "(" + String.join(", ", params) + ")");
        };
    }
}
