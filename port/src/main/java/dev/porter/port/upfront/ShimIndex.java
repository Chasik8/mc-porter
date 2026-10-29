package dev.porter.port.upfront;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Public members of the existing shim sources (spec WP7 step 1): shimgen never generates a member the shim already
 * has. A regex scan is enough: members are keyed by name, arity and the simple names of the parameter types.
 * Nested classes are folded into their file's top-level class.
 */
public final class ShimIndex {

    /** One shim source file. */
    public static final class Info {
        public final String fqn;
        public final String simple;
        public final boolean isInterface;
        /** {@code name(Type,Type)}; constructors use {@code <init>}. */
        public final Set<String> methods = new HashSet<>();
        public final Set<String> fields = new HashSet<>();

        Info(String fqn, String simple, boolean isInterface) {
            this.fqn = fqn;
            this.simple = simple;
            this.isInterface = isInterface;
        }
    }

    private final Map<String, Info> byFqn = new TreeMap<>();
    private final Map<String, Info> bySimple = new TreeMap<>();

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|enum)\\s+(\\w+)");
    private static final String MODS = "(?:(?:static|final|abstract|default|synchronized|native)\\s+)*";
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(public\\s+)?" + MODS + "(?:<[^>]*>\\s+)?([\\w.]+(?:<[^;{}()]*>)?(?:\\[\\])*)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*public\\s+" + MODS + "[\\w.]+(?:<[^;{}()=]*>)?(?:\\[\\])*\\s+(\\w+)\\s*(?:=|;)");

    /** Scans every {@code .java} file below the given roots; the first file with a simple name wins. */
    public static ShimIndex scan(List<Path> roots) throws IOException {
        ShimIndex idx = new ShimIndex();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path f : files) {
                idx.add(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString());
            }
        }
        return idx;
    }

    /** Adds one source text (file name decides the top-level class name). */
    public void add(String src, String fileName) {
        String simple = fileName.replaceAll("\\.java$", "");
        if (simple.endsWith(".members") || bySimple.containsKey(simple)) {
            return;
        }
        Matcher pm = PACKAGE.matcher(src);
        String pkg = pm.find() ? pm.group(1) : "";
        boolean isItf = false;
        Matcher tm = TYPE_DECL.matcher(src);
        while (tm.find()) {
            if (tm.group(2).equals(simple)) {
                isItf = tm.group(1).equals("interface");
                break;
            }
        }
        Info info = new Info(pkg.isEmpty() ? simple : pkg + "." + simple, simple, isItf);
        for (String line : src.split("\n")) {
            Matcher fm = FIELD.matcher(line);
            if (fm.find()) {
                info.fields.add(fm.group(1));
            }
            Matcher mm = METHOD.matcher(line);
            if (mm.find() && (mm.group(1) != null || isItf) && !isKeyword(mm.group(2))) {
                info.methods.add(mm.group(3) + "(" + simpleParams(mm.group(4)) + ")");
            } else {
                Matcher cm = Pattern.compile("^\\s*(?:public|protected)\\s+" + Pattern.quote(simple) + "\\s*\\(([^)]*)\\)").matcher(line);
                if (cm.find()) {
                    info.methods.add("<init>(" + simpleParams(cm.group(1)) + ")");
                }
            }
        }
        byFqn.put(info.fqn, info);
        bySimple.put(simple, info);
    }

    private static boolean isKeyword(String s) {
        return s.equals("return") || s.equals("new") || s.equals("throw") || s.equals("else");
    }

    /** Parameter list text to {@code Type,Type} of simple type names (generics dropped, varargs as arrays). */
    static String simpleParams(String params) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (char ch : params.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        for (String p : parts) {
            String t = p.replaceAll("@\\w+(\\([^)]*\\))?", " ").replaceAll("<[^<>]*(<[^<>]*>[^<>]*)*>", "").trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean varargs = t.contains("...");
            t = t.replace("...", "[]").replaceAll("\\bfinal\\b", " ").trim();
            String[] tok = t.split("\\s+");
            String type = tok.length >= 2 ? tok[tok.length - 2] : tok[0];
            if (tok.length >= 2 && tok[tok.length - 1].startsWith("[")) {
                type = tok[tok.length - 2] + tok[tok.length - 1];
            }
            if (varargs && !type.endsWith("[]")) {
                type += "[]";
            }
            out.add(simpleName(type));
        }
        return String.join(",", out);
    }

    /** Last dotted segment, array suffix kept. */
    public static String simpleName(String type) {
        int dims = 0;
        String t = type;
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
            dims++;
        }
        t = t.substring(t.lastIndexOf('.') + 1);
        return t + "[]".repeat(dims);
    }

    public Info bySimple(String simple) {
        return bySimple.get(simple);
    }

    public Info byFqn(String fqn) {
        return byFqn.get(fqn);
    }

    public int size() {
        return byFqn.size();
    }
}
