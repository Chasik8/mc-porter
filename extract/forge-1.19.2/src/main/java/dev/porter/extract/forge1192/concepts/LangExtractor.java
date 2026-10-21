package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ArrayNode;

/**
 * {@code lang}: {@code assets/<ns>/lang/<locale>.json}, one node per namespace and locale. Mods that load extra
 * files ({@code <prefix>_<locale>.json}, declared by a pattern pack) get them merged into the same node; the source
 * files are listed in {@code extensions["porter-extract:files"]}. Any other file in a lang directory is unknown.
 */
public final class LangExtractor {
    public static final String ID = "lang/json";
    private static final Pattern FILE = Pattern.compile("^assets/([a-z0-9_.-]+)/lang/(.+)\\.json$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}_[a-z]{2,3}$");

    private LangExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        Map<String, Map<String, String>> entries = new TreeMap<>();
        Map<String, List<String>> files = new TreeMap<>();
        Map<String, Path> firstFile = new TreeMap<>();
        for (Path f : ctx.layout.resourceFiles(p -> FILE.matcher(p).matches())) {
            String rel = ctx.layout.resourcePath(f);
            Matcher m = FILE.matcher(rel);
            if (!m.matches()) {
                continue;
            }
            String ns = m.group(1);
            String base = m.group(2);
            String locale = null;
            if (LOCALE.matcher(base).matches()) {
                locale = base;
            } else {
                int us = base.indexOf('_');
                if (us > 0 && ctx.langPrefixes.contains(ns + ":" + base.substring(0, us))
                        && LOCALE.matcher(base.substring(us + 1)).matches()) {
                    locale = base.substring(us + 1);
                }
            }
            if (locale == null) {
                ctx.sink.unknown("lang", null, "lang file name is neither <locale>.json nor a declared prefix file",
                        rel, ctx.provResource(f, ID));
                continue;
            }
            JsonNode root;
            try {
                root = Json.json().readTree(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                ctx.sink.unknown("lang", null, "unreadable lang json: " + e.getMessage(), rel, ctx.provResource(f, ID));
                continue;
            }
            if (!root.isObject()) {
                ctx.sink.unknown("lang", null, "lang json is not an object", rel, ctx.provResource(f, ID));
                continue;
            }
            String key = ns + ":" + locale;
            Map<String, String> map = entries.computeIfAbsent(key, k -> new LinkedHashMap<>());
            files.computeIfAbsent(key, k -> new ArrayList<>()).add(ctx.layout.relative(f));
            if (LOCALE.matcher(base).matches()) {
                firstFile.put(key, f);
            } else {
                firstFile.putIfAbsent(key, f);
            }
            int conflicts = 0;
            for (var e : root.properties()) {
                if (!e.getValue().isString()) {
                    continue;
                }
                String prev = map.putIfAbsent(e.getKey(), e.getValue().asString());
                if (prev != null && !prev.equals(e.getValue().asString())) {
                    conflicts++;
                }
            }
            if (conflicts > 0) {
                ctx.note(ID, rel + ": " + conflicts + " keys already defined by an earlier file (first kept)");
            }
        }
        for (var e : entries.entrySet()) {
            String key = e.getKey();
            String locale = key.substring(key.indexOf(':') + 1);
            Map<String, JsonNode> ext = null;
            if (files.get(key).size() > 1) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                files.get(key).forEach(arr::add);
                ext = Map.of("porter-extract:files", arr);
            }
            ctx.sink.add(new IrNode.Lang(key, locale, new TreeMap<>(e.getValue()), ctx.provResource(firstFile.get(key), ID),
                    null, ext));
        }
    }
}
