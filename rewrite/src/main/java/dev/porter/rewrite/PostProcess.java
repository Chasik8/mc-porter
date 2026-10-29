package dev.porter.rewrite;

import dev.porter.rewrite.engine.Rewriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic text passes over the rewriter output that tie it to the rest of the pipeline.
 *
 * <ul>
 *   <li><b>relocate</b> ({@code --relocate from=to}): classes of the rewritten files whose package starts with
 *       {@code from} move to {@code to} (package declaration, output path), and every qualified reference to one of
 *       those classes in the rewritten files is renamed. References to classes that are not ported keep their name
 *       (they stay compile errors / TODOs, never silently redirected).</li>
 *   <li><b>stub contract</b> ({@code --stub-contract porter-generate.json}): a rewritten class that replaces a
 *       {@code porter generate} stub gets the stub's superclass, and the stub's constructor is added when no
 *       constructor with the same parameter types exists; other constructors get a
 *       {@code TODO(porter:stub-contract)} line (the generated registration code calls the stub's).</li>
 * </ul>
 */
public final class PostProcess {
    private PostProcess() {
    }

    /** A stub from a generate manifest: superclass (FQN), constructors (parameter list text, body text). */
    public record Stub(String fqn, String superclass, List<String[]> constructors) {
    }

    // ---- relocation ------------------------------------------------------------------------------------

    public record Relocation(String from, String to) {
        public static Relocation parse(String s) {
            int eq = s.indexOf('=');
            if (eq <= 0 || eq == s.length() - 1) {
                throw new IllegalArgumentException("--relocate expects <from-prefix>=<to-prefix>, got " + s);
            }
            return new Relocation(s.substring(0, eq), s.substring(eq + 1));
        }

        String apply(String fqn) {
            return fqn.startsWith(from) ? to + fqn.substring(from.length()) : null;
        }
    }

    static String fqnOf(String path) {
        String p = path.endsWith(".java") ? path.substring(0, path.length() - 5) : path;
        return p.replace('/', '.');
    }

    /** FQN → new FQN of every top-level class in the rewritten files that a relocation applies to. */
    public static Map<String, String> relocationTable(List<Rewriter.FileResult> files, List<Relocation> relocs) {
        return relocationTable(files, relocs, List.of());
    }

    /** As above; {@code known}: further classes (dotted names) that are rewritten elsewhere (another run of the same port) and relocate too. */
    public static Map<String, String> relocationTable(List<Rewriter.FileResult> files, List<Relocation> relocs, List<String> known) {
        Map<String, String> t = new TreeMap<>();
        List<String> fqns = new java.util.ArrayList<>(files.stream().map(f -> fqnOf(f.path())).toList());
        fqns.addAll(known);
        for (String fqn : fqns) {
            for (Relocation r : relocs) {
                String n = r.apply(fqn);
                if (n != null) {
                    t.put(fqn, n);
                    break;
                }
            }
        }
        return t;
    }

    /** Removes the {@code $target(...)} marks (RFC 006) once relocation is done. */
    public static Rewriter.FileResult stripMarks(Rewriter.FileResult f) {
        String t = dev.porter.rewrite.engine.Template.stripMarks(f.text());
        return t.equals(f.text()) ? f : new Rewriter.FileResult(f.path(), t, f.applied(), f.todos(), f.changed());
    }

    public static Rewriter.FileResult relocate(Rewriter.FileResult f, Map<String, String> table) {
        if (table.isEmpty()) {
            return f;
        }
        String text = f.text();
        int edits = 0;
        // longest names first so a.b.C.D never shadows a.b.C
        List<String> keys = new ArrayList<>(table.keySet());
        keys.sort((a, b) -> b.length() != a.length() ? b.length() - a.length() : a.compareTo(b));
        for (String k : keys) {
            // a name written as $target(...) by a rule (RFC 006) carries Template.TARGET_MARK: never relocated
            Matcher m = Pattern.compile("(?<![\\w.\uE000])" + Pattern.quote(k) + "(?![\\w])").matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(table.get(k)));
                edits++;
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        String path = f.path();
        String own = table.get(fqnOf(path));
        if (own != null) {
            String oldPkg = packageOf(fqnOf(path));
            String newPkg = packageOf(own);
            Matcher m = Pattern.compile("(?m)^(\\s*package\\s+)" + Pattern.quote(oldPkg) + "(\\s*;)").matcher(text);
            if (m.find()) {
                text = text.substring(0, m.start()) + m.group(1) + newPkg + m.group(2) + text.substring(m.end());
                edits++;
            }
            // classes of the old package that are not relocated were visible without import: import them explicitly
            path = own.replace('.', '/') + ".java";
        }
        if (edits == 0) {
            return f;
        }
        Map<String, Integer> applied = new TreeMap<>(f.applied());
        applied.merge("porter:relocate", edits, Integer::sum);
        return new Rewriter.FileResult(path, text, applied, f.todos(), true);
    }

    static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    // ---- import cleanup --------------------------------------------------------------------------------

    private static final Pattern TODO_IMPORT = Pattern.compile("(?m)^[ \\t]*// TODO\\(porter:[\\w-]+\\) import (?:static )?([\\w.]+)[ \\t]*\\R");

    /**
     * Imports the rewriter could not map (marked by a {@code TODO(porter:...) import X} line) are dropped, together
     * with their marker, when (a) the simple name is no longer used in the code (every use was rewritten), or (b)
     * a rule added an import of another type with the same simple name. Identical imports are deduplicated.
     * Never touches an import without a marker.
     */
    public static Rewriter.FileResult cleanImports(Rewriter.FileResult f) {
        String text = f.text();
        String code = stripForUse(text);
        Map<String, List<String>> bySimple = new TreeMap<>();
        Matcher im = IMPORT.matcher(text);
        while (im.find()) {
            bySimple.computeIfAbsent(simple(im.group(1)), k -> new ArrayList<>()).add(im.group(1));
        }
        int edits = 0;
        Matcher tm = TODO_IMPORT.matcher(text);
        List<int[]> cuts = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        while (tm.find()) {
            String fqn = tm.group(1);
            Matcher next = Pattern.compile("\\G[ \\t]*import\\s+" + Pattern.quote(fqn) + "\\s*;[ \\t]*\\R?").matcher(text);
            if (!next.find(tm.end())) {
                continue;
            }
            String s = simple(fqn);
            boolean unused = !Pattern.compile("\\b" + Pattern.quote(s) + "\\b").matcher(code).find();
            boolean shadowed = bySimple.getOrDefault(s, List.of()).stream().anyMatch(o -> !o.equals(fqn));
            if (unused || shadowed) {
                cuts.add(new int[] {tm.start(), next.end()});
                reasons.add(fqn);
            }
        }
        for (int i = cuts.size() - 1; i >= 0; i--) {
            text = text.substring(0, cuts.get(i)[0]) + text.substring(cuts.get(i)[1]);
            edits++;
        }
        // identical duplicates
        Set<String> seen = new TreeSet<>();
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher m = Pattern.compile("^import\\s+([\\w.]+)\\s*;\\s*$").matcher(line.replace("\r", ""));
            if (m.find() && !seen.add(m.group(1))) {
                edits++;
                continue;
            }
            sb.append(line).append('\n');
        }
        text = sb.substring(0, sb.length() - 1);
        if (edits == 0) {
            return f;
        }
        List<Rewriter.Todo> todos = new ArrayList<>();
        for (Rewriter.Todo t : f.todos()) {
            boolean dropped = reasons.stream().anyMatch(r -> t.detail().equals("import " + r) || t.detail().equals("import static " + r));
            if (!dropped) {
                todos.add(t);
            }
        }
        Map<String, Integer> applied = new TreeMap<>(f.applied());
        applied.merge("porter:import-cleanup", edits, Integer::sum);
        return new Rewriter.FileResult(f.path(), text, applied, todos, true);
    }

    /** Code without import lines and comments (for simple-name use checks). */
    static String stripForUse(String text) {
        String s = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("(?m)//.*$", " ");
        s = s.replaceAll("(?m)^\\s*import\\s+[\\w.*]+\\s*;", " ");
        s = s.replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;", " ");
        return s;
    }

    // ---- stub contract ---------------------------------------------------------------------------------

    private static final Pattern STUB_CLASS = Pattern.compile("class\\s+(\\w+)\\s+extends\\s+([\\w.]+)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;");

    /** Reads the stubs listed in a {@code porter-generate.json} (stub sources next to it under src/main/java). */
    public static Map<String, Stub> loadStubs(Path manifest) {
        Map<String, Stub> out = new TreeMap<>();
        try {
            JsonNode root = dev.porter.core.json.Json.json().readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            JsonNode stubs = root.get("stubs");
            if (stubs == null) {
                return out;
            }
            Path base = manifest.getParent().resolve("src/main/java");
            for (Map.Entry<String, JsonNode> e : stubs.properties()) {
                String fqn = e.getKey();
                Path p = base.resolve(fqn.replace('.', '/') + ".java");
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                out.put(fqn, parseStub(fqn, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static Stub parseStub(String fqn, String text) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher im = IMPORT.matcher(text);
        while (im.find()) {
            imports.put(simple(im.group(1)), im.group(1));
        }
        Matcher cm = STUB_CLASS.matcher(text);
        String sup = null;
        if (cm.find()) {
            sup = cm.group(2);
            if (!sup.contains(".")) {
                sup = imports.getOrDefault(sup, packageOf(fqn).isEmpty() ? sup : packageOf(fqn) + "." + sup);
            }
        }
        List<String[]> ctors = new ArrayList<>();
        Matcher km = Pattern.compile("public\\s+" + Pattern.quote(simple(fqn)) + "\\s*\\(([^)]*)\\)\\s*\\{([^}]*)\\}").matcher(text);
        while (km.find()) {
            String params = km.group(1).trim();
            // qualify parameter types through the stub's imports
            StringBuilder q = new StringBuilder();
            for (String part : params.isEmpty() ? new String[0] : params.split(",")) {
                String[] tn = part.trim().split("\\s+");
                String type = imports.getOrDefault(tn[0], tn[0]);
                q.append(q.length() == 0 ? "" : ", ").append(type).append(' ').append(tn[tn.length - 1]);
            }
            ctors.add(new String[] {q.toString(), km.group(2).trim()});
        }
        return new Stub(fqn, sup, ctors);
    }

    /** Erased simple parameter types of a parameter list ("final java.util.List<X> a, int b" → [List, int]). */
    static List<String> paramTypes(String params) {
        List<String> out = new ArrayList<>();
        String s = params.replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "").replaceAll("@\\w+(\\([^)]*\\))?", "");
        if (s.isBlank()) {
            return out;
        }
        for (String part : s.split(",")) {
            String[] tn = part.trim().replace("final ", "").trim().split("\\s+");
            String t = tn.length > 1 ? tn[tn.length - 2] : tn[0];
            out.add(simple(t.replace("...", "[]")));
        }
        return out;
    }

    public static Rewriter.FileResult stubContract(Rewriter.FileResult f, Map<String, Stub> stubs) {
        return stubContract(f, stubs, Set.of());
    }

    /**
     * As {@link #stubContract(Rewriter.FileResult, Map)}; a superclass that is itself one of the ported classes
     * ({@code ported}, FQNs after relocation) is kept (its logic would be orphaned otherwise) and a
     * {@code TODO(porter:stub-contract)} says that the generated base must become the root of that chain.
     */
    public static Rewriter.FileResult stubContract(Rewriter.FileResult f, Map<String, Stub> stubs, Set<String> ported) {
        String fqn = fqnOf(f.path());
        Stub stub = stubs.get(fqn);
        if (stub == null || stub.superclass() == null) {
            return f;
        }
        String name = simple(fqn);
        String text = f.text();
        List<Rewriter.Todo> todos = new ArrayList<>(f.todos());
        int edits = 0;
        // 1. superclass
        Matcher head = Pattern.compile("(class\\s+" + Pattern.quote(name) + "\\b(?:\\s*<[^{]*?>)?)(\\s+extends\\s+[\\w.]+(?:\\s*<[^{]*?>)?)?(?=[\\s{])")
                .matcher(text);
        if (!head.find()) {
            todos.add(new Rewriter.Todo(f.path(), 1, "stub-contract", "class header of " + name + " not found; superclass "
                    + stub.superclass() + " not applied"));
            return new Rewriter.FileResult(f.path(), text, f.applied(), todos, f.changed());
        }
        String want = " extends " + simple(stub.superclass());
        String old = head.group(2) == null ? "" : head.group(2);
        String oldType = old.isEmpty() ? null : resolve(text, fqn, old.replaceFirst("^\\s*extends\\s+", "").replaceAll("<.*", "").trim());
        boolean keep = oldType != null && ported.contains(oldType);
        if (keep) {
            int line = 1 + (int) text.substring(0, head.start()).chars().filter(ch -> ch == '\n').count();
            String detail = "superclass " + oldType + " is ported too: kept; the generated base " + stub.superclass()
                    + " must become the root of this chain (or its members moved into " + simple(oldType) + ")";
            todos.add(new Rewriter.Todo(f.path(), line, "stub-contract", detail));
            int ls = text.lastIndexOf('\n', head.start()) + 1;
            text = text.substring(0, ls) + "// TODO(porter:stub-contract) " + detail + "\n" + text.substring(ls);
        } else {
            if (!old.trim().equals(want.trim())) {
                String comment = old.isEmpty() ? "" : " /* porter:stub-contract was:" + old.replace("*/", "* /") + " */";
                text = text.substring(0, head.start()) + head.group(1) + want + comment + text.substring(head.end());
                edits++;
            }
            text = addImport(text, stub.superclass(), fqn);
            if (!old.isEmpty() && !old.trim().equals(want.trim())) {
                // drop the old superclass import when nothing else uses it
                String oldSimple = simple(old.replaceFirst("^\\s*extends\\s+", "").replaceAll("<.*", "").trim());
                String rest = text.replaceAll("(?m)^import\\s+[\\w.]+\\s*;", "").replaceAll("/\\* porter:stub-contract was:[^*]*\\*/", "");
                if (!Pattern.compile("\\b" + Pattern.quote(oldSimple) + "\\b").matcher(rest).find()) {
                    text = text.replaceAll("(?m)^import\\s+[\\w.]+\\." + Pattern.quote(oldSimple) + "\\s*;\\R", "");
                }
            }
        }
        // 2. constructors
        Pattern ctorP = Pattern.compile("(?m)^([ \\t]*)((?:public|protected|private)\\s+)?" + Pattern.quote(name) + "\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\{");
        Matcher cm = ctorP.matcher(text);
        Set<String> have = new TreeSet<>();
        String indent = "    ";
        while (cm.find()) {
            List<String> types = paramTypes(cm.group(3));
            have.add(String.join(",", types));
            indent = cm.group(1);
        }
        StringBuilder add = new StringBuilder();
        Set<String> stubSigs = new TreeSet<>();
        for (String[] c : stub.constructors()) {
            String sig = String.join(",", paramTypes(c[0]));
            stubSigs.add(sig);
            if (!have.contains(sig)) {
                String params = c[0];
                for (String q : qualifiedTypes(c[0])) {
                    text = addImport(text, q, fqn);
                    params = params.replace(q + " ", simple(q) + " ");
                }
                add.append(indent).append("/** porter:stub-contract - constructor the generated registration code calls. */\n")
                        .append(indent).append("public ").append(name).append('(').append(params).append(") {\n");
                if (!c[1].isEmpty()) {
                    add.append(indent).append("    ").append(c[1].replaceAll("\\s+", " ")).append('\n');
                }
                add.append(indent).append("}\n\n");
                edits++;
            }
        }
        // mark constructors with other signatures (iterate from the end so offsets stay valid)
        cm = ctorP.matcher(text);
        List<Integer> marks = new ArrayList<>();
        List<String> indents = new ArrayList<>();
        while (cm.find()) {
            if (!stubSigs.contains(String.join(",", paramTypes(cm.group(3))))) {
                marks.add(cm.start());
                indents.add(cm.group(1));
            }
        }
        for (int i = marks.size() - 1; i >= 0; i--) {
            int at = marks.get(i);
            int line = 1 + (int) text.substring(0, at).chars().filter(ch -> ch == '\n').count();
            String ind = indents.get(i);
            String detail = "constructor signature differs from the generated stub " + stub.constructors().stream()
                    .map(c -> "(" + c[0] + ")").toList() + "; registration code calls the stub's";
            text = text.substring(0, at) + ind + "// TODO(porter:stub-contract) " + detail + "\n" + text.substring(at);
            todos.add(new Rewriter.Todo(f.path(), line, "stub-contract", detail));
        }
        if (add.length() > 0) {
            int body = text.indexOf('{', head.start());
            text = text.substring(0, body + 1) + "\n" + add + text.substring(body + 1);
        }
        if (edits == 0 && marks.isEmpty()) {
            return new Rewriter.FileResult(f.path(), text, f.applied(), todos, f.changed() || !text.equals(f.text()));
        }
        Map<String, Integer> applied = new TreeMap<>(f.applied());
        if (edits > 0) {
            applied.merge("porter:stub-contract", edits, Integer::sum);
        }
        return new Rewriter.FileResult(f.path(), text, applied, todos, true);
    }

    private static List<String> qualifiedTypes(String params) {
        List<String> out = new ArrayList<>();
        for (String part : params.isBlank() ? new String[0] : params.split(",")) {
            String[] tn = part.trim().split("\\s+");
            if (tn[0].contains(".")) {
                out.add(tn[0]);
            }
        }
        return out;
    }

    /** FQN of a simple or qualified type name as seen from a file (explicit import, else same package). */
    static String resolve(String text, String self, String name) {
        if (name.contains(".")) {
            return name;
        }
        Matcher im = IMPORT.matcher(text);
        while (im.find()) {
            if (simple(im.group(1)).equals(name)) {
                return im.group(1);
            }
        }
        return packageOf(self).isEmpty() ? name : packageOf(self) + "." + name;
    }

    /** Adds {@code import fqn;} unless it is in the same package, java.lang, or already imported (simple name). */
    static String addImport(String text, String fqn, String self) {
        if (!fqn.contains(".") || packageOf(fqn).equals(packageOf(self)) || packageOf(fqn).equals("java.lang")) {
            return text;
        }
        String simple = simple(fqn);
        Matcher im = IMPORT.matcher(text);
        int last = -1;
        while (im.find()) {
            if (im.group(1).equals(fqn)) {
                return text;
            }
            if (simple(im.group(1)).equals(simple)) {
                // another class with that simple name is imported: replace that import (the old superclass)
                return text.substring(0, im.start()) + "import " + fqn + ";" + text.substring(im.end());
            }
            last = im.end();
        }
        if (last < 0) {
            Matcher pm = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(text);
            last = pm.find() ? pm.end() : 0;
            return text.substring(0, last) + "\n\nimport " + fqn + ";" + text.substring(last);
        }
        return text.substring(0, last) + "\nimport " + fqn + ";" + text.substring(last);
    }
}
