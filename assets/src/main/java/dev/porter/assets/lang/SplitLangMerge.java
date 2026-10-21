package dev.porter.assets.lang;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Source normalisation for 1.13+ trees: merges non-standard split lang files {@code lang/<prefix>_<locale>.json}
 * (vanilla only loads {@code lang/<locale>.json}; some mods load the split files through a mixin) into the locale
 * file. Order: the locale file first, then split files sorted by name; a key defined twice with different text is a
 * loss (last wins, as in the source mod's loader).
 */
public final class SplitLangMerge {
    private SplitLangMerge() {
    }

    public static void apply(AssetTree tree, Context ctx) {
        int merged = 0;
        for (String ns : tree.namespaces()) {
            String dir = ns + "/lang/";
            Map<String, List<String>> splits = new TreeMap<>();
            for (String p : tree.paths(dir)) {
                String name = p.substring(dir.length());
                if (name.contains("/") || LangFiles.JSON_LOCALE.matcher(name).matches()) {
                    continue;
                }
                Matcher m = LangFiles.JSON_SPLIT.matcher(name);
                if (m.matches()) {
                    splits.computeIfAbsent(m.group(2), k -> new ArrayList<>()).add(p);
                }
            }
            for (Map.Entry<String, List<String>> e : splits.entrySet()) {
                String target = dir + e.getKey() + ".json";
                Map<String, String> all = new LinkedHashMap<>();
                List<String> parts = new ArrayList<>();
                if (tree.has(target)) {
                    parts.add(target);
                }
                parts.addAll(e.getValue());
                for (String part : parts) {
                    LangFiles.Parsed parsed = LangFiles.parseJson(tree.text(part));
                    parsed.duplicates().forEach(k -> ctx.warn("lang-duplicate-key", part + "#" + k,
                            "key defined twice in the same file; last value kept (as vanilla does)"));
                    for (Map.Entry<String, String> x : parsed.entries().entrySet()) {
                        String old = all.put(x.getKey(), x.getValue());
                        if (old != null && !old.equals(x.getValue())) {
                            ctx.loss("lang-split-conflict", part + "#" + x.getKey(),
                                    "key also defined with different text in another split file; last kept");
                        }
                    }
                    if (!part.equals(target)) {
                        tree.remove(part);
                        merged++;
                    }
                }
                tree.putText(target, LangFiles.writeJson(all));
            }
        }
        ctx.metric("lang.splitFilesMerged", merged);
    }
}
