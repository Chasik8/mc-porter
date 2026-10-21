import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a mojmap -> SRG member mapping (TSRG v1) for Minecraft 1.19.2 from
 * Mojang's official mappings (client.txt, ProGuard format, mojmap -> obf) and MCPConfig's joined.tsrg
 * (TSRG2 "obf srg id", obf -> srg). Class names stay mojmap (Forge 1.17+ runtime keeps official class names).
 *
 * Usage (Java 17 single-file launcher): java -cp asm.jar MakeMojToSrg.java client.txt joined.tsrg out.tsrg [srg.jar]
 * With srg.jar only members that exist in that jar are written (the server jar lacks client-only members, and
 * ForgeAutoRenamingTool fails on mapped members it cannot find).
 *
 * The output is used with ForgeAutoRenamingTool: forwards to reobfuscate the test mod (mojmap -> srg),
 * reversed (--reverse) to turn the SRG-named Forge server jars into a mojmap compile classpath.
 */
public class MakeMojToSrg {
    record Cls(String moj, String obf, Map<String, String> fields, Map<String, String> methods) {
    }

    public static void main(String[] args) throws IOException {
        List<String> pg = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        List<String> ts = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);

        // ---- ProGuard: moj -> obf
        Map<String, Cls> byMoj = new TreeMap<>();
        Map<String, String> mojToObfClass = new HashMap<>();
        Cls cur = null;
        // methods: moj name + java signature, resolved after all classes are known
        Map<String, List<String[]>> pendingMethods = new HashMap<>();
        for (String line : pg) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                String[] p = line.substring(0, line.length() - 1).split(" -> ");
                cur = new Cls(p[0].replace('.', '/'), p[1].replace('.', '/'), new TreeMap<>(), new TreeMap<>());
                byMoj.put(cur.moj, cur);
                mojToObfClass.put(cur.moj, cur.obf);
                pendingMethods.put(cur.moj, new java.util.ArrayList<>());
                continue;
            }
            String t = line.trim();
            String[] p = t.split(" -> ");
            String left = p[0];
            String obf = p[1];
            if (left.contains("(")) {
                // [a:b:]ret name(args)[:x:y]
                String s = left;
                while (Character.isDigit(s.charAt(0))) {
                    s = s.substring(s.indexOf(':') + 1);
                }
                String ret = s.substring(0, s.indexOf(' '));
                String name = s.substring(s.indexOf(' ') + 1, s.indexOf('('));
                String argList = s.substring(s.indexOf('(') + 1, s.indexOf(')'));
                pendingMethods.get(cur.moj).add(new String[] {name, ret, argList, obf});
            } else {
                String name = left.substring(left.indexOf(' ') + 1);
                cur.fields.put(name, obf);
            }
        }

        // ---- TSRG2: obf -> srg
        Map<String, String> fieldSrg = new HashMap<>(); // obfClass/obfName -> srg
        Map<String, String> methodSrg = new HashMap<>(); // obfClass/obfName/obfDesc -> srg
        String obfClass = null;
        for (String line : ts) {
            if (line.startsWith("tsrg2") || line.isBlank()) {
                continue;
            }
            if (line.startsWith("\t\t")) {
                continue; // parameters / static marker
            }
            if (line.startsWith("\t")) {
                String[] p = line.substring(1).split(" ");
                if (p.length >= 3 && p[1].startsWith("(")) {
                    methodSrg.put(obfClass + "/" + p[0] + "/" + p[1], p[2]);
                } else {
                    fieldSrg.put(obfClass + "/" + p[0], p[1]);
                }
                continue;
            }
            obfClass = line.split(" ")[0];
        }

        java.util.Set<String> present = args.length > 3 ? members(Path.of(args[3])) : null;
        StringBuilder out = new StringBuilder();
        int nf = 0;
        int nm = 0;
        for (Cls c : byMoj.values()) {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> f : c.fields.entrySet()) {
                String srg = fieldSrg.get(c.obf + "/" + f.getValue());
                if (srg != null && !srg.equals(f.getKey()) && (present == null || present.contains(c.moj + "." + srg))) {
                    body.append('\t').append(f.getKey()).append(' ').append(srg).append('\n');
                    nf++;
                }
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String[] m : pendingMethods.get(c.moj)) {
                String mojDesc = desc(m[2], m[1], null);
                String obfDesc = desc(m[2], m[1], mojToObfClass);
                String srg = methodSrg.get(c.obf + "/" + m[3] + "/" + obfDesc);
                if (srg != null && !srg.equals(m[0]) && (present == null || present.contains(c.moj + "." + srg + mojDesc))
                        && seen.add(m[0] + mojDesc)) {
                    body.append('\t').append(m[0]).append(' ').append(mojDesc).append(' ').append(srg).append('\n');
                    nm++;
                }
            }
            out.append(c.moj).append(' ').append(c.moj).append('\n').append(body);
        }
        Files.writeString(Path.of(args[2]), out.toString(), StandardCharsets.UTF_8);
        System.out.println("classes " + byMoj.size() + ", fields " + nf + ", methods " + nm);
    }

    static java.util.Set<String> members(Path jar) throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements();) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] b = z.getInputStream(e).readAllBytes();
                new org.objectweb.asm.ClassReader(b).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    String name;

                    @Override
                    public void visit(int v, int a, String n, String sig, String sup, String[] itf) {
                        name = n;
                    }

                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                        out.add(name + "." + n);
                        return null;
                    }

                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                        out.add(name + "." + n + d);
                        return null;
                    }
                }, org.objectweb.asm.ClassReader.SKIP_CODE);
            }
        }
        return out;
    }

    static String desc(String args, String ret, Map<String, String> classMap) {
        StringBuilder sb = new StringBuilder("(");
        if (!args.isEmpty()) {
            for (String a : args.split(",")) {
                sb.append(type(a, classMap));
            }
        }
        return sb.append(')').append(type(ret, classMap)).toString();
    }

    static String type(String t, Map<String, String> classMap) {
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        String base = switch (t) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                String n = t.replace('.', '/');
                if (classMap != null) {
                    n = classMap.getOrDefault(n, n);
                }
                yield "L" + n + ";";
            }
        };
        return "[".repeat(dims) + base;
    }
}
