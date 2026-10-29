package dev.porter.port.upfront;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read side of the {@code api-index/} directory ({@code lineage.tsv}, {@code target.tsv}, {@code source-hier.tsv},
 * {@code srg-1.7.10.tsv}): the composed lineage, the target API, the source hierarchy and the SRG-to-MCP names.
 */
public final class IndexData {
    /** Composed lineage of one source symbol. */
    public record Lin(String tOwner, String tName, String tDesc, String[] statuses, double conf, String brokenAt) {
        public boolean mechanical() {
            for (String s : statuses) {
                if (!s.equals("same") && !s.equals("renamed") && !s.equals("moved")) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A target member. */
    public record Mem(String kind, String name, String desc, int access) {
        public boolean isPrivate() {
            return (access & 0x0002) != 0;
        }

        public boolean isStatic() {
            return (access & 0x0008) != 0;
        }
    }

    public static final int ACC_PRIVATE = 0x0002;

    /** key: {@code kind\towner\tname\tdesc}. */
    public final Map<String, Lin> lineage = new HashMap<>();
    /** Source class (as in the lineage) to target class: only chains of same/renamed/moved. */
    public final Map<String, String> classMap = new HashMap<>();
    public final Map<String, List<String>> targetSupers = new HashMap<>();
    public final Map<String, List<Mem>> targetMembers = new HashMap<>();
    public final Map<String, List<String>> targetBySimple = new HashMap<>();
    public final Map<String, List<String>> sourceSupers = new HashMap<>();
    public final Map<String, String> srgToMcp = new HashMap<>();
    private final Map<String, List<String>> superCache = new HashMap<>();
    private final Map<String, List<String>> tsuperCache = new HashMap<>();

    public static String lineageKey(String kind, String owner, String name, String desc) {
        return kind + "\t" + owner + "\t" + name + "\t" + desc;
    }

    public static IndexData read(Path dir) {
        IndexData d = new IndexData();
        try {
            for (String l : Files.readAllLines(dir.resolve("lineage.tsv"))) {
                String[] p = l.split("\t", -1);
                if (p.length < 10) {
                    continue;
                }
                Lin e = new Lin(p[4], p[5], p[6], p[7].split(","), Double.parseDouble(p[8]), p[9]);
                d.lineage.put(lineageKey(p[0], p[1], p[2], p[3]), e);
                if (p[0].equals("class") && p[9].isEmpty()) {
                    d.classMap.put(p[1], p[4]);
                }
            }
            for (String l : Files.readAllLines(dir.resolve("target.tsv"))) {
                String[] p = l.split("\t", -1);
                if (p[0].equals("class")) {
                    List<String> sup = new ArrayList<>();
                    if (p.length > 2 && !p[2].isEmpty()) {
                        sup.add(p[2]);
                    }
                    if (p.length > 3 && !p[3].isEmpty()) {
                        for (String s : p[3].split(",")) {
                            sup.add(s);
                        }
                    }
                    d.targetSupers.put(p[1], sup);
                    String simple = p[1].substring(p[1].lastIndexOf('.') + 1);
                    simple = simple.substring(simple.lastIndexOf('$') + 1).toLowerCase(Locale.ROOT);
                    d.targetBySimple.computeIfAbsent(simple, k -> new ArrayList<>()).add(p[1]);
                } else if (p[0].equals("member") && p.length >= 6) {
                    d.targetMembers.computeIfAbsent(p[1], k -> new ArrayList<>()).add(new Mem(p[2], p[3], p[4], Integer.parseInt(p[5])));
                }
            }
            for (String l : Files.readAllLines(dir.resolve("source-hier.tsv"))) {
                if (l.startsWith("#") || l.isEmpty()) {
                    continue;
                }
                String[] p = l.split("\t", -1);
                List<String> sup = new ArrayList<>();
                if (p.length > 1 && !p[1].isEmpty()) {
                    sup.add(p[1]);
                }
                if (p.length > 2 && !p[2].isEmpty()) {
                    for (String s : p[2].split(",")) {
                        sup.add(s);
                    }
                }
                d.sourceSupers.put(p[0], sup);
            }
            Path srg = dir.resolve("srg-1.7.10.tsv");
            if (Files.exists(srg)) {
                for (String l : Files.readAllLines(srg)) {
                    String[] p = l.split("\t", -1);
                    if (p.length >= 2 && !l.startsWith("#")) {
                        d.srgToMcp.put(p[0], p[1]);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    /** All transitive supertypes in a stable order (same walk as the reference prototype: stack, first seen first). */
    public static List<String> supers(String c, Map<String, List<String>> sup, Map<String, List<String>> cache) {
        List<String> hit = cache.get(c);
        if (hit != null) {
            return hit;
        }
        List<String> out = new ArrayList<>();
        List<String> st = new ArrayList<>();
        st.add(c);
        while (!st.isEmpty()) {
            String x = st.remove(st.size() - 1);
            for (String s : sup.getOrDefault(x, List.of())) {
                if (!out.contains(s)) {
                    out.add(s);
                    st.add(s);
                }
            }
        }
        cache.put(c, out);
        return out;
    }

    public List<String> sourceSupers(String c) {
        return supers(c, sourceSupers, superCache);
    }

    public List<String> targetSupers(String c) {
        return supers(c, targetSupers, tsuperCache);
    }

    /** Dotted FQN of a (possibly nested, {@code $}) class. */
    public boolean hasTargetClass(String c) {
        return targetSupers.containsKey(c);
    }

    /** First non-private member with this name and descriptor on the class or a supertype: {declaring class, member}. */
    public Object[] findExisting(String owner, String name, String desc) {
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(targetSupers(owner));
        for (String c : chain) {
            for (Mem m : targetMembers.getOrDefault(c, List.of())) {
                if (m.name().equals(name) && m.desc().equals(desc) && !m.isPrivate()) {
                    return new Object[] {c, m};
                }
            }
        }
        return null;
    }

    /** Names of the members that exist on the class and its supertypes. */
    public boolean hasMemberNamed(String owner, java.util.Set<String> names) {
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(targetSupers(owner));
        for (String c : chain) {
            for (Mem m : targetMembers.getOrDefault(c, List.of())) {
                if (names.contains(m.name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
