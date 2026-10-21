package dev.porter.loop.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, text-level facts about one Java source file: package, single-type imports, the supertypes of its
 * classes. Enough to qualify the simple names javac prints; no parsing beyond regular expressions.
 */
public final class SourceFacts {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.$]+)(\\.\\*)?\\s*;");
    private static final Pattern SUPER = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+\\w+(?:<[^{]*?>)?(?:\\([^)]*\\))?\\s*((?:extends|implements)[^{]*)\\{");

    private final String text;
    private final List<String> lines;
    private final String pkg;
    private final Map<String, String> imports = new LinkedHashMap<>();
    private final List<String> wildcardImports = new ArrayList<>();
    private final List<String> supertypes = new ArrayList<>();

    public SourceFacts(String text) {
        this.text = text;
        this.lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        Matcher pm = PACKAGE.matcher(text);
        this.pkg = pm.find() ? pm.group(1) : "";
        Matcher im = IMPORT.matcher(text);
        while (im.find()) {
            if (im.group(1) != null) {
                continue;
            }
            String name = im.group(2);
            if (im.group(3) != null) {
                wildcardImports.add(name);
            } else {
                imports.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        Matcher sm = SUPER.matcher(text);
        while (sm.find()) {
            for (String part : sm.group(1).replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "")
                    .split("\\bextends\\b|\\bimplements\\b|,")) {
                String t = part.trim();
                if (!t.isEmpty() && t.matches("[\\w.$]+")) {
                    supertypes.add(t);
                }
            }
        }
    }

    public static SourceFacts read(Path file) {
        try {
            return new SourceFacts(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new SourceFacts("");
        }
    }

    public String text() {
        return text;
    }

    /** 1-based line, or "" when out of range. */
    public String line(int line) {
        return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
    }

    public int lineCount() {
        return lines.size();
    }

    public String packageName() {
        return pkg;
    }

    public Map<String, String> imports() {
        return imports;
    }

    /** Supertypes named in extends/implements clauses (simple or qualified, as written). */
    public List<String> supertypes() {
        return supertypes;
    }

    /**
     * Qualifies a simple (or Outer.Inner) name through the single-type imports; returns the input unchanged when the
     * name is already qualified or cannot be resolved without guessing.
     */
    public String qualify(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String base = name.replaceAll("<.*", "").replace("[]", "");
        int dot = base.indexOf('.');
        String head = dot < 0 ? base : base.substring(0, dot);
        if (!head.isEmpty() && Character.isLowerCase(head.charAt(0)) && dot > 0) {
            return base; // already a package-qualified name
        }
        String imp = imports.get(head);
        if (imp != null) {
            return dot < 0 ? imp : imp + base.substring(dot).replace('.', '$');
        }
        return base;
    }
}
