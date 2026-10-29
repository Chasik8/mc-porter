package dev.porter.port.passes;

import dev.porter.port.Text;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds call sites in kept (ported) code that target a member of a hub stub whose body is inert
 * ({@code // porter:hub-stub inert}). Such a call compiles but does nothing, so a feature (sounds, item icons in a GUI)
 * is silently lost; the list makes that visible at build time. Deterministic, text based on the installed sources:
 * a stub is any file with the inert marker, a call is {@code Stub.member(} (static) or {@code .member(} in a file that
 * names the stub's class (instance, less certain).
 */
public final class InertHubCalls {
    public static final String MARK = "porter:hub-stub inert";

    private static final Pattern PACKAGE = Text.re("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern INERT_METHOD = Text.re("([\\w$]+)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{[^{}]*?" + Pattern.quote(MARK));
    private static final Pattern CLIENT = Text.re("client|render|gui|screen|sound|audio|icon|texture|particle");
    private static final Pattern COMMENTS = Text.re("(?s)/\\*.*?\\*/|//[^\\n]*");

    /** One call site. */
    public record Hit(String stub, String member, String file, int line, String kind, boolean client) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stub", stub);
            m.put("member", member);
            m.put("file", file);
            m.put("line", line);
            m.put("kind", kind);
            m.put("client", client);
            return m;
        }
    }

    private InertHubCalls() {
    }

    /** Scans every java file under {@code root}; the result is sorted (file, line). */
    public static List<Hit> scan(Path root) {
        List<Path> files = Text.javaFiles(root);
        Map<String, Map<String, Boolean>> stubs = new HashMap<>(); // fqn -> inert member names
        Map<Path, String> texts = new HashMap<>();
        for (Path f : files) {
            texts.put(f, Text.read(f));
        }
        for (Path f : files) {
            String t = texts.get(f);
            if (!t.contains(MARK)) {
                continue;
            }
            String simple = f.getFileName().toString().replaceAll("\\.java$", "");
            Matcher pk = PACKAGE.matcher(t);
            String fqn = (pk.find() ? pk.group(1) + "." : "") + simple;
            Map<String, Boolean> members = new HashMap<>();
            Matcher m = INERT_METHOD.matcher(t);
            while (m.find()) {
                if (!m.group(1).equals(simple)) {
                    members.put(m.group(1), true);
                }
            }
            if (!members.isEmpty()) {
                stubs.put(fqn, members);
            }
        }
        List<Hit> out = new ArrayList<>();
        for (Path f : new TreeSet<>(files)) {
            String raw = texts.get(f);
            if (raw.contains(MARK)) {
                continue;
            }
            String rel = root.relativize(f).toString().replace('\\', '/');
            String t = blankComments(raw);
            String[] code = t.split("\n", -1);
            for (Map.Entry<String, Map<String, Boolean>> e : stubs.entrySet()) {
                String fqn = e.getKey();
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                Pattern named = Text.re("\\b" + Pattern.quote(simple) + "\\b");
                if (!named.matcher(t).find()) {
                    continue;
                }
                for (String member : new TreeSet<>(e.getValue().keySet())) {
                    Pattern st = Text.re("\\b" + Pattern.quote(simple) + "\\s*\\.\\s*" + Pattern.quote(member) + "\\s*\\(");
                    Pattern inst = Text.re("\\.\\s*" + Pattern.quote(member) + "\\s*\\(");
                    for (int i = 0; i < code.length; i++) {
                        boolean s = st.matcher(code[i]).find();
                        if (s || inst.matcher(code[i]).find()) {
                            String lc = (rel + " " + simple + " " + member).toLowerCase(java.util.Locale.ROOT);
                            out.add(new Hit(fqn, member, rel, i + 1, s ? "static" : "instance", CLIENT.matcher(lc).find()));
                        }
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparing(Hit::file).thenComparingInt(Hit::line).thenComparing(Hit::stub).thenComparing(Hit::member));
        return out;
    }

    /** Replaces comment characters by spaces, keeping newlines, so line numbers stay. */
    static String blankComments(String s) {
        Matcher m = COMMENTS.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            for (char c : m.group().toCharArray()) {
                sb.append(c == '\n' ? '\n' : ' ');
            }
            last = m.end();
        }
        return sb.append(s.substring(last)).toString();
    }

    /** One line per stub member with its call count, for the end-of-run warning. */
    public static List<String> summary(List<Hit> hits) {
        Map<String, Integer> n = new java.util.TreeMap<>();
        for (Hit h : hits) {
            n.merge(h.stub() + "#" + h.member() + (h.client() ? " [client]" : ""), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        n.forEach((k, v) -> out.add(k + " x" + v));
        return out;
    }
}
