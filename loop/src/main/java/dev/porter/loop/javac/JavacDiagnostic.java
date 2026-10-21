package dev.porter.loop.javac;

import java.util.List;

/**
 * One javac diagnostic as printed by the compiler (plain text format or {@code -XDrawDiagnostics}).
 *
 * @param file       path as printed by javac (absolute in Gradle builds)
 * @param line       1-based line
 * @param column     1-based column (raw diagnostics, or computed from the caret line), or null
 * @param severity   error | warning
 * @param message    first message line ("cannot find symbol")
 * @param code       javac diagnostic key when printed raw ({@code compiler.err.cant.resolve.location}), else null
 * @param sourceLine the source line javac echoed, or null
 * @param details    further message lines, trimmed ("symbol:   class Foo", "location: class Bar", "required: int")
 */
public record JavacDiagnostic(String file, int line, Integer column, String severity, String message, String code,
        String sourceLine, List<String> details) {

    public JavacDiagnostic {
        details = List.copyOf(details);
    }

    public boolean isError() {
        return "error".equals(severity);
    }

    /** First detail line starting with {@code key + ":"} (e.g. "symbol"), value trimmed, or null. */
    public String detail(String key) {
        for (String d : details) {
            if (d.startsWith(key + ":")) {
                return d.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    /** The full message text: first line plus details, joined by newlines. */
    public String fullMessage() {
        if (details.isEmpty()) {
            return message;
        }
        return message + "\n" + String.join("\n", details);
    }
}
