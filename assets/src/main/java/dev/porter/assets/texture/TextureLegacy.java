package dev.porter.assets.texture;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 1.7.10 texture rules (verified against the 1.7.10 sources):
 * <ul>
 *   <li>{@code .mcmeta}: sections {@code animation} ({@code frametime, frames, width, height}) and {@code texture}
 *       ({@code blur, clamp, mipmaps}) only; {@code animation.interpolate} and unknown sections are dropped and
 *       reported.</li>
 *   <li>Atlas sprites ({@code textures/blocks}, {@code textures/items}): a non-animated sprite must be square
 *       ({@code TextureAtlasSprite}: "broken aspect ratio and not an animation"); animated strips must hold whole
 *       frames.</li>
 * </ul>
 */
public final class TextureLegacy {
    private static final Map<String, Set<String>> SECTIONS = Map.of(
            "animation", Set.of("frametime", "frames", "width", "height"),
            "texture", Set.of("blur", "clamp", "mipmaps"));

    private TextureLegacy() {
    }

    public static void apply(AssetTree tree, Context ctx) {
        int mcmeta = 0;
        int sprites = 0;
        int animated = 0;
        for (String p : tree.paths()) {
            if (p.endsWith(".png.mcmeta")) {
                mcmeta++;
                strictMcmeta(tree, p, ctx);
            }
        }
        for (String p : tree.paths()) {
            String local = AssetTree.local(p);
            if (!p.endsWith(".png") || !(local.startsWith("textures/blocks/") || local.startsWith("textures/items/"))) {
                continue;
            }
            sprites++;
            int[] wh = Png.size(tree.get(p));
            if (wh == null) {
                ctx.warn("texture-unreadable", p, "not a PNG");
                continue;
            }
            JsonNode anim = null;
            if (tree.has(p + ".mcmeta")) {
                anim = Json.json().readTree(tree.text(p + ".mcmeta")).get("animation");
            }
            if (anim == null) {
                if (wh[0] != wh[1]) {
                    ctx.warn("texture-not-square", p, wh[0] + "x" + wh[1]
                            + " without animation: 1.7.10 refuses it as an icon (fine if only bound directly)");
                }
            } else {
                animated++;
                int fw = anim.has("width") ? anim.get("width").asInt() : Math.min(wh[0], wh[1]);
                int fh = anim.has("height") ? anim.get("height").asInt() : fw;
                if (fw <= 0 || fh <= 0 || wh[0] % fw != 0 || wh[1] % fh != 0) {
                    ctx.warn("texture-animation-size", p, "frame " + fw + "x" + fh + " does not tile " + wh[0] + "x" + wh[1]);
                }
            }
        }
        ctx.metric("textures.atlasSprites", sprites);
        ctx.metric("textures.animated", animated);
        ctx.metric("textures.mcmeta", mcmeta);
    }

    private static void strictMcmeta(AssetTree tree, String p, Context ctx) {
        JsonNode root = Json.json().readTree(tree.text(p));
        if (!(root instanceof ObjectNode obj)) {
            ctx.loss("mcmeta-invalid", p, "not a JSON object");
            return;
        }
        boolean changed = false;
        for (String section : new ArrayList<>(obj.propertyNames())) {
            Set<String> allowed = SECTIONS.get(section);
            if (allowed == null) {
                ctx.warn("dropped-field", p + "#" + section, "mcmeta section unknown to 1.7.10");
                obj.remove(section);
                changed = true;
                continue;
            }
            if (obj.get(section) instanceof ObjectNode sec) {
                for (String f : new ArrayList<>(sec.propertyNames())) {
                    if (!allowed.contains(f)) {
                        boolean noEffect = "interpolate".equals(f) && !sec.get(f).asBoolean();
                        if (!noEffect) {
                            ctx.warn("dropped-field", p + "#" + section + "." + f, "ignored by 1.7.10, removed"
                                    + ("interpolate".equals(f) ? " (frames will switch without blending)" : ""));
                        }
                        sec.remove(f);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            tree.putText(p, Json.json().writeValueAsString(root) + "\n");
        }
    }
}
