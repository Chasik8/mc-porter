package dev.porter.assets.model;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON block models (1.8+) → per-side icons for 1.7.10.
 *
 * <p>For every {@code <ns>/blockstates/<path>.json} with {@code variants}, each referenced model is resolved through
 * its parent chain. A model is a <em>simple cube</em> when either a vanilla cube parent is reached (semantics below)
 * or its first element list is a single full, unrotated 0..16 cube. Simple cubes give {@code block.textures};
 * everything else (multipart, other vanilla parents, several or partial elements, unresolved {@code #vars}) is listed
 * as {@code needs-manual-render}, with an OBJ export when the model has elements.
 *
 * <p>Vanilla cube parents (face ← texture variable), from the vanilla model semantics:
 * {@code cube_all}: all ← all; {@code cube}: each face ← same-named variable; {@code cube_column}
 * ({@code _horizontal}): up/down ← end, sides ← side; {@code cube_bottom_top}: up ← top, down ← bottom, sides ← side;
 * {@code cube_top}: up ← top, rest ← side; {@code orientable_with_bottom}: up ← top, down ← bottom, north ← front,
 * rest ← side; {@code orientable}: as orientable_with_bottom with bottom = top.
 */
public final class ModelConverter {
    private static final Map<String, Map<String, String>> VANILLA = new LinkedHashMap<>();

    static {
        VANILLA.put("block/cube_all", faces("all", "all", "all", "all", "all", "all"));
        VANILLA.put("block/cube_mirrored_all", faces("all", "all", "all", "all", "all", "all"));
        VANILLA.put("block/cube", faces("down", "up", "north", "south", "west", "east"));
        VANILLA.put("block/cube_directional", faces("down", "up", "north", "south", "west", "east"));
        VANILLA.put("block/cube_column", faces("end", "end", "side", "side", "side", "side"));
        VANILLA.put("block/cube_column_horizontal", faces("end", "end", "side", "side", "side", "side"));
        VANILLA.put("block/cube_bottom_top", faces("bottom", "top", "side", "side", "side", "side"));
        VANILLA.put("block/cube_top", faces("side", "top", "side", "side", "side", "side"));
        VANILLA.put("block/orientable_with_bottom", faces("bottom", "top", "front", "side", "side", "side"));
        VANILLA.put("block/orientable", faces("top", "top", "front", "side", "side", "side"));
    }

    private static Map<String, String> faces(String... vars) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            m.put(RenderResult.FACES[i], "#" + vars[i]);
        }
        return m;
    }

    private final AssetTree tree;
    private final Context ctx;
    private final RenderResult result;

    public ModelConverter(AssetTree tree, Context ctx, RenderResult result) {
        this.tree = tree;
        this.ctx = ctx;
        this.result = result;
    }

    /** A model with its parent chain flattened. */
    record Resolved(String ref, Map<String, String> textures, JsonNode elements, String vanillaParent, String error) {
    }

    public void run() {
        int models = 0;
        for (String p : tree.paths()) {
            String ns = AssetTree.namespace(p);
            String local = AssetTree.local(p);
            if (!local.startsWith("blockstates/") || !local.endsWith(".json")) {
                continue;
            }
            models++;
            String path = local.substring("blockstates/".length(), local.length() - 5);
            String id = ns + ":" + path;
            JsonNode bs = Json.json().readTree(tree.text(p));
            if (bs.has("multipart")) {
                result.manual.put(id, new RenderResult.Manual(id, p, "multipart blockstate", null));
                continue;
            }
            JsonNode variants = bs.get("variants");
            if (!(variants instanceof ObjectNode vobj) || vobj.isEmpty()) {
                result.manual.put(id, new RenderResult.Manual(id, p, "blockstate without variants", null));
                continue;
            }
            List<String> keys = new ArrayList<>(vobj.propertyNames());
            keys.sort(null);
            String def = keys.contains("") ? "" : keys.get(0);
            Map<String, Map<String, String>> perVariant = new TreeMap<>();
            Map<String, String> modelOf = new TreeMap<>();
            String failure = null;
            Resolved failed = null;
            String shape = null;
            List<String> notes = new ArrayList<>();
            for (String k : keys) {
                JsonNode v = vobj.get(k);
                if (v instanceof ArrayNode arr) {
                    notes.add("variant '" + k + "' is a weighted list; first model used");
                    v = arr.get(0);
                }
                if (v.has("x") || v.has("y")) {
                    notes.add("variant '" + k + "' rotated x=" + v.path("x").asInt(0) + " y=" + v.path("y").asInt(0));
                }
                String ref = normalise(v.path("model").asString(""));
                modelOf.put(k, ref);
                Resolved r = resolve(ref);
                Map<String, String> f = r.error() == null ? cubeFaces(r) : null;
                if (f == null) {
                    failure = r.error() != null ? r.error() : "model " + ref + " is not a simple cube";
                    failed = r;
                    break;
                }
                if (k.equals(def)) {
                    shape = shapeOf(r);
                }
                perVariant.put(k, f);
            }
            if (failure != null) {
                String obj = failed != null && failed.elements() != null ? ObjExporter.export(id, failed, tree, ctx) : null;
                result.manual.put(id, new RenderResult.Manual(id, p, failure, obj));
                continue;
            }
            Map<String, String> textures = perVariant.get(def);
            Map<String, Map<String, String>> others = new TreeMap<>();
            perVariant.forEach((k, f) -> {
                if (!f.equals(textures)) {
                    others.put(k.isEmpty() ? "default" : k, f);
                }
            });
            result.blocks.put(id, new RenderResult.CubeBlock(id, shape, modelOf.get(def), p, 1.0, textures, others,
                    notes.isEmpty() ? null : String.join("; ", notes)));
        }
        ctx.metric("models.blockstates", models);
    }

    static String normalise(String ref) {
        return ref.contains(":") ? ref : "minecraft:" + ref;
    }

    Resolved resolve(String ref) {
        Map<String, String> textures = new LinkedHashMap<>();
        JsonNode elements = null;
        String current = ref;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            int c = current.indexOf(':');
            String file = current.substring(0, c) + "/models/" + current.substring(c + 1) + ".json";
            if (!tree.has(file)) {
                if (current.startsWith("minecraft:")) {
                    return new Resolved(ref, textures, elements, current.substring("minecraft:".length()), null);
                }
                return new Resolved(ref, textures, elements, null, "model file missing: " + file);
            }
            JsonNode m = Json.json().readTree(tree.text(file));
            if (m.get("textures") instanceof ObjectNode t) {
                for (Map.Entry<String, JsonNode> e : t.properties()) {
                    textures.putIfAbsent(e.getKey(), e.getValue().asString());
                }
            }
            if (elements == null && m.has("elements")) {
                elements = m.get("elements");
            }
            current = m.has("parent") ? normalise(m.get("parent").asString()) : null;
        }
        return new Resolved(ref, textures, elements, null, null);
    }

    private static String shapeOf(Resolved r) {
        if (r.elements() != null || r.vanillaParent() == null) {
            return "cube";
        }
        return switch (r.vanillaParent()) {
            case "block/cube_all", "block/cube_mirrored_all" -> "cube_all";
            case "block/cube_column", "block/cube_column_horizontal" -> "cube_column";
            case "block/orientable", "block/orientable_with_bottom" -> "orientable";
            default -> "cube";
        };
    }

    /** Face → resolved texture ref for a simple cube, else null. */
    Map<String, String> cubeFaces(Resolved r) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (r.elements() != null) {
            if (!(r.elements() instanceof ArrayNode arr) || arr.size() != 1) {
                return null;
            }
            JsonNode e = arr.get(0);
            if (e.has("rotation") || !isFull(e.get("from"), 0) || !isFull(e.get("to"), 16)) {
                return null;
            }
            JsonNode fs = e.get("faces");
            if (fs == null) {
                return null;
            }
            for (String f : RenderResult.FACES) {
                if (fs.has(f)) {
                    raw.put(f, fs.get(f).path("texture").asString(""));
                    JsonNode uv = fs.get(f).get("uv");
                    if (uv != null && !(uv.get(0).asDouble() == 0 && uv.get(1).asDouble() == 0
                            && uv.get(2).asDouble() == 16 && uv.get(3).asDouble() == 16)) {
                        ctx.warn("model-uv-ignored", r.ref() + "#" + f, "non-default uv " + uv + "; 1.7.10 icons use the whole sprite");
                    }
                }
            }
        } else if (r.vanillaParent() != null && VANILLA.containsKey(r.vanillaParent())) {
            raw.putAll(VANILLA.get(r.vanillaParent()));
        } else {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String t = resolveVar(e.getValue(), r.textures());
            if (t == null) {
                return null;
            }
            out.put(e.getKey(), t);
        }
        String particle = resolveVar("#particle", r.textures());
        if (particle != null) {
            out.put("particle", particle);
        }
        return out;
    }

    static String resolveVar(String v, Map<String, String> textures) {
        for (int i = 0; i < 16 && v != null && v.startsWith("#"); i++) {
            v = textures.get(v.substring(1));
        }
        return v == null || v.startsWith("#") || v.isEmpty() ? null : normalise(v);
    }

    private static boolean isFull(JsonNode a, double value) {
        return a != null && a.size() == 3 && a.get(0).asDouble() == value && a.get(1).asDouble() == value
                && a.get(2).asDouble() == value;
    }
}
