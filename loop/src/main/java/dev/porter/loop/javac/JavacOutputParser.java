package dev.porter.loop.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses javac output as printed by {@code javac} or Gradle's {@code compileJava} (plain console). Two formats:
 * <ul>
 *   <li>plain: {@code <file>.java:<line>: error: <message>}, then the echoed source line, a caret line and indented
 *       detail lines ({@code symbol:}, {@code location:}, {@code required:} ...);</li>
 *   <li>raw ({@code -XDrawDiagnostics}): {@code <file>.java:<line>:<col>: compiler.err.<key>: <args>}.</li>
 * </ul>
 * Everything that is not part of a diagnostic (Gradle task lines, "N errors", notes) is ignored.
 */
public final class JavacOutputParser {
    private static final Pattern PLAIN = Pattern.compile("^(.*?\\.java):(\\d+): (error|warning): (.*)$");
    private static final Pattern RAW = Pattern.compile("^(.*?\\.java):(\\d+):(\\d+): (compiler\\.(err|warn)\\.[\\w.-]+)(?:: ?(.*))?$");
    private static final Pattern CARET = Pattern.compile("^\\s*\\^\\s*$");
    private static final Pattern END = Pattern.compile(
            "^(\\d+ (errors?|warnings?)|> Task .*|FAILURE:.*|BUILD (FAILED|SUCCESSFUL).*|\\* What went wrong:.*|Note: .*"
                    + "|\\[queue].*|Execution failed for task.*|warning: \\[.*|error: .*|> Compilation failed.*)$");

    private JavacOutputParser() {
    }

    public static List<JavacDiagnostic> parse(String output) {
        String[] lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<JavacDiagnostic> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String l = lines[i];
            Matcher raw = RAW.matcher(l);
            Matcher plain = PLAIN.matcher(l);
            if (raw.matches()) {
                List<String> details = new ArrayList<>();
                int j = i + 1;
                while (j < lines.length && isContinuation(lines[j])) {
                    if (!lines[j].isBlank() && !CARET.matcher(lines[j]).matches()) {
                        details.add(lines[j].trim());
                    }
                    j++;
                }
                String sev = "err".equals(raw.group(5)) ? "error" : "warning";
                out.add(new JavacDiagnostic(raw.group(1).trim(), Integer.parseInt(raw.group(2)), Integer.parseInt(raw.group(3)),
                        sev, raw.group(6) == null ? "" : raw.group(6).trim(), raw.group(4), null, details));
                i = j;
            } else if (plain.matches()) {
                int j = i + 1;
                String source = null;
                Integer column = null;
                List<String> details = new ArrayList<>();
                // Source echo is followed by a caret line.
                if (j + 1 < lines.length && CARET.matcher(lines[j + 1]).matches() && !isHeader(lines[j])) {
                    source = lines[j];
                    column = lines[j + 1].indexOf('^') + 1;
                    j += 2;
                }
                while (j < lines.length && isContinuation(lines[j])) {
                    String d = lines[j];
                    if (!d.isBlank() && !CARET.matcher(d).matches()) {
                        details.add(d.trim());
                    }
                    j++;
                }
                // Multi-line messages where the source echo comes after continuation lines (rare): pick it up.
                out.add(new JavacDiagnostic(plain.group(1).trim(), Integer.parseInt(plain.group(2)), column, plain.group(3),
                        plain.group(4).trim(), null, source, details));
                i = j;
            } else {
                i++;
            }
        }
        // Gradle 9 repeats every compiler error (indented) in its failure summary: keep the first of identical ones.
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<JavacDiagnostic> unique = new ArrayList<>();
        for (JavacDiagnostic d : out) {
            if (seen.add(d.file() + "|" + d.line() + "|" + d.severity() + "|" + d.message() + "|" + d.details())) {
                unique.add(d);
            }
        }
        return unique;
    }

    private static boolean isHeader(String l) {
        return PLAIN.matcher(l).matches() || RAW.matcher(l).matches();
    }

    /** Detail lines are indented (javac indents them by at least 2 spaces) and are not a new diagnostic. */
    private static boolean isContinuation(String l) {
        if (isHeader(l) || END.matcher(l.strip()).matches()) {
            return false;
        }
        return l.startsWith(" ") || l.startsWith("\t");
    }
}
