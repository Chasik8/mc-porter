package dev.porter.port.upfront;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What is known about the 1.19.2 platform (vanilla + Forge): the class hierarchy and, per class, the members it
 * declares (names as mojmap and as SRG ids). Built from a tiny v2 file (mojmap + srg namespaces; vanilla members),
 * a hierarchy table ({@code class super interfaces access}, tab separated) and, optionally, class jars. Class names
 * are dotted with {@code $} for nested classes; descriptors use internal (slash) names.
 */
public final class PlatformIndex {
    private static final Pattern SRG_ID = Pattern.compile("[mf]_\\d+_");

    /** A declared member. {@code name} is the mojmap name. */
    public record Member(boolean method, String name, int access) {
    }

    /** Result of {@link #declaring}. */
    public record Decl(String owner, Member member) {
    }

    private final Map<String, List<String>> supers = new HashMap<>();
    private final Map<String, Integer> classAccess = new HashMap<>();
    /** class -> (name + " " + desc, with the mojmap and the SRG name both present) -> member. */
    private final Map<String, Map<String, Member>> members = new HashMap<>();
    /** srg id -> {class, mojmap name, desc, "m"|"f"}; the first class that declares an id wins. */
    private final Map<String, String[]> srg = new HashMap<>();

    public static String dotted(String internal) {
        return internal.replace('/', '.');
    }

    public static String internal(String dotted) {
        return dotted.replace('.', '/');
    }

    public boolean knowsClass(String cls) {
        return supers.containsKey(cls) || members.containsKey(cls) || classAccess.containsKey(cls);
    }

    public int classAccess(String cls) {
        return classAccess.getOrDefault(cls, -1);
    }

    public List<String> supers(String cls) {
        return supers.getOrDefault(cls, List.of());
    }

    public String[] srg(String id) {
        return srg.get(id);
    }

    public int srgSize() {
        return srg.size();
    }

    public void addClass(String cls, String superName, List<String> interfaces, int access) {
        List<String> s = new ArrayList<>();
        if (superName != null && !superName.isEmpty()) {
            s.add(superName);
        }
        s.addAll(interfaces);
        supers.put(cls, s);
        if (access >= 0) {
            classAccess.put(cls, access);
        }
    }

    public void addMember(String cls, boolean method, String name, String srgName, String desc, int access) {
        Map<String, Member> m = members.computeIfAbsent(cls, k -> new HashMap<>());
        Member mem = new Member(method, name, access);
        Member old = m.get(name + " " + desc);
        if (old == null || (old.access() < 0 && access >= 0)) {
            m.put(name + " " + desc, mem);
        }
        if (srgName != null && !srgName.equals(name)) {
            m.putIfAbsent(srgName + " " + desc, m.get(name + " " + desc));
        }
    }

    /** The first class, walking {@code owner} and then its supertypes (super class first), that declares name+desc. */
    public Decl declaring(String owner, String name, String desc) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.push(owner);
        String k = name + " " + desc;
        while (!todo.isEmpty()) {
            String c = todo.pop();
            if (!seen.add(c)) {
                continue;
            }
            Map<String, Member> m = members.get(c);
            if (m != null && m.containsKey(k)) {
                return new Decl(c, m.get(k));
            }
            List<String> s = supers.getOrDefault(c, List.of());
            for (int i = s.size() - 1; i >= 0; i--) {
                todo.push(s.get(i));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ loading

    /** Tiny v2 with {@code mojmap} and {@code srg} namespaces; descriptors are remapped to mojmap class names. */
    public void loadTiny(Path tiny) throws IOException {
        List<String> lines = Files.readAllLines(tiny, StandardCharsets.UTF_8);
        String[] hdr = lines.get(0).split("\t", -1);
        if (hdr.length < 4 || !hdr[0].equals("tiny")) {
            throw new IllegalArgumentException("not a tiny v2 file: " + tiny);
        }
        List<String> ns = List.of(hdr).subList(3, hdr.length);
        int mi = ns.indexOf("mojmap");
        int si = ns.indexOf("srg");
        if (mi < 0 || si < 0) {
            throw new IllegalArgumentException("tiny file needs mojmap and srg namespaces: " + tiny + " has " + ns);
        }
        Map<String, String> toMoj = new HashMap<>();
        List<String[]> rows = new ArrayList<>(); // cls, kind, desc, moj, srg
        String cls = null;
        for (String l : lines.subList(1, lines.size())) {
            if (l.startsWith("c\t")) {
                String[] p = l.split("\t", -1);
                cls = p[1 + mi];
                toMoj.put(p[1], cls);
            } else if ((l.startsWith("\tm\t") || l.startsWith("\tf\t")) && cls != null) {
                String[] p = l.split("\t", -1);
                rows.add(new String[] {cls, p[1], p[2], p[3 + mi], p[3 + si]});
            }
        }
        for (String[] r : rows) {
            String c = dotted(r[0]);
            String desc = remap(r[2], toMoj);
            addMember(c, r[1].equals("m"), r[3], r[4], desc, -1);
            if (SRG_ID.matcher(r[4]).matches()) {
                srg.putIfAbsent(r[4], new String[] {c, r[3], desc, r[1]});
            }
        }
    }

    static String remap(String desc, Map<String, String> toMoj) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < desc.length()) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int e = desc.indexOf(';', i);
                String n = desc.substring(i + 1, e);
                sb.append('L').append(toMoj.getOrDefault(n, n)).append(';');
                i = e + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    /** {@code class<TAB>super<TAB>interfaces,comma<TAB>access}; lines starting with # are comments. */
    public void loadHier(Path tsv) throws IOException {
        for (String l : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] p = l.split("\t", -1);
            List<String> itf = new ArrayList<>();
            if (p.length > 2 && !p[2].isEmpty()) {
                for (String s : p[2].split(",")) {
                    itf.add(dotted(s.strip()));
                }
            }
            int acc = -1;
            if (p.length > 3 && p[3].matches("\\d+")) {
                acc = Integer.parseInt(p[3]);
            }
            addClass(dotted(p[0]), p.length > 1 ? dotted(p[1]) : null, itf, acc);
        }
    }

    /** Classes of a jar: hierarchy, class and member access, declared members (names as they are in the jar). */
    public void loadJar(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    addClassBytes(in.readAllBytes());
                }
            }
        }
    }

    public void addClassBytes(byte[] bytes) {
        ClassReader r = new ClassReader(bytes);
        String name = dotted(r.getClassName());
        List<String> itf = new ArrayList<>();
        for (String i : r.getInterfaces()) {
            itf.add(dotted(i));
        }
        addClass(name, r.getSuperName() == null ? null : dotted(r.getSuperName()), itf, r.getAccess());
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String n, String desc, String sig, Object v) {
                addMember(name, false, n, null, desc, access);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String n, String desc, String sig, String[] ex) {
                addMember(name, true, n, null, desc, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
