package dev.porter.graph.match;

import dev.porter.graph.model.Sym;

/** Descriptor translation between adjacent versions through the class matches. */
public final class Descs {
    private Descs() {
    }

    /**
     * Translates an obf descriptor of version {@code r.a} into {@code r.b}'s obf space. Classes outside the jar
     * (JDK, libraries) stay as they are. Returns null if a referenced Minecraft class has no match.
     */
    public static String translate(String desc, PairResult r) {
        StringBuilder sb = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String cls = desc.substring(i + 1, end);
                Sym ca = r.a.classByObf(cls);
                if (ca != null) {
                    Sym cb = r.matchA(ca);
                    if (cb == null) {
                        return null;
                    }
                    cls = cb.obfName;
                } else if (r.b.classByObf(cls) != null) {
                    return null; // a library class name that collides with an obf name of b
                }
                sb.append('L').append(cls).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
