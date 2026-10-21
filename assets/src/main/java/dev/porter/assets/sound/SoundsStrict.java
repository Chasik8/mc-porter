package dev.porter.assets.sound;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import dev.porter.assets.steps.SoundsCategoryStep;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes {@code sounds.json} loadable by 1.7.10 ({@code SoundListSerializer}, verified against the 1.7.10 sources):
 * event fields {@code replace, category, sounds}; sound fields {@code name, type (file|event), volume (> 0),
 * pitch (> 0), weight (> 0), stream}. Anything else is dropped and reported. 1.7.10 reads the file with the platform
 * charset, so the output is ASCII with {@code \\uXXXX} escapes.
 */
public final class SoundsStrict {
    private static final Set<String> EVENT_FIELDS = Set.of("replace", "category", "sounds");
    private static final Set<String> SOUND_FIELDS = Set.of("name", "type", "volume", "pitch", "weight", "stream");
    private static final JsonMapper ASCII = Json.json().rebuild().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private SoundsStrict() {
    }

    public static void apply(AssetTree tree, Context ctx) {
        int events = 0;
        int sounds = 0;
        int oggFiles = 0;
        for (String ns : tree.namespaces()) {
            oggFiles += (int) tree.paths(ns + "/sounds/").stream().filter(x -> x.endsWith(".ogg")).count();
            String p = ns + "/sounds.json";
            if (!tree.has(p)) {
                continue;
            }
            JsonNode root = Json.json().readTree(tree.text(p));
            if (!(root instanceof ObjectNode obj)) {
                continue;
            }
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if (!(e.getValue() instanceof ObjectNode ev)) {
                    continue;
                }
                String id = ns + ":" + e.getKey();
                events++;
                for (String f : new ArrayList<>(ev.propertyNames())) {
                    if (!EVENT_FIELDS.contains(f)) {
                        ctx.warn("dropped-field", id + "#" + f, "event field not supported by 1.7.10");
                        ev.remove(f);
                    }
                }
                if (ev.has("category") && !SoundsCategoryStep.CATEGORIES_1_7_10.contains(ev.get("category").asString())) {
                    ctx.loss("sound-category-invalid", id, "category " + ev.get("category").asString() + " is invalid in 1.7.10");
                }
                JsonNode list = ev.get("sounds");
                if (!(list instanceof ArrayNode arr)) {
                    continue;
                }
                for (int i = 0; i < arr.size(); i++) {
                    sounds++;
                    if (!(arr.get(i) instanceof ObjectNode s)) {
                        continue;
                    }
                    String sid = id + "#sounds[" + i + "]";
                    for (String f : new ArrayList<>(s.propertyNames())) {
                        if (!SOUND_FIELDS.contains(f)) {
                            ctx.warn("dropped-field", sid + "." + f, "sound field not supported by 1.7.10");
                            s.remove(f);
                        }
                    }
                    for (String f : new String[] {"volume", "pitch", "weight"}) {
                        if (s.has(f) && s.get(f).asDouble() <= 0) {
                            ctx.loss("sound-value-invalid", sid + "." + f, f + " must be > 0 in 1.7.10 (it rejects the whole file)");
                        }
                    }
                    if (s.has("type") && !Set.of("file", "event").contains(s.get("type").asString())) {
                        ctx.loss("sound-type-invalid", sid, "type " + s.get("type").asString() + " unknown to 1.7.10");
                    }
                    if (s.size() == 1 && s.has("name")) {
                        arr.set(i, s.get("name")); // compact form, same meaning
                    }
                }
            }
            tree.putText(p, ASCII.writeValueAsString(root) + "\n");
        }
        ctx.metric("sounds.events", events);
        ctx.metric("sounds.entries", sounds);
        ctx.metric("sounds.oggFiles", oggFiles);
    }
}
