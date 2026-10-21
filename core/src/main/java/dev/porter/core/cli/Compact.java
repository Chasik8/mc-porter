package dev.porter.core.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compact console output (keeps console output short): the full output of a command goes to a log file,
 * the console gets a short summary of at most {@link #MAX_LINES} lines: success, or a failure grouped by cause, plus
 * the path of the full log. {@code --verbose} (or {@code PORTER_VERBOSE=1}) prints everything.
 */
public final class Compact {
    /** Longest console summary (lines), log hint included. */
    public static final int MAX_LINES = 20;

    private static final Pattern ERROR_LINE = Pattern.compile(
            "(?i)(\\berror\\b|\\bexception\\b|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|\\bcannot\\b|\\bnot found\\b)");
    private static final Pattern PATH = Pattern.compile("([A-Za-z]:)?[\\\\/][^\\s:\"')\\]]+");
    private static final Pattern LOCATION = Pattern.compile("\\S+\\.java:\\d+:\\s*");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private Compact() {
    }

    /** True when {@code --verbose} was given or {@code PORTER_VERBOSE} is set (the CLI sets {@code porter.verbose}). */
    public static boolean verbose() {
        if (Boolean.getBoolean("porter.verbose")) {
            return true;
        }
        String e = System.getenv("PORTER_VERBOSE");
        return e != null && !e.isBlank() && !e.equals("0") && !e.equalsIgnoreCase("false");
    }

    /** The console lines for a finished command; {@code lines} unchanged when they are already short. */
    public static List<String> summarize(List<String> lines, int exitCode, String logPath) {
        if (lines.size() <= MAX_LINES) {
            return lines;
        }
        int budget = MAX_LINES - 1; // one line for the log hint
        List<String> out = new ArrayList<>();
        if (exitCode == 0) {
            int head = 2;
            int tail = budget - head - 1;
            out.addAll(lines.subList(0, head));
            out.add("... " + (lines.size() - head - tail) + " lines omitted ...");
            out.addAll(lines.subList(lines.size() - tail, lines.size()));
        } else {
            Map<String, int[]> groups = new LinkedHashMap<>();
            Map<String, String> sample = new LinkedHashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!ERROR_LINE.matcher(l).find() || l.trim().startsWith("at ")) {
                    continue;
                }
                String text = l.trim();
                // javac: the symbol / location lines after "error:" belong to the cause
                if (i + 1 < lines.size() && lines.get(i + 1).trim().startsWith("symbol:")) {
                    text += " " + lines.get(i + 1).trim();
                }
                String key = cause(text);
                groups.computeIfAbsent(key, k -> new int[1])[0]++;
                sample.putIfAbsent(key, text);
            }
            int tail = 4;
            int room = budget - tail - 4;
            if (groups.isEmpty()) {
                out.addAll(lines.subList(lines.size() - Math.min(lines.size(), budget), lines.size()));
            } else {
                out.add("FAILED (exit " + exitCode + "), " + groups.size() + " distinct cause(s) in " + lines.size() + " lines:");
                List<Map.Entry<String, int[]>> sorted = new ArrayList<>(groups.entrySet());
                sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
                for (int i = 0; i < Math.min(room, sorted.size()); i++) {
                    out.add(String.format(Locale.ROOT, "%5dx %s", sorted.get(i).getValue()[0], clip(sample.get(sorted.get(i).getKey()), 150)));
                }
                if (sorted.size() > room) {
                    out.add("       ... " + (sorted.size() - room) + " more cause(s)");
                }
                out.add("last lines:");
                for (String l : lines.subList(lines.size() - tail, lines.size())) {
                    out.add("  " + clip(l, 150));
                }
            }
        }
        out.add((exitCode == 0 ? "ok; " : "") + "full output: " + logPath + " (--verbose prints everything)");
        return out;
    }

    /** Normalised message of an error line: paths, source positions and numbers removed. */
    static String cause(String line) {
        String s = LOCATION.matcher(line).replaceAll("");
        s = PATH.matcher(s).replaceAll("<path>");
        s = NUMBER.matcher(s).replaceAll("#");
        return s.trim();
    }

    public static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
