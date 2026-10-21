package dev.porter.port.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** javac's plain-text diagnostics ({@code File.java:12: error: msg} + detail lines), as printed by the CLI javac. */
public final class JavacLog {
    static final Pattern HDR = Pattern.compile("(.*?\\.java):(\\d+): error: (.*)");
    static final Pattern COUNT = Pattern.compile("\\d+ errors?");

    /** One error: file as printed, line, message, following detail lines (source line, caret, symbol, location). */
    public record Error(String file, int line, String message, List<String> extra) {
    }

    private JavacLog() {
    }

    public static List<String> lines(String log) {
        return List.of(log.split("\r\n|\n|\r", -1));
    }

    public static List<Error> parse(String log) {
        List<String> l = lines(log);
        List<Error> out = new ArrayList<>();
        int i = 0;
        while (i < l.size()) {
            Matcher m = HDR.matcher(l.get(i));
            if (!m.matches()) {
                i++;
                continue;
            }
            int j = i + 1;
            List<String> extra = new ArrayList<>();
            while (j < l.size() && !HDR.matcher(l.get(j)).matches() && !COUNT.matcher(l.get(j)).matches()) {
                extra.add(l.get(j));
                j++;
            }
            out.add(new Error(m.group(1), Integer.parseInt(m.group(2)), m.group(3), extra));
            i = j;
        }
        return out;
    }

    /** Number of {@code : error:} lines (what {@code grep -c ': error:'} counts). */
    public static int errorLines(String log) {
        int n = 0;
        for (String s : lines(log)) {
            if (s.contains(": error:")) {
                n++;
            }
        }
        return n;
    }
}
