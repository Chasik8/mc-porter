package dev.porter.port.upfront;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JVM descriptor helpers. */
final class Descs {
    private static final Pattern ONE = Pattern.compile("\\[*(?:L[^;]+;|[ZBCSIJFDV])");

    private Descs() {
    }

    /** Parameter types of a method descriptor, or {@code null} for a field descriptor. */
    static List<String> params(String desc) {
        if (!desc.startsWith("(")) {
            return null;
        }
        String a = desc.substring(1, desc.indexOf(')'));
        List<String> out = new ArrayList<>();
        Matcher m = ONE.matcher(a);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Return type of a method descriptor; the descriptor itself for a field. */
    static String ret(String desc) {
        return desc.startsWith("(") ? desc.substring(desc.indexOf(')') + 1) : desc;
    }

    /** Maps the class inside a type descriptor through the class map, the concept owners and the hand type rules. */
    static String mapType(String t, Map<String, String> cmap, Map<String, String> conceptOwners, Map<String, String> typeRuleClasses) {
        int i = 0;
        while (i < t.length() && t.charAt(i) == '[') {
            i++;
        }
        String base = t.substring(i);
        if (!base.startsWith("L")) {
            return t;
        }
        String c = base.substring(1, base.length() - 1).replace('/', '.');
        String m = cmap.get(c);
        if (m == null) {
            m = conceptOwners.get(c);
        }
        if (m == null) {
            m = typeRuleClasses.get(c.replace('$', '.'));
        }
        if (m != null) {
            c = m;
        }
        return t.substring(0, i) + "L" + c.replace('.', '/') + ";";
    }
}
