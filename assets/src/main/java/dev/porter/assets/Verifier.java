package dev.porter.assets;

import dev.porter.assets.lang.KeyTable;
import dev.porter.assets.lang.LangFiles;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import tools.jackson.databind.JsonNode;

/**
 * Lossless checks after a run: every source lang entry must be present in the output under its mapped key with the
 * same text (newlines as {@code \n} in {@code .lang}), and every sound event must keep its sound list (names in order).
 */
public final class Verifier {
    private Verifier() {
    }

    public record Result(boolean lossless, Map<String, Object> details) {
    }

    public static Result lang(AssetTree source, AssetTree out, Context ctx, AssetVersion to) {
        boolean ok = true;
        Map<String, Object> perFile = new LinkedHashMap<>();
        int total = 0;
        int matched = 0;
        for (String p : source.paths()) {
            String ns = AssetTree.namespace(p);
            String local = AssetTree.local(p);
            if (!local.startsWith("lang/") || local.indexOf('/', 5) >= 0) {
                continue;
            }
            Matcher m = LangFiles.JSON_LOCALE.matcher(local.substring(5));
            if (!m.matches()) {
                continue;
            }
            String locale = m.group(1);
            boolean json = to.ordinal() <= AssetVersion.V1_14_4.ordinal();
            String outPath = ns + "/lang/" + (json ? locale + ".json"
                    : to == AssetVersion.V1_12_2 ? locale + ".lang" : LangFiles.legacyLocale(locale) + ".lang");
            Map<String, String> src = LangFiles.parseJson(source.text(p)).entries();
            String text = out.text(outPath);
            Map<String, String> got = text == null ? Map.of()
                    : json ? LangFiles.parseJson(text).entries() : LangFiles.parseLang(text, false);
            int fileMatched = 0;
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> e : src.entrySet()) {
                KeyTable.Mapping mp = json ? null : ctx.langKeyMap.get(e.getKey());
                String key = mp == null ? e.getKey() : mp.target();
                String expected = json ? e.getValue() : LangFiles.escapeValue(e.getValue());
                if (expected.equals(got.get(key))) {
                    fileMatched++;
                } else if (missing.size() < 20) {
                    missing.add(e.getKey());
                }
            }
            boolean fileOk = fileMatched == src.size();
            ok &= fileOk;
            total += src.size();
            matched += fileMatched;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("output", outPath);
            d.put("sourceEntries", src.size());
            d.put("outputEntries", got.size());
            d.put("matched", fileMatched);
            d.put("lossless", fileOk);
            if (!missing.isEmpty()) {
                d.put("missingSample", missing);
            }
            perFile.put(p, d);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lossless", ok);
        details.put("sourceEntries", total);
        details.put("matched", matched);
        details.put("files", perFile);
        return new Result(ok, details);
    }

    public static Result sounds(AssetTree source, AssetTree out) {
        boolean ok = true;
        int events = 0;
        int sounds = 0;
        List<String> problems = new ArrayList<>();
        for (String ns : source.namespaces()) {
            String p = ns + "/sounds.json";
            if (!source.has(p)) {
                continue;
            }
            JsonNode a = Json.json().readTree(source.text(p));
            JsonNode b = out.has(p) ? Json.json().readTree(out.text(p)) : null;
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                events++;
                List<String> want = names(e.getValue().get("sounds"));
                sounds += want.size();
                List<String> have = b == null || !b.has(e.getKey()) ? null : names(b.get(e.getKey()).get("sounds"));
                if (!want.equals(have)) {
                    ok = false;
                    problems.add(ns + ":" + e.getKey());
                }
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lossless", ok);
        d.put("events", events);
        d.put("sounds", sounds);
        if (!problems.isEmpty()) {
            d.put("problems", problems);
        }
        return new Result(ok, d);
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (JsonNode s : list) {
                out.add(s.isString() ? s.asString() : s.path("name").asString(""));
            }
        }
        return out;
    }
}
