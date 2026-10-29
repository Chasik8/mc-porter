package dev.porter.port.passes;

import dev.porter.port.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic whole-tree source passes that run after {@code porter rewrite} on the staged closure. None of them
 * contains mod content; each is a transformation the rule language cannot express (it adds or removes members or
 * declarations).
 * <ul>
 *   <li>{@code srgmoj}: SRG member names the rules left ({@code m_123_}/{@code f_123_}) -> source-version mojmap
 *       names (the adapters implement the source API by mojmap name);</li>
 *   <li>{@code java8}: Java 9-16 syntax -> Java 8 (+Jabel): private interface methods, records,
 *       {@code toArray(X[]::new)};</li>
 *   <li>{@code tector}: no-arg constructors for block entities (1.7.10 creates tile entities reflectively);</li>
 *   <li>{@code imports}: unused single-type imports dropped;</li>
 *   <li>{@code edits}: a counted list of regex call-site edits from the port config (fails when one stops
 *       matching).</li>
 * </ul>
 */
public final class SourcePasses {
    private SourcePasses() {
    }

    /** Applies {@code fn} to every .java file under root; writes changed files; returns the number of changed files. */
    static int eachFile(Path root, java.util.function.BiFunction<Path, String, String> fn) {
        int changed = 0;
        for (Path p : Text.javaFiles(root)) {
            String s = Text.read(p);
            String t = fn.apply(p, s);
            if (!t.equals(s)) {
                Text.write(p, t);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------------------------------------ srgmoj

    private static final Pattern SRG = Text.re("\\b([mf]_\\d+_)\\b");

    /** Renames SRG member ids to mojmap names; returns the number of renamed occurrences. */
    public static int srgToMojmap(Path root, SrgMap map) {
        int[] n = {0};
        eachFile(root, (p, s) -> Text.sub(SRG, m -> {
            String[] v = map.get(m.group(1));
            if (v == null) {
                return m.group(1);
            }
            n[0]++;
            return v[1];
        }, s));
        return n[0];
    }

    // ------------------------------------------------------------------------------------------------- java8

    /** Counts of the java8 pass. */
    public record Java8Counts(int privateInterfaceMethods, int records, int toArray) {
    }

    private static final Pattern INTERFACE = Text.re("\\binterface\\s+\\w+[^{;]*\\{");
    private static final Pattern PRIVATE = Text.re("(\\s*)private(\\s+static)?(\\s+)");
    private static final Pattern RECORD = Text.re(
            "\\b((?:public|protected|private|static|final|\\s)*)record\\s+(\\w+)\\s*(<[^>]*>)?\\s*\\(([^)]*)\\)\\s*(implements[^{]*)?\\{");
    private static final Pattern COMPONENT_SPLIT = Text.re(",(?![^<]*>)");
    private static final Pattern TO_ARRAY = Text.re("\\.toArray\\(\\s*([\\w.]+(?:<[^>]*>)?)\\[\\]::new\\s*\\)");
    private static final Pattern GENERIC_TAIL = Text.re("<.*");

    private static final Pattern STREAM_OPS = Text.re("stream\\(\\)|\\bStream\\.|\\.map\\(|\\.filter\\(|\\.flatMap\\(|\\.sorted\\(|\\.distinct\\(");

    /** Stream.toArray(IntFunction) is Java 8 API: keep X[]::new when the statement before it is a stream pipeline. */
    static boolean streamPipeline(String t, int at) {
        int st = Math.max(t.lastIndexOf(';', at), Math.max(t.lastIndexOf('{', at), t.lastIndexOf('}', at)));
        return STREAM_OPS.matcher(t.substring(st + 1, at)).find();
    }

    public static Java8Counts java8(Path root) {
        int[] c = new int[3];
        eachFile(root, (p, s) -> {
            String t = s;
            if (t.contains("interface") && t.contains("private")) {
                t = fixPrivate(t, c);
            }
            if (t.contains("record")) {
                t = fixRecords(t, c);
            }
            if (t.contains("[]::new")) {
                final String src = t;
                String t2 = Text.sub(TO_ARRAY, m -> streamPipeline(src, m.start()) ? m.group(0)
                        : ".toArray(new " + GENERIC_TAIL.matcher(m.group(1)).replaceAll("") + "[0])", t);
                if (!t2.equals(t)) {
                    c[2] += count(t2, "[0])") - count(t, "[0])");
                    t = t2;
                }
            }
            return t;
        });
        return new Java8Counts(c[0], c[1], c[2]);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    /** Index of the brace closing the one at {@code s[i]} (strings, chars and comments skipped), or -1. */
    static int findClose(String s, int i) {
        int d = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                i = s.indexOf('\n', i);
                i = i < 0 ? n : i;
                continue;
            }
            if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
                continue;
            }
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
                if (d == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private record Edit(int from, int to, String text) {
    }

    private static String fixPrivate(String s, int[] c) {
        List<Edit> edits = new ArrayList<>();
        Matcher im = INTERFACE.matcher(s);
        while (im.find()) {
            int a = im.end() - 1;
            int b = findClose(s, a);
            String body = b < 0 ? s.substring(a + 1, Math.max(a + 1, s.length() - 1)) : s.substring(a + 1, b);
            int i = 0;
            Matcher pm = PRIVATE.matcher(body);
            while (i < body.length()) {
                char ch = body.charAt(i);
                if (ch == '{') {
                    int j = findClose(body, i);
                    if (j < 0) {
                        break;
                    }
                    i = j + 1;
                    continue;
                }
                pm.region(i, body.length());
                if (pm.lookingAt() && (i == 0 || ";}\n\r\t {".indexOf(body.charAt(i - 1)) >= 0)) {
                    int pos = a + 1 + i + pm.group(1).length();
                    String st = pm.group(2);
                    edits.add(new Edit(pos, pos + "private".length() + (st == null ? 0 : st.length()) + pm.group(3).length(),
                            st != null ? "static " : "default "));
                    i += pm.group().length();
                    continue;
                }
                i++;
            }
        }
        edits.sort(Comparator.comparingInt(Edit::from).thenComparingInt(Edit::to).thenComparing(Edit::text).reversed());
        for (Edit e : edits) {
            s = s.substring(0, e.from()) + e.text() + s.substring(e.to());
            c[0]++;
        }
        return s;
    }

    /**
     * True when the record body declares the canonical constructor: {@code Name(<n params>) {} or the compact
     * {@code Name {}. The record's other constructors (another parameter count, they call this(...)) do not count.
     */
    static boolean hasCanonicalConstructor(String body, String name, int components) {
        if (Text.re("(?<!new )(?<![\\w.])" + name + "\\s*\\{").matcher(body).find()) {
            return true;
        }
        Matcher m = Text.re("(?<!new )(?<![\\w.])" + name + "\\s*\\(").matcher(body);
        while (m.find()) {
            int depth = 0;
            int angle = 0;
            int commas = 0;
            boolean any = false;
            int i = m.end() - 1;
            for (; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    if (--depth == 0) {
                        break;
                    }
                } else if (ch == '<') {
                    angle++;
                } else if (ch == '>') {
                    angle--;
                } else if (ch == ',' && depth == 1 && angle == 0) {
                    commas++;
                } else if (!Character.isWhitespace(ch)) {
                    any = true;
                }
            }
            if (i >= body.length()) {
                continue;
            }
            int n = any ? commas + 1 : 0;
            int j = i + 1;
            while (j < body.length() && Character.isWhitespace(body.charAt(j))) {
                j++;
            }
            if (j < body.length() && body.charAt(j) == '{' && n == components) {
                return true;
            }
        }
        return false;
    }

    private static String fixRecords(String s, int[] c) {
        while (true) {
            Matcher m = RECORD.matcher(s);
            if (!m.find()) {
                return s;
            }
            String mods = m.group(1);
            String name = m.group(2);
            String gen = m.group(3) == null ? "" : m.group(3);
            String comps = m.group(4);
            String impl = m.group(5) == null ? "" : m.group(5);
            int ob = m.end() - 1;
            int cb = findClose(s, ob);
            String body = cb < 0 ? s.substring(ob + 1, Math.max(ob + 1, s.length() - 1)) : s.substring(ob + 1, cb);
            List<String[]> fields = new ArrayList<>();
            for (String part : COMPONENT_SPLIT.split(comps, -1)) {
                part = part.strip();
                if (!part.isEmpty()) {
                    fields.add(rsplitWs(part));
                }
            }
            StringBuilder out = new StringBuilder();
            for (String[] f : fields) {
                out.append("\n    private final ").append(f[0]).append(' ').append(f[1]).append(';');
            }
            if (!hasCanonicalConstructor(body, name, fields.size())) {
                out.append("\n    public ").append(name).append('(');
                for (int i = 0; i < fields.size(); i++) {
                    out.append(i > 0 ? ", " : "").append(fields.get(i)[0]).append(' ').append(fields.get(i)[1]);
                }
                out.append(") {");
                fields.forEach(f -> out.append(" this.").append(f[1]).append(" = ").append(f[1]).append(';'));
                out.append(" }");
            }
            for (String[] f : fields) {
                if (!Text.re("\\b" + f[1] + "\\s*\\(\\s*\\)\\s*\\{").matcher(body).find()) {
                    out.append("\n    public ").append(f[0]).append(' ').append(f[1]).append("() { return this.").append(f[1]).append("; }");
                }
            }
            if (!body.contains("equals(")) {
                List<String> eq = fields.stream().map(f -> "java.util.Objects.equals(this." + f[1] + ", o." + f[1] + ")").toList();
                out.append("\n    @Override public boolean equals(Object obj) { if (this == obj) return true; if (!(obj instanceof ")
                        .append(name).append(")) return false; ").append(name).append(" o = (").append(name).append(") obj; return ")
                        .append(eq.isEmpty() ? "true" : String.join(" && ", eq)).append("; }");
            }
            if (!body.contains("hashCode(")) {
                out.append("\n    @Override public int hashCode() { return java.util.Objects.hash(")
                        .append(String.join(", ", fields.stream().map(f -> f[1]).toList())).append("); }");
            }
            if (!body.contains("toString(")) {
                out.append("\n    @Override public String toString() { return \"").append(name).append('[')
                        .append(String.join(", ", fields.stream().map(f -> f[1] + "=\" + " + f[1] + " + \"").toList()))
                        .append("]\"; }");
            }
            String mods2 = mods.contains("final") ? mods : mods + "final ";
            String before = s.substring(0, m.start());
            String stat = mods2.contains("static") ? "" : (count(before, "{") > count(before, "}") ? "static " : "");
            String repl = mods2 + stat + "class " + name + gen + " " + impl + "{" + out + "\n" + body + "}";
            s = before + repl + (cb < 0 ? s.substring(s.length()) : s.substring(cb + 1));
            c[1]++;
        }
    }

    /** Python {@code s.rsplit(None, 1)} for a stripped, non-empty string with at least one space. */
    static String[] rsplitWs(String p) {
        int i = p.length() - 1;
        while (i >= 0 && !Character.isWhitespace(p.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return new String[] {p, p};
        }
        int j = i;
        while (j >= 0 && Character.isWhitespace(p.charAt(j))) {
            j--;
        }
        return new String[] {p.substring(0, j + 1), p.substring(i + 1)};
    }

    // ------------------------------------------------------------------------------------------------ tector

    /** Adds {@code public X() { this(new BlockPos(0, 0, 0), null); }} to block entities; returns the count. */
    public static int tileConstructors(Path root) {
        int[] n = {0};
        eachFile(root, (p, s) -> {
            String f = p.getFileName().toString();
            String cname = f.substring(0, f.length() - 5);
            String q = Pattern.quote(cname);
            Matcher m = Text.re("\\n(public |protected )?((?:final )?)class " + q + "\\b[^{]*\\{").matcher(s);
            if (!m.find() || s.substring(m.start(), m.end()).split("class", -1)[0].contains("abstract")) {
                return s;
            }
            boolean ctor = Text.re("\\n    (public |protected )?" + q + "\\(\\s*BlockPos \\w+,\\s*BlockState \\w+\\s*\\)\\s*\\{").matcher(s).find();
            if (!ctor || Text.re("\\n    (public |protected |private )?" + q + "\\(\\s*\\)").matcher(s).find()) {
                return s;
            }
            n[0]++;
            String ins = "\n    public " + cname + "() {\n        this(new BlockPos(0, 0, 0), null); // porter:tector (1.7.10 creates tiles reflectively)\n    }\n";
            return s.substring(0, m.end()) + ins + s.substring(m.end());
        });
        return n[0];
    }

    // ----------------------------------------------------------------------------------------------- imports

    private static final Pattern IMP = Text.re("^[ \\t]*import[ \\t]+(?!static)([\\w.]+)[ \\t]*;[ \\t]*\\r?\\n", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Text.re("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Text.re("//[^\\n]*");
    private static final Pattern WORD = Text.re("\\b\\w+\\b");
    private static final Pattern STRING_LITERAL = Text.re("\"(?:\\\\.|[^\"\\\\\\n])*\"");

    /** Drops single-type imports whose simple name is not used; returns the number dropped. */
    public static int unusedImports(Path root) {
        int[] n = {0};
        eachFile(root, (p, s) -> {
            String code = BLOCK_COMMENT.matcher(s).replaceAll(" ");
            code = LINE_COMMENT.matcher(code).replaceAll(" ");
            code = STRING_LITERAL.matcher(code).replaceAll("\"\""); // a word in a string is no use of an import
            code = IMP.matcher(code).replaceAll("");
            Set<String> words = new HashSet<>();
            Matcher w = WORD.matcher(code);
            while (w.find()) {
                words.add(w.group());
            }
            return Text.sub(IMP, m -> {
                String fq = m.group(1);
                String simple = fq.substring(fq.lastIndexOf('.') + 1);
                if (words.contains(simple)) {
                    return m.group();
                }
                n[0]++;
                return "";
            }, s);
        });
        return n[0];
    }

    // ------------------------------------------------------------------------------------------------- edits

    /** One counted call-site edit: Python-style regex (DOTALL) and replacement template, applied once. */
    public record SourceEdit(String file, String pattern, String replace, String reason) {
    }

    /** Applies the edits in order; throws when one no longer matches. */
    public static int edits(Path root, List<SourceEdit> edits) {
        int n = 0;
        for (SourceEdit e : edits) {
            Path p = root.resolve(e.file());
            if (!Files.exists(p)) {
                throw new IllegalStateException("edit target missing: " + e.file() + " (" + e.reason() + ")");
            }
            String s = Text.read(p);
            Text.SubResult r = Text.subn(Text.re(e.pattern(), Pattern.DOTALL), e.replace(), s, 1);
            if (r.count() != 1) {
                throw new IllegalStateException("edit no longer matches: " + e.file() + ": " + e.reason());
            }
            Text.write(p, r.text());
            n++;
        }
        return n;
    }

    /** Edits file: a YAML/JSON list of {file, pattern, replace, reason}. */
    public static List<SourceEdit> readEdits(Path file) {
        tools.jackson.databind.JsonNode root = file.toString().endsWith(".json")
                ? dev.porter.core.json.Json.json().readTree(Text.read(file))
                : dev.porter.core.json.Json.yaml().readTree(Text.read(file));
        tools.jackson.databind.JsonNode list = root.isArray() ? root : root.path("edits");
        List<SourceEdit> out = new ArrayList<>();
        for (tools.jackson.databind.JsonNode e : list) {
            out.add(new SourceEdit(e.path("file").asString(), e.path("pattern").asString(), e.path("replace").asString(""),
                    e.path("reason").asString("")));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ asset namespace

    /**
     * One namespace move of asset locations: {@code new ResourceLocation("<from>", "<path>...")} with a path starting
     * with one of {@code paths} gets namespace {@code to}. For ports that ship the original mod's textures under their
     * own namespace (generate {@code --asset-namespace}) while registry and recipe ids keep the original one.
     */
    public record AssetNamespace(String from, String to, List<String> paths) {
    }

    /** Applies the namespace moves to every Java file below {@code root}; returns the number of locations moved. */
    public static int assetNamespaces(Path root, List<AssetNamespace> moves) {
        int n = 0;
        for (Path p : Text.javaFiles(root)) {
            String s = Text.read(p);
            String t = s;
            for (AssetNamespace m : moves) {
                String alts = m.paths().isEmpty() ? "" : "(?:" + String.join("|", m.paths().stream().map(Pattern::quote).toList()) + ")";
                Pattern pat = Text.re("new ResourceLocation\\(\\s*\"" + Pattern.quote(m.from()) + "\"\\s*,\\s*\"(" + alts + ")");
                Matcher mm = pat.matcher(t);
                StringBuilder sb = new StringBuilder();
                while (mm.find()) {
                    mm.appendReplacement(sb, Matcher.quoteReplacement("new ResourceLocation(\"" + m.to() + "\", \"" + mm.group(1)));
                    n++;
                }
                mm.appendTail(sb);
                t = sb.toString();
            }
            if (!t.equals(s)) {
                Text.write(p, t);
            }
        }
        return n;
    }

    static Map<String, Object> noop() {
        return Map.of();
    }
}
