package dev.porter.testkit.runner.gui;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.databind.JsonNode;

/**
 * English translations of the mods of one side, read from their jars: {@code assets/<ns>/lang/en_us.json} (1.13+)
 * and {@code assets/<ns>/lang/en_US.lang} (1.7.10 key=value). Used to map drawn texts back to lang keys.
 */
public final class Lang {
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXegc]|\\{\\d+}");
    private static final Pattern CODES = Pattern.compile("§.");

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, Pattern> patterns = new LinkedHashMap<>();

    public static Lang of(List<Path> jars) {
        Lang l = new Lang();
        for (Path j : jars) {
            l.read(j);
        }
        return l;
    }

    private void read(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("assets/") || !n.contains("/lang/")) {
                    continue;
                }
                String f = n.substring(n.lastIndexOf('/') + 1);
                if (f.equals("en_us.json")) {
                    try (InputStream in = z.getInputStream(e)) {
                        JsonNode o = Json.json().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        for (Map.Entry<String, JsonNode> p : o.properties()) {
                            if (p.getValue().isString()) {
                                values.putIfAbsent(p.getKey(), p.getValue().asString());
                            }
                        }
                    }
                } else if (f.equalsIgnoreCase("en_US.lang")) {
                    try (InputStream in = z.getInputStream(e)) {
                        for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                            int eq = line.indexOf('=');
                            if (eq > 0 && !line.startsWith("#")) {
                                values.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // a jar without readable lang files contributes nothing
        }
    }

    public int size() {
        return values.size();
    }

    public String get(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Pattern matching a drawn text produced from this key's translation (format arguments match anything). */
    public Pattern pattern(String key) {
        String v = values.get(key);
        if (v == null) {
            return null;
        }
        return patterns.computeIfAbsent(key, k -> toPattern(v));
    }

    public static Pattern toPattern(String value) {
        String v = strip(value).replace("%%", "\u0000");
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = FORMAT.matcher(v);
        int last = 0;
        while (m.find()) {
            sb.append(Pattern.quote(v.substring(last, m.start()).replace("\u0000", "%")));
            sb.append(".*?");
            last = m.end();
        }
        sb.append(Pattern.quote(v.substring(last).replace("\u0000", "%")));
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    /** Keys whose translation produces the text. */
    public List<String> keysFor(String text) {
        String t = strip(text);
        List<String> out = new ArrayList<>();
        for (String k : values.keySet()) {
            String v = strip(values.get(k));
            if (v.isEmpty()) {
                continue;
            }
            if (v.equals(t) || (v.contains("%") || v.contains("{")) && pattern(k).matcher(t).matches()) {
                out.add(k);
                if (out.size() > 8) {
                    break;
                }
            }
        }
        return out;
    }

    /** Removes formatting codes and surrounding/duplicate whitespace. */
    public static String strip(String s) {
        return CODES.matcher(s).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /** Looks like a raw (untranslated) lang key: dotted lower-case identifiers, no spaces. */
    public static boolean looksLikeKey(String s) {
        String t = strip(s);
        return t.matches("[a-z0-9_]+(\\.[A-Za-z0-9_]+){2,}");
    }
}
