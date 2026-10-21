package dev.porter.port;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text helpers shared by the source passes. Line handling follows one convention everywhere: files are read with
 * universal newlines ({@code \r\n} and {@code \r} become {@code \n}) and regexes treat only {@code \n} as a line
 * terminator ({@link Pattern#UNIX_LINES}), so a pass gives the same result on any OS.
 */
public final class Text {
    /** Default regex flags of the passes. */
    public static final int FLAGS = Pattern.UNIX_LINES;

    private Text() {
    }

    public static Pattern re(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    public static Pattern re(String regex, int flags) {
        return Pattern.compile(regex, FLAGS | flags);
    }

    /** Reads UTF-8 text with universal newlines; malformed bytes are replaced. */
    public static String read(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(b)).toString();
            return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes UTF-8 text as is (no newline translation), creating parent directories. */
    public static void write(Path p, String s) {
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Non-blank, trimmed lines of a list file (one entry per line or whitespace-separated). */
    public static List<String> words(Path p) {
        List<String> out = new ArrayList<>();
        for (String w : read(p).split("\\s+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Writes entries one per line with a trailing newline. */
    public static void writeLines(Path p, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        lines.forEach(l -> sb.append(l).append('\n'));
        write(p, sb.toString());
    }

    /** All {@code .java} files under a root, sorted by path. */
    public static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces matches using a function (like Python's {@code re.sub(pattern, fn, s)}), all occurrences. */
    public static String sub(Pattern p, Function<Matcher, String> fn, String s) {
        return sub(p, fn, s, 0);
    }

    /** As {@link #sub(Pattern, Function, String)}, at most {@code count} replacements when count > 0. */
    public static String sub(Pattern p, Function<Matcher, String> fn, String s, int count) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while ((count <= 0 || n < count) && m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
            n++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Result of {@link #subn}: the new text and the number of replacements. */
    public record SubResult(String text, int count) {
    }

    /**
     * Replacement with a Python {@code re} template: {@code \1}..{@code \99}, {@code \g<n>}, {@code \g<name>} and the
     * escapes {@code \n \t \r \\ \f \v \a \b} — the format of the edit lists, which are data written for
     * {@code re.sub}.
     */
    public static SubResult subn(Pattern p, String template, String s, int count) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while ((count <= 0 || n < count) && m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(expand(template, m)));
            n++;
        }
        m.appendTail(sb);
        return new SubResult(sb.toString(), n);
    }

    static String expand(String t, Matcher m) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '\\' || i + 1 >= t.length()) {
                out.append(c);
                continue;
            }
            char d = t.charAt(++i);
            if (Character.isDigit(d)) {
                int j = i + 1;
                if (j < t.length() && Character.isDigit(t.charAt(j)) && Integer.parseInt(t.substring(i, j + 1)) <= m.groupCount()) {
                    j++;
                }
                String g = m.group(Integer.parseInt(t.substring(i, j)));
                out.append(g == null ? "" : g);
                i = j - 1;
            } else if (d == 'g' && i + 1 < t.length() && t.charAt(i + 1) == '<') {
                int close = t.indexOf('>', i);
                String name = t.substring(i + 2, close);
                String g = name.chars().allMatch(Character::isDigit) ? m.group(Integer.parseInt(name)) : m.group(name);
                out.append(g == null ? "" : g);
                i = close;
            } else {
                switch (d) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'f' -> out.append('\f');
                    case 'v' -> out.append('\u000b');
                    case 'a' -> out.append('\u0007');
                    case 'b' -> out.append('\b');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(d); // Python keeps unknown ASCII-letter escapes as an error; others as is
                }
            }
        }
        return out.toString();
    }

    /** Python {@code str.strip()} for ASCII whitespace. */
    public static String strip(String s) {
        return s.strip();
    }
}
