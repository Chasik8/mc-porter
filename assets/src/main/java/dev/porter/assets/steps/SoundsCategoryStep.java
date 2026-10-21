package dev.porter.assets.steps;

import dev.porter.assets.AssetTree;
import dev.porter.assets.AssetVersion;
import dev.porter.assets.Context;
import dev.porter.assets.Step;
import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 1.10.2 (pack_format 2) → 1.8.9 (pack_format 1): in {@code sounds.json} 1.9 moved the category into code and added
 * subtitles. Going back, {@code subtitle} is dropped (no equivalent, reported) and {@code category} is set from the
 * IR {@code sound} node; without IR it is left out, which 1.7.10 reads as {@code master} (reported).
 */
public final class SoundsCategoryStep implements Step {
    /** Categories accepted by 1.7.10 {@code SoundCategory} (verified against the 1.7.10 sources). */
    public static final Set<String> CATEGORIES_1_7_10 =
            Set.of("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient");

    @Override
    public AssetVersion from() {
        return AssetVersion.V1_10_2;
    }

    @Override
    public AssetVersion to() {
        return AssetVersion.V1_8_9;
    }

    @Override
    public String description() {
        return "sounds.json: drop subtitle, add category from IR";
    }

    @Override
    public void apply(AssetTree tree, Context ctx) {
        Map<String, String> category = new HashMap<>();
        for (IrNode.Sound s : ctx.irNodes(IrNode.Sound.class)) {
            if (s.category() != null) {
                category.put(s.id(), s.category());
            }
        }
        int fromIr = 0;
        int defaulted = 0;
        int subtitles = 0;
        for (String ns : tree.namespaces()) {
            String p = ns + "/sounds.json";
            if (!tree.has(p)) {
                continue;
            }
            JsonNode root = Json.json().readTree(tree.text(p));
            if (!(root instanceof ObjectNode obj)) {
                ctx.loss("sounds-invalid", p, "sounds.json is not a JSON object");
                continue;
            }
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if (!(e.getValue() instanceof ObjectNode ev)) {
                    ctx.loss("sounds-invalid", p + "#" + e.getKey(), "event is not an object");
                    continue;
                }
                String id = ns + ":" + e.getKey();
                if (ev.has("subtitle")) {
                    ctx.warn("dropped-field", id + "#subtitle", "subtitles do not exist before 1.9: " + ev.get("subtitle").asString());
                    ev.remove("subtitle");
                    subtitles++;
                }
                if (!ev.has("category")) {
                    String c = category.get(id);
                    if (c == null) {
                        ctx.warn("sound-category-default", id, "no IR sound category; 1.7.10 defaults to master");
                        defaulted++;
                    } else if (!CATEGORIES_1_7_10.contains(c)) {
                        ctx.warn("sound-category-unsupported", id, "category " + c + " does not exist in 1.7.10; using master");
                        defaulted++;
                    } else {
                        ev.put("category", c);
                        fromIr++;
                    }
                }
            }
            tree.putText(p, Json.json().writeValueAsString(root) + "\n");
        }
        ctx.metric("sounds.categoryFromIr", fromIr);
        ctx.metric("sounds.categoryDefaulted", defaulted);
        ctx.metric("sounds.subtitlesDropped", subtitles);
    }
}
