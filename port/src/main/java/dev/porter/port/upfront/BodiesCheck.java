package dev.porter.port.upfront;

import dev.porter.port.upfront.model.SymbolKey;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;

/**
 * {@code porter bodies check}: splices the bodies DB into shimgen's output and verifies every filled body with ONE
 * {@code javac --release 8} run against the 1.7.10 jar and the adapter pack. Errors are mapped back to the key whose
 * body (or import) produced the line. Passing bodies get {@code verified: compile}; failing ones are listed.
 *
 * <p>Bodies that do not parse are parsed out first (a syntax error would otherwise hide the semantic errors of every
 * other body); they are replaced by their TODO stub for the compile run. Fragments ({@code fragments/*.members.java})
 * are compiled as members of a standalone class, so a body there cannot use other members of its shim.
 */
public final class BodiesCheck {
    private static final Pattern MARK = Pattern.compile("^\\s*//\\s*porter:body\\s+(\\S+)(\\s+set)?\\s*$");
    private static final Pattern END = Pattern.compile("^\\s*//\\s*porter:end\\s*$");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+[\\w.]+\\s*;.*$");
    private static final Pattern FRAG_IMPORT = Pattern.compile("^//\\s*import\\s+([\\w.*]+)\\s*;\\s*$");
    private static final String HELPER_KEY = "(helper)";

    /** Outcome of a check. {@code failed} maps key to the first error message. */
    public record Result(int bodies, List<SymbolKey> ok, Map<SymbolKey, String> failed, List<SymbolKey> orphans,
                         int todo, List<String> genErrors, long javacMillis) {
        public boolean success() {
            return failed.isEmpty() && genErrors.isEmpty();
        }
    }

    private static final class Spliced {
        final String rel;
        final boolean fragment;
        List<String> lines = new ArrayList<>();
        List<SymbolKey> lineKey = new ArrayList<>();
        Path written;

        Spliced(String rel, boolean fragment) {
            this.rel = rel;
            this.fragment = fragment;
        }
    }

    private BodiesCheck() {
    }

    /** Loads body files: each path is a YAML file or a directory of them. A key may occur once overall. */
    public static Map<SymbolKey, BodyEntry> load(List<Path> sources, Map<SymbolKey, Path> fileOf) throws IOException {
        Map<SymbolKey, BodyEntry> out = new LinkedHashMap<>();
        for (Path p : sources) {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(p)) {
                files.add(p);
            } else {
                throw new IllegalArgumentException("no such bodies file or directory: " + p);
            }
            for (Path f : files) {
                for (BodyEntry e : BodiesDb.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString())) {
                    if (out.put(e.key(), e) != null) {
                        throw new IllegalArgumentException("duplicate body key " + e.key() + " in " + f + " and " + fileOf.get(e.key()));
                    }
                    fileOf.put(e.key(), f);
                }
            }
        }
        return out;
    }

    /**
     * @param gen shimgen output directory ({@code src/}, {@code fragments/}, {@code todo.tsv})
     * @param classpath the 1.7.10 jar and the adapter pack (jars or class directories)
     * @param helpers optional directory of generic helper sources compiled together (may be null)
     * @param todoTsv optional todo list; the reported todo count is its rows without a body
     * @param work scratch directory for the spliced sources (kept, so line numbers can be looked up)
     */
    public static Result check(Map<SymbolKey, BodyEntry> bodies, Path gen, List<Path> classpath, Path helpers, Path todoTsv,
                               Path work) throws IOException {
        List<Spliced> files = new ArrayList<>();
        Path srcDir = gen.resolve("src");
        if (Files.isDirectory(srcDir)) {
            try (Stream<Path> s = Files.walk(srcDir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    files.add(new Spliced("src/" + srcDir.relativize(p).toString().replace('\\', '/'), false));
                }
            }
        }
        Path fragDir = gen.resolve("fragments");
        if (Files.isDirectory(fragDir)) {
            try (Stream<Path> s = Files.list(fragDir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".members.java")).sorted().toList()) {
                    files.add(new Spliced("fragments/" + p.getFileName(), true));
                }
            }
        }
        Map<String, List<String>> original = new HashMap<>();
        for (Spliced f : files) {
            original.put(f.rel, Files.readAllLines(gen.resolve(f.rel), StandardCharsets.UTF_8));
        }

        Set<SymbolKey> present = new HashSet<>();
        Set<SymbolKey> unfilled = new LinkedHashSet<>();
        for (Spliced f : files) {
            for (String l : original.get(f.rel)) {
                Matcher m = MARK.matcher(l);
                if (m.matches() && m.group(2) == null) {
                    SymbolKey k = tryParse(m.group(1));
                    if (k != null) {
                        (bodies.containsKey(k) ? present : unfilled).add(k);
                    }
                }
            }
        }

        deleteTree(work);
        Files.createDirectories(work);
        List<Path> helperFiles = new ArrayList<>();
        if (helpers != null && Files.isDirectory(helpers)) {
            try (Stream<Path> s = Files.walk(helpers)) {
                s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(helperFiles::add);
            }
        }

        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) {
            throw new IllegalStateException("no system Java compiler: run porter on a JDK");
        }
        Map<SymbolKey, String> failed = new LinkedHashMap<>();
        List<String> genErrors = new ArrayList<>();
        Set<SymbolKey> skip = new HashSet<>();

        // pass 1: each body alone, syntax only; unparsable bodies are named and stubbed out for the compile
        long t0 = System.currentTimeMillis();
        for (SymbolKey k : present) {
            String err = syntaxError(jc, bodies.get(k).body());
            if (err == null && !bodies.get(k).setBody().isBlank()) {
                String e2 = syntaxError(jc, bodies.get(k).setBody());
                err = e2 == null ? null : "set-body " + e2;
            }
            if (err != null) {
                failed.put(k, "syntax: " + err);
            }
        }
        skip.addAll(failed.keySet());
        splice(files, original, bodies, skip, work);
        List<Path> all = new ArrayList<>(helperFiles);
        files.forEach(f -> all.add(f.written));

        // pass 2: the one compile
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            jc.getTask(null, fm, diags, options(classpath, classes), null, fm.getJavaFileObjectsFromPaths(all)).call();
        }
        long ms = System.currentTimeMillis() - t0;
        attribute(diags, files, failed, genErrors, helperFiles);

        List<SymbolKey> ok = new ArrayList<>();
        for (SymbolKey k : present) {
            if (!failed.containsKey(k)) {
                ok.add(k);
            }
        }
        ok.sort(Comparator.comparing(SymbolKey::toString));
        return new Result(bodies.size(), ok, failed, orphans(bodies, present), countTodo(todoTsv, bodies, unfilled), genErrors, ms);
    }

    private static String syntaxError(JavaCompiler jc, String body) throws IOException {
        String text = "class P {\n void m() {\n" + body + "\n }\n}\n";
        JavaFileObject src = new javax.tools.SimpleJavaFileObject(URI.create("string:///P.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return text;
            }
        };
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(d, null, StandardCharsets.UTF_8)) {
            ((JavacTask) jc.getTask(null, fm, d, List.of("--release", "8", "-proc:none", "-Xlint:-options"), null, List.of(src))).parse();
        }
        for (Diagnostic<? extends JavaFileObject> x : d.getDiagnostics()) {
            if (x.getKind() == Diagnostic.Kind.ERROR) {
                return "line " + (x.getLineNumber() - 2) + ": " + x.getMessage(java.util.Locale.ROOT).replace('\n', ' ');
            }
        }
        return null;
    }

    private static List<SymbolKey> orphans(Map<SymbolKey, BodyEntry> bodies, Set<SymbolKey> present) {
        List<SymbolKey> out = new ArrayList<>();
        for (SymbolKey k : bodies.keySet()) {
            if (!present.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static int countTodo(Path todoTsv, Map<SymbolKey, BodyEntry> bodies, Set<SymbolKey> unfilled) throws IOException {
        if (todoTsv != null && Files.isRegularFile(todoTsv)) {
            int n = 0;
            for (TodoEntry e : TodoEntry.read(todoTsv)) {
                if (!bodies.containsKey(e.key())) {
                    n++;
                }
            }
            return n;
        }
        return unfilled.size();
    }

    private static List<String> options(List<Path> classpath, Path out) {
        List<String> o = new ArrayList<>(List.of("--release", "8", "-proc:none", "-nowarn", "-Xlint:-options", "-Xmaxerrs", "100000",
                "-encoding", "UTF-8", "-d", out.toString()));
        if (!classpath.isEmpty()) {
            o.add("-cp");
            o.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        return o;
    }

    private static void attribute(DiagnosticCollector<JavaFileObject> diags, List<Spliced> files, Map<SymbolKey, String> failed,
                                  List<String> genErrors, List<Path> helperFiles) {
        Map<Path, Spliced> byPath = new HashMap<>();
        for (Spliced f : files) {
            byPath.put(f.written.toAbsolutePath().normalize(), f);
        }
        Set<Path> helpers = new HashSet<>();
        helperFiles.forEach(h -> helpers.add(h.toAbsolutePath().normalize()));
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            String msg = d.getMessage(java.util.Locale.ROOT).replace('\n', ' ');
            Path p = null;
            if (d.getSource() != null) {
                URI u = d.getSource().toUri();
                try {
                    p = Path.of(u).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    p = null;
                }
            }
            Spliced f = p == null ? null : byPath.get(p);
            long line = d.getLineNumber();
            SymbolKey key = f != null && line >= 1 && line <= f.lineKey.size() ? f.lineKey.get((int) line - 1) : null;
            if (key != null) {
                failed.putIfAbsent(key, "line " + line + ": " + msg);
            } else {
                String where = f != null ? f.rel : p != null && helpers.contains(p) ? HELPER_KEY + " " + p.getFileName() : String.valueOf(p);
                genErrors.add(where + ":" + line + ": " + msg);
            }
        }
    }

    /** Builds each file's spliced text and writes it to {@code work/spliced/<rel>}. */
    private static void splice(List<Spliced> files, Map<String, List<String>> original, Map<SymbolKey, BodyEntry> bodies,
                               Set<SymbolKey> skip, Path work) throws IOException {
        for (Spliced f : files) {
            List<String> src = original.get(f.rel);
            f.lines = new ArrayList<>();
            f.lineKey = new ArrayList<>();
            // imports needed by the bodies of this file
            Map<String, SymbolKey> imports = new LinkedHashMap<>();
            Set<String> have = new HashSet<>();
            for (String l : src) {
                String t = l.trim();
                if (t.startsWith("import ")) {
                    have.add(t);
                }
                Matcher m = MARK.matcher(l);
                if (m.matches()) {
                    SymbolKey k = tryParse(m.group(1));
                    BodyEntry e = k == null ? null : bodies.get(k);
                    if (e != null && !skip.contains(k) && (m.group(2) == null || !e.setBody().isBlank())) {
                        for (String i : e.imports()) {
                            imports.putIfAbsent(i, k);
                        }
                    }
                }
            }
            if (f.fragment) {
                for (String l : src) {
                    Matcher m = FRAG_IMPORT.matcher(l.trim());
                    if (m.matches()) {
                        add(f, "import " + m.group(1) + ";", null);
                    }
                }
                for (Map.Entry<String, SymbolKey> i : imports.entrySet()) {
                    add(f, "import " + i.getKey() + ";", i.getValue());
                }
                // members of an interface shim are 'default' methods: compile them inside an interface
                boolean itf = src.stream().anyMatch(l -> l.trim().startsWith("default "));
                add(f, (itf ? "interface " : "abstract class ") + fragmentClass(f.rel) + " {", null);
            }
            boolean importsDone = f.fragment;
            for (int i = 0; i < src.size(); i++) {
                String l = src.get(i);
                Matcher m = MARK.matcher(l);
                if (m.matches() && tryParse(m.group(1)) != null && bodies.containsKey(tryParse(m.group(1)))
                        && !skip.contains(tryParse(m.group(1)))
                        && (m.group(2) == null || !bodies.get(tryParse(m.group(1))).setBody().isBlank())) {
                    SymbolKey k = tryParse(m.group(1));
                    String text = m.group(2) == null ? bodies.get(k).body() : bodies.get(k).setBody();
                    add(f, l, null);
                    int j = i + 1;
                    while (j < src.size() && !END.matcher(src.get(j)).matches()) {
                        j++;
                    }
                    for (String b : text.split("\n", -1)) {
                        add(f, b.isEmpty() ? "" : "        " + b, k);
                    }
                    i = j - 1;
                    continue;
                }
                add(f, l, null);
                if (!importsDone && PACKAGE.matcher(l).matches()) {
                    importsDone = true;
                    for (Map.Entry<String, SymbolKey> e : imports.entrySet()) {
                        if (!have.contains("import " + e.getKey() + ";")) {
                            add(f, "import " + e.getKey() + ";", e.getValue());
                        }
                    }
                }
            }
            if (f.fragment) {
                add(f, "}", null);
            }
            f.written = work.resolve("spliced").resolve(f.rel + (f.fragment ? ".java" : ""));
            if (f.fragment) {
                f.written = work.resolve("spliced").resolve("fragments").resolve(
                        fragmentClass(f.rel) + ".java");
            }
            Files.createDirectories(f.written.getParent());
            Files.writeString(f.written, String.join("\n", f.lines) + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Name of the class a fragment ({@code fragments/<Shim>.members.java}) is compiled in: the shim's simple name, so
     * that constructors in the fragment are valid declarations; a generic name if the file name is no identifier.
     */
    static String fragmentClass(String rel) {
        String n = rel.substring(rel.lastIndexOf('/') + 1).replaceAll("\\.members\\.java$", "");
        return n.matches("[A-Za-z_][A-Za-z0-9_]*") ? n : "PorterFragment" + Math.abs(rel.hashCode());
    }

    private static void add(Spliced f, String line, SymbolKey key) {
        f.lines.add(line);
        f.lineKey.add(key);
    }

    private static SymbolKey tryParse(String s) {
        try {
            return SymbolKey.parse(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * Sets {@code verified:} of the entries in the given files: {@code compile} for the keys in {@code ok} (an entry
     * already {@code scenario} stays), {@code none} for the failed ones that were {@code compile}. Comments and all
     * other text stay as they are.
     */
    public static int markVerified(Map<SymbolKey, Path> fileOf, Map<SymbolKey, BodyEntry> bodies, Collection<SymbolKey> ok,
                                   Collection<SymbolKey> failed) throws IOException {
        Map<Path, Map<SymbolKey, String>> perFile = new TreeMap<>();
        for (SymbolKey k : ok) {
            BodyEntry e = bodies.get(k);
            if (e != null && e.verified().equals("none")) {
                perFile.computeIfAbsent(fileOf.get(k), x -> new HashMap<>()).put(k, "compile");
            }
        }
        for (SymbolKey k : failed) {
            BodyEntry e = bodies.get(k);
            if (e != null && e.verified().equals("compile")) {
                perFile.computeIfAbsent(fileOf.get(k), x -> new HashMap<>()).put(k, "none");
            }
        }
        int changed = 0;
        for (Map.Entry<Path, Map<SymbolKey, String>> pf : perFile.entrySet()) {
            List<String> lines = Files.readAllLines(pf.getKey(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            List<String> doc = new ArrayList<>();
            for (String l : lines) {
                if (l.equals("---") || l.startsWith("--- ")) {
                    changed += flushDoc(doc, out, pf.getValue());
                    out.add(l);
                } else {
                    doc.add(l);
                }
            }
            changed += flushDoc(doc, out, pf.getValue());
            Files.writeString(pf.getKey(), String.join("\n", out) + "\n", StandardCharsets.UTF_8);
        }
        return changed;
    }

    private static int flushDoc(List<String> doc, List<String> out, Map<SymbolKey, String> want) {
        int changed = 0;
        List<BodyEntry> parsed = BodiesDb.parse(String.join("\n", doc), "doc");
        if (parsed.size() == 1 && want.containsKey(parsed.get(0).key())) {
            String v = want.get(parsed.get(0).key());
            boolean set = false;
            for (int i = 0; i < doc.size(); i++) {
                if (doc.get(i).startsWith("verified:")) {
                    doc.set(i, "verified: " + v);
                    set = true;
                    break;
                }
            }
            if (!set) {
                int end = doc.size();
                while (end > 0 && doc.get(end - 1).isBlank()) {
                    end--;
                }
                doc.add(end, "verified: " + v);
            }
            changed = 1;
        }
        out.addAll(doc);
        doc.clear();
        return changed;
    }
}
