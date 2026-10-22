package dev.porter.port.passes;

import dev.porter.core.json.Json;
import dev.porter.port.Text;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Lang entries that ported code looks up by literal key: every string literal of the scanned sources that matches
 * the key pattern and that a target {@code .lang} file lacks is copied from the source mod's JSON lang files
 * ({@code *<locale>.json}, merged in name order; missing translations fall back to the fallback locale) into a
 * marked section of the target file. The section is replaced on every run, so the pass is idempotent; entries the
 * file already has outside the section are never duplicated.
 */
public final class LangKeys {
    private LangKeys() {
    }

    /**
     * @param scan       sources to scan for literals
     * @param keyPattern regex whose group 1 is the key (applied to the source text)
     * @param source     directory of the source mod's JSON lang files
     * @param target     directory of the target {@code <locale>.lang} files (only existing files are extended)
     * @param locales    source locale (file suffix) -> target lang file name without {@code .lang}
     * @param fallback   source locale used for keys a translation lacks (and that decides which keys exist)
     * @param marker     section name ({@code # --- <marker> ---} ... {@code # --- end <marker> ---})
     * @return entries written per target locale
     */
    public static Map<String, Integer> merge(Path scan, Pattern keyPattern, Path source, Path target, Map<String, String> locales,
            String fallback, String marker) {
        Set<String> lits = new TreeSet<>();
        for (Path p : Text.javaFiles(scan)) {
            Matcher m = keyPattern.matcher(Text.read(p));
            while (m.find()) {
                lits.add(m.group(1));
            }
        }
        String mark = "# --- " + marker + " ---";
        String end = "# --- end " + marker + " ---";
        Pattern section = Pattern.compile(Pattern.quote(mark) + ".*?" + Pattern.quote(end) + "\n?", Pattern.DOTALL);
        Map<String, String> en = load(source, fallback);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> loc : locales.entrySet()) {
            Path f = target.resolve(loc.getValue() + ".lang");
            if (!Files.exists(f)) {
                continue;
            }
            String text = section.matcher(read(f)).replaceAll("");
            Set<String> have = new HashSet<>();
            for (String l : text.split("\n", -1)) {
                if (l.contains("=") && !l.startsWith("#")) {
                    have.add(l.substring(0, l.indexOf('=')));
                }
            }
            Map<String, String> tr = load(source, loc.getKey());
            List<String> lines = new ArrayList<>();
            for (String k : lits) {
                if (have.contains(k) || !en.containsKey(k)) {
                    continue;
                }
                lines.add(k + "=" + tr.getOrDefault(k, en.get(k)).replace("\n", "\\n"));
            }
            if (!text.endsWith("\n")) {
                text += "\n";
            }
            write(f, text + mark + "\n" + String.join("\n", lines) + "\n" + end + "\n");
            out.put(loc.getValue(), lines.size());
        }
        return out;
    }

    private static Map<String, String> load(Path dir, String locale) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(locale + ".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path f : files) {
            JsonNode n = Json.json().readTree(read(f));
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                out.put(e.getKey(), e.getValue().asString());
            }
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String s) {
        try {
            Files.write(p, s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
