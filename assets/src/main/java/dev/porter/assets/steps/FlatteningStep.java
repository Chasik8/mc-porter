package dev.porter.assets.steps;

import dev.porter.assets.AssetTree;
import dev.porter.assets.AssetVersion;
import dev.porter.assets.Context;
import dev.porter.assets.Step;
import dev.porter.assets.lang.KeyTable;
import dev.porter.assets.lang.LangFiles;
import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 1.14.4 (pack_format 4, post-flattening) → 1.12.2 (pack_format 3).
 *
 * <ul>
 *   <li>{@code lang/<xx_yy>.json} → {@code lang/<xx_yy>.lang}; keys renamed through {@link KeyTable}.</li>
 *   <li>{@code textures/block/} → {@code textures/blocks/}, {@code textures/item/} → {@code textures/items/} (with
 *       their {@code .mcmeta}); texture references {@code ns:block/...}/{@code ns:item/...} inside model JSON and
 *       texture-package JSON follow the move.</li>
 * </ul>
 */
public final class FlatteningStep implements Step {

    @Override
    public AssetVersion from() {
        return AssetVersion.V1_14_4;
    }

    @Override
    public AssetVersion to() {
        return AssetVersion.V1_12_2;
    }

    @Override
    public String description() {
        return "flattening: JSON lang -> .lang with legacy keys; textures block/item -> blocks/items";
    }

    @Override
    public void apply(AssetTree tree, Context ctx) {
        collectKnownBlocks(tree, ctx);
        convertLang(tree, ctx);
        moveTextures(tree, ctx);
    }

    static void collectKnownBlocks(AssetTree tree, Context ctx) {
        for (IrNode.Block b : ctx.irNodes(IrNode.Block.class)) {
            ctx.knownBlocks.put(b.id(), "ir");
        }
        Pattern blockKey = Pattern.compile("^block\\.([a-z0-9_-]+)\\.([a-z0-9_/-]+)$");
        for (String p : tree.paths()) {
            if (!LangFiles.JSON_LOCALE.matcher(p.substring(p.lastIndexOf('/') + 1)).matches() || !p.contains("/lang/")) {
                continue;
            }
            for (String k : LangFiles.parseJson(tree.text(p)).entries().keySet()) {
                Matcher m = blockKey.matcher(k);
                if (m.matches()) {
                    ctx.knownBlocks.putIfAbsent(m.group(1) + ":" + m.group(2), "lang");
                }
            }
        }
    }

    private void convertLang(AssetTree tree, Context ctx) {
        KeyTable table = new KeyTable(ctx);
        int files = 0;
        int entries = 0;
        int renamed = 0;
        int multiline = 0;
        for (String p : tree.paths()) {
            String ns = AssetTree.namespace(p);
            String local = AssetTree.local(p);
            if (!local.startsWith("lang/") || local.indexOf('/', 5) >= 0) {
                continue;
            }
            Matcher m = LangFiles.JSON_LOCALE.matcher(local.substring(5));
            if (!m.matches()) {
                if (local.endsWith(".json")) {
                    ctx.warn("lang-nonstandard-file", p, "not a <locale>.json lang file; copied unchanged (not loaded by the target)");
                }
                continue;
            }
            LangFiles.Parsed parsed = LangFiles.parseJson(tree.text(p));
            parsed.duplicates().forEach(k -> ctx.warn("lang-duplicate-key", p + "#" + k, "duplicate key in source; last value kept"));
            parsed.nonString().forEach(k -> ctx.warn("lang-non-string", p + "#" + k, "non-string value converted to text"));
            Map<String, String> out = new LinkedHashMap<>();
            Map<String, String> origin = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parsed.entries().entrySet()) {
                String src = e.getKey();
                KeyTable.Mapping mp = table.map(src);
                if (mp.origin() != KeyTable.Origin.KEPT) {
                    ctx.langKeyMap.put(src, mp);
                }
                if (mp.renamed(src)) {
                    renamed++;
                }
                put(out, origin, mp.target(), e.getValue(), src, p, ctx);
                if (ctx.keepSourceKeys && mp.renamed(src)) {
                    put(out, origin, src, e.getValue(), src, p, ctx);
                }
                if (LangFiles.multiline(e.getValue())) {
                    multiline++;
                    ctx.warn("lang-multiline", ns + ":" + src,
                            "value contains a line break; written as \\n, mod code must expand it (1.7.10 .lang is line based)");
                }
                if (e.getValue().contains("\\n")) {
                    ctx.warn("lang-literal-backslash-n", ns + ":" + src, "value already contains a literal \\n sequence");
                }
                entries++;
            }
            String lang = LangFiles.writeLang(out, k -> ctx.loss("lang-unrepresentable-key", p + "#" + k,
                    "key cannot be written to a .lang file (contains '=' or a line break, or starts with '#')"));
            tree.remove(p);
            tree.putText(ns + "/lang/" + m.group(1) + ".lang", lang);
            files++;
        }
        ctx.metric("lang.files", files);
        ctx.metric("lang.entries", entries);
        ctx.metric("lang.renamedEntries", renamed);
        ctx.metric("lang.multilineEntries", multiline);
        long guessed = ctx.langKeyMap.values().stream().filter(KeyTable.Mapping::guessed).count();
        ctx.metric("lang.guessedKeys", guessed);
        ctx.langKeyMap.forEach((k, v) -> {
            if (v.guessed()) {
                ctx.warn("lang-guessed-key", k, "renamed to " + v.target() + " by " + v.reason() + " (no IR entry)");
            }
        });
    }

    private static void put(Map<String, String> out, Map<String, String> origin, String key, String value, String src,
            String file, Context ctx) {
        String prev = out.get(key);
        if (prev == null) {
            out.put(key, value);
            origin.put(key, src);
        } else if (prev.equals(value)) {
            ctx.warn("lang-key-collision-same", file + "#" + key, "keys " + origin.get(key) + " and " + src + " both map here; same text");
        } else {
            ctx.loss("lang-key-collision", file + "#" + key,
                    "keys " + origin.get(key) + " and " + src + " map to the same target with different text; first kept");
        }
    }

    /** {@code ns:block/x} → {@code ns:blocks/x}, {@code item/x} → {@code items/x}; other references unchanged. */
    static String movedRef(String ref) {
        int c = ref.indexOf(':');
        String ns = c < 0 ? "" : ref.substring(0, c + 1);
        String path = ref.substring(c + 1);
        if (path.startsWith("block/")) {
            return ns + "blocks/" + path.substring(6);
        }
        if (path.startsWith("item/")) {
            return ns + "items/" + path.substring(5);
        }
        return ref;
    }

    private void moveTextures(AssetTree tree, Context ctx) {
        int moved = 0;
        int refs = 0;
        for (String p : tree.paths()) {
            String ns = AssetTree.namespace(p);
            String local = AssetTree.local(p);
            if (local.startsWith("textures/block/")) {
                tree.move(p, ns + "/textures/blocks/" + local.substring("textures/block/".length()));
                moved++;
            } else if (local.startsWith("textures/item/")) {
                tree.move(p, ns + "/textures/items/" + local.substring("textures/item/".length()));
                moved++;
            }
        }
        for (String p : tree.paths()) {
            String local = AssetTree.local(p);
            boolean model = local.startsWith("models/") && local.endsWith(".json");
            boolean pkg = local.startsWith("textures/") && local.endsWith("texture_package.json");
            if (!model && !pkg) {
                continue;
            }
            JsonNode root = Json.json().readTree(tree.text(p));
            JsonNode target = model ? root.get("textures") : root;
            if (!(target instanceof ObjectNode obj)) {
                continue;
            }
            int n = 0;
            for (Map.Entry<String, JsonNode> e : new ArrayList<>(obj.properties())) {
                if (!e.getValue().isString()) {
                    continue;
                }
                String v = e.getValue().asString();
                String nv = movedRef(v);
                if (!nv.equals(v)) {
                    obj.put(e.getKey(), nv);
                    n++;
                }
            }
            if (n > 0) {
                tree.putText(p, Json.json().writeValueAsString(root) + "\n");
                refs += n;
            }
        }
        ctx.metric("textures.moved", moved);
        ctx.metric("textures.referencesRewritten", refs);
    }
}
