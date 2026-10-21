package dev.porter.rewrite.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@code replace} template: literal text with {@code $placeholder} holes. */
public record Template(List<Part> parts) {
    public record Part(String literal, String placeholder) {
    }

    private static final Pattern PH = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TARGET = Pattern.compile("\\$target\\(\\s*([A-Za-z_$][\\w$.]*)\\s*\\)");

    /**
     * Marks a target-version name ({@code $target(a.b.C)}, RFC 006) that {@code --relocate} must not rename: the name
     * is prefixed with this private-use character, which is stripped from the final output (after relocation).
     */
    public static final char TARGET_MARK = '';

    public static String markTargets(String text) {
        return text == null ? null : TARGET.matcher(text).replaceAll(m -> Matcher.quoteReplacement(TARGET_MARK + m.group(1)));
    }

    public static String stripMarks(String text) {
        return text.indexOf(TARGET_MARK) < 0 ? text : text.replace(String.valueOf(TARGET_MARK), "");
    }

    public static Template parse(String text) {
        List<Part> parts = new ArrayList<>();
        Matcher m = PH.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                parts.add(new Part(text.substring(last, m.start()), null));
            }
            parts.add(new Part(null, m.group()));
            last = m.end();
        }
        if (last < text.length()) {
            parts.add(new Part(text.substring(last), null));
        }
        return new Template(List.copyOf(parts));
    }

    public Set<String> placeholders() {
        Set<String> s = new LinkedHashSet<>();
        for (Part p : parts) {
            if (p.placeholder() != null) {
                s.add(p.placeholder());
            }
        }
        return s;
    }
}
