package dev.porter.port.jar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reobfuscation collision check on a built (reobfuscated) jar: concrete classes that do not implement an abstract
 * method of one of the jar's own interfaces (name + descriptor). Typical cause: a ported interface method shares its
 * MCP name with a vanilla method that the implementing class also overrides; reobf renames the class method only,
 * and the game dies with {@code AbstractMethodError}. Methods that may come from a vanilla superclass outside the
 * jar are trusted when they carry an SRG name ({@code func_}/{@code field_}) or are JDK/Object methods.
 */
public final class ObfCheck {
    private static final Set<String> OBJ = Set.of("equals", "hashCode", "toString", "clone", "finalize", "getClass", "notify",
            "notifyAll", "wait");

    private record Method(int access, String name, String desc) {
    }

    private record Cls(String name, String superName, List<String> interfaces, int access, List<Method> methods) {
    }

    private ObfCheck() {
    }

    /** Problems found ({@code class: missing itf#name desc}), sorted; empty = clean. */
    public static List<String> check(Path jar, String prefix) {
        Map<String, Cls> c = read(jar);
        List<String> bad = new ArrayList<>();
        for (Cls k : c.values()) {
            if (!k.name().startsWith(prefix) || (k.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            closure(c, k.name(), seen);
            List<String> itfs = seen.stream().filter(i -> (c.get(i).access() & Opcodes.ACC_INTERFACE) != 0).sorted().toList();
            for (String i : itfs) {
                for (Method m : c.get(i).methods()) {
                    if ((m.access() & Opcodes.ACC_ABSTRACT) != 0 && !impl(c, k.name(), m) && !defaults(c, itfs, m)) {
                        bad.add(k.name() + ": missing " + i + "#" + m.name() + m.desc());
                    }
                }
            }
        }
        return bad;
    }

    private static void closure(Map<String, Cls> c, String name, Set<String> seen) {
        Cls k = c.get(name);
        if (k == null || !seen.add(name)) {
            return;
        }
        k.interfaces().forEach(i -> closure(c, i, seen));
        if (k.superName() != null) {
            closure(c, k.superName(), seen);
        }
    }

    private static boolean impl(Map<String, Cls> c, String name, Method want) {
        while (name != null && c.containsKey(name)) {
            for (Method m : c.get(name).methods()) {
                if (m.name().equals(want.name()) && m.desc().equals(want.desc()) && (m.access() & Opcodes.ACC_ABSTRACT) == 0) {
                    return true;
                }
            }
            name = c.get(name).superName();
        }
        String nm = want.name();
        return nm.startsWith("func_") || nm.startsWith("field_") || OBJ.contains(nm) || (name != null && name.startsWith("java/"));
    }

    private static boolean defaults(Map<String, Cls> c, List<String> itfs, Method want) {
        for (String i : itfs) {
            for (Method m : c.get(i).methods()) {
                if (m.name().equals(want.name()) && m.desc().equals(want.desc()) && (m.access() & Opcodes.ACC_ABSTRACT) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Cls> read(Path jar) {
        Map<String, Cls> out = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader r = new ClassReader(in.readAllBytes());
                    List<Method> methods = new ArrayList<>();
                    r.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            methods.add(new Method(access, name, descriptor));
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    out.put(r.getClassName(), new Cls(r.getClassName(), r.getSuperName(), List.of(r.getInterfaces()), r.getAccess(), methods));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
