package dev.porter.assets.model;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Face-alias texture maps ({@code textures/<target>/<dir>/texture_package.json}, a format some tech mods use
 * 1.19.2 instead of block models): {@code name → texture}. For block packages, names that are (or end in
 * {@code _<alias>}) one of {@code all, vertical, horizontal, side, xaxis, yaxis, zaxis, top, bottom, front, back,
 * left, right} also define the faces they stand for (front = south, back = north, left = west, right = east,
 * top = up, bottom = down, side/horizontal = the four horizontal faces, vertical = up+down).
 *
 * <p>Classification of block packages: all six faces → cube; {@code <state>_<face>} for all six faces → cube with
 * states (default state {@code inactive} if present, else the first state); anything else → needs-manual-render. The
 * owning block is not stated in the assets: an IR block may name it ({@code extensions["porter:texturePackage"]},
 * confidence 1.0); otherwise it is matched by name against known block ids (IR, else source lang keys) and reported as
 * a guess with confidence 0.6.
 */
public final class TexturePackages {
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("all", List.of("east", "west", "north", "south", "up", "down"));
        ALIASES.put("vertical", List.of("up", "down"));
        ALIASES.put("horizontal", List.of("east", "west", "north", "south"));
        ALIASES.put("side", List.of("east", "west", "north", "south"));
        ALIASES.put("xaxis", List.of("east", "west"));
        ALIASES.put("yaxis", List.of("up", "down"));
        ALIASES.put("zaxis", List.of("north", "south"));
        ALIASES.put("back", List.of("north"));
        ALIASES.put("front", List.of("south"));
        ALIASES.put("left", List.of("west"));
        ALIASES.put("right", List.of("east"));
        ALIASES.put("top", List.of("up"));
        ALIASES.put("bottom", List.of("down"));
    }

    public static final String FILE = "texture_package.json";
    /** IR block extension naming the block's texture package, e.g. {@code "examplemod:block/machine/battery"}. */
    public static final String IR_PACKAGE = "porter:texturePackage";

    private final AssetTree tree;
    private final Context ctx;
    private final RenderResult result;
    /** Package id (source form, e.g. ns:block/dir) → description, for meta/texture-packages.json. */
    public final Map<String, Map<String, Object>> packages = new TreeMap<>();
    /** Non-block, non-item packages: candidates for sheet packing (package id → sprite name → tree path). */
    public final Map<String, Map<String, String>> sheetCandidates = new TreeMap<>();

    public TexturePackages(AssetTree tree, Context ctx, RenderResult result) {
        this.tree = tree;
        this.ctx = ctx;
        this.result = result;
    }

    /** Decodes face aliases (an alias key is kept and also expanded). */
    static Map<String, String> decode(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String name = e.getKey();
            if (ALIASES.containsKey(name)) {
                ALIASES.get(name).forEach(f -> out.put(f, e.getValue()));
                out.put(name, e.getValue());
                continue;
            }
            int u = name.lastIndexOf('_');
            if (u >= 0 && ALIASES.containsKey(name.substring(u + 1))) {
                String pre = name.substring(0, u);
                ALIASES.get(name.substring(u + 1)).forEach(f -> out.put(pre + "_" + f, e.getValue()));
                out.put(name, e.getValue());
                continue;
            }
            out.put(name, e.getValue());
        }
        return out;
    }

    public void run() {
        List<String> pkgs = new ArrayList<>();
        for (String p : tree.paths()) {
            if (p.endsWith("/" + FILE) && AssetTree.local(p).startsWith("textures/")) {
                pkgs.add(p);
            }
        }
        Map<String, List<String>> claims = new TreeMap<>();
        // package → the blocks that name it (several blocks may share one package, e.g. a controller and its casing)
        Map<String, List<String>> declared = new TreeMap<>();
        Set<String> declaringBlocks = new TreeSet<>();
        for (IrNode.Block b : ctx.irNodes(IrNode.Block.class)) {
            if (b.extensions() != null && b.extensions().get(IR_PACKAGE) != null) {
                declared.computeIfAbsent(b.extensions().get(IR_PACKAGE).asString(), k -> new ArrayList<>()).add(b.id());
                declaringBlocks.add(b.id());
            }
        }
        Map<String, Map<String, Map<String, String>>> cubes = new LinkedHashMap<>();
        for (String p : pkgs) {
            String ns = AssetTree.namespace(p);
            String dir = AssetTree.local(p).substring("textures/".length(), AssetTree.local(p).length() - FILE.length() - 1);
            String target = dir.contains("/") ? dir.substring(0, dir.indexOf('/')) : dir;
            String sub = dir.contains("/") ? dir.substring(dir.indexOf('/') + 1) : "";
            String pkgId = ns + ":" + sourceDir(target) + (sub.isEmpty() ? "" : "/" + sub);
            Map<String, String> raw = new LinkedHashMap<>();
            JsonNode json = Json.json().readTree(tree.text(p));
            for (Map.Entry<String, JsonNode> e : json.properties()) {
                if (e.getValue().isString()) {
                    raw.put(e.getKey(), ModelConverter.normalise(e.getValue().asString()));
                } else {
                    ctx.warn("texture-package-non-string", p + "#" + e.getKey(), "ignored (the source loader ignores it too)");
                }
            }
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("file", p);
            Map<String, Object> sprites = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("texture", v);
                s.put("icon", LegacyIcons.icon(v));
                sprites.put(k, s);
            });
            desc.put("sprites", sprites);
            packages.put(pkgId, desc);
            if (target.equals("blocks")) {
                Map<String, Map<String, String>> states = classify(decode(raw));
                if (states == null) {
                    desc.put("kind", "sprites");
                    result.manual.put(pkgId, new RenderResult.Manual(pkgId, p,
                            "texture package is not a cube (sprites: " + String.join(", ", new TreeSet<>(raw.keySet())) + ")", null));
                } else {
                    desc.put("kind", states.containsKey("") ? "cube" : "cube-states");
                    cubes.put(pkgId, states);
                    if (declared.containsKey(pkgId)) {
                        for (String block : declared.get(pkgId)) {
                            claims.put(block, new ArrayList<>(List.of(pkgId))); // IR wins over name matches
                        }
                    } else {
                        String block = matchBlock(ns, sub);
                        if (block != null && !declaringBlocks.contains(block)) {
                            claims.computeIfAbsent(block, k -> new ArrayList<>()).add(pkgId);
                        }
                    }
                }
            } else if (target.equals("items")) {
                desc.put("kind", "item-sprites");
            } else {
                desc.put("kind", "sheet-candidate");
                Map<String, String> c = new TreeMap<>();
                raw.forEach((k, v) -> c.put(k, texturePath(v)));
                sheetCandidates.put(pkgId, c);
            }
        }
        int matched = 0;
        for (Map.Entry<String, List<String>> e : claims.entrySet()) {
            if (e.getValue().size() > 1) {
                ctx.warn("texture-package-ambiguous", e.getKey(), "claimed by " + e.getValue() + "; none assigned");
                continue;
            }
            String pkgId = e.getValue().get(0);
            Map<String, Map<String, String>> states = cubes.get(pkgId);
            String def = states.containsKey("") ? "" : states.containsKey("inactive") ? "inactive" : states.keySet().iterator().next();
            Map<String, Map<String, String>> others = new TreeMap<>(states);
            Map<String, String> textures = others.remove(def);
            if (result.blocks.containsKey(e.getKey())) {
                ctx.warn("texture-package-shadowed", pkgId, "block " + e.getKey() + " already has a model");
                continue;
            }
            boolean fromIr = declared.containsKey(pkgId);
            result.blocks.put(e.getKey(), new RenderResult.CubeBlock(e.getKey(), "cube", null,
                    (String) packages.get(pkgId).get("file"), fromIr ? 1.0 : 0.6, textures, others,
                    "texture package " + pkgId + (fromIr ? " (from IR)" : " matched by name")
                            + (def.isEmpty() ? "" : "; default state '" + def + "'")));
            if (packages.get(pkgId).containsKey("block")) {
                @SuppressWarnings("unchecked")
                List<String> more = (List<String>) packages.get(pkgId).computeIfAbsent("alsoBlocks", k -> new ArrayList<String>());
                more.add(e.getKey());
            } else {
                packages.get(pkgId).put("block", e.getKey());
            }
            if (!fromIr) {
                ctx.warn("texture-package-guessed-block", pkgId, "assigned to block " + e.getKey() + " by name match ("
                        + ctx.knownBlocks.get(e.getKey()) + ")");
            }
            matched++;
        }
        cubes.keySet().stream().filter(k -> !packages.get(k).containsKey("block"))
                .forEach(k -> ctx.warn("texture-package-unassigned", k, "cube texture package without a matching block id"));
        ctx.metric("texturePackages.total", pkgs.size());
        ctx.metric("texturePackages.cubes", cubes.size());
        ctx.metric("texturePackages.assignedToBlocks", matched);
        ctx.metric("texturePackages.sheetCandidates", sheetCandidates.size());
    }

    /** state ("" = none) → faces, or null when the package is not a (stateful) cube. */
    static Map<String, Map<String, String>> classify(Map<String, String> d) {
        Map<String, Map<String, String>> states = new TreeMap<>();
        if (hasAllFaces(d, "")) {
            states.put("", faces(d, ""));
            return states;
        }
        TreeSet<String> prefixes = new TreeSet<>();
        for (String k : d.keySet()) {
            int u = k.lastIndexOf('_');
            if (u > 0 && List.of(RenderResult.FACES).contains(k.substring(u + 1))) {
                prefixes.add(k.substring(0, u));
            }
        }
        for (String pre : prefixes) {
            if (hasAllFaces(d, pre + "_")) {
                states.put(pre, faces(d, pre + "_"));
            }
        }
        if (states.isEmpty()) {
            return null;
        }
        // every key must belong to a complete state (or be an alias name of one)
        for (String k : d.keySet()) {
            int u = k.lastIndexOf('_');
            String pre = u > 0 ? k.substring(0, u) : null;
            if (pre == null || !states.containsKey(pre)) {
                return null;
            }
        }
        return states;
    }

    private static boolean hasAllFaces(Map<String, String> d, String prefix) {
        for (String f : RenderResult.FACES) {
            if (!d.containsKey(prefix + f)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> faces(Map<String, String> d, String prefix) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : RenderResult.FACES) {
            m.put(f, d.get(prefix + f));
        }
        return m;
    }

    private String matchBlock(String ns, String sub) {
        if (sub.isEmpty()) {
            return null;
        }
        String[] seg = sub.split("/");
        String leaf = seg[seg.length - 1];
        List<String> candidates = new ArrayList<>();
        if (seg.length >= 2) {
            candidates.add(seg[seg.length - 2] + "_" + leaf);
            candidates.add(leaf + "_" + seg[seg.length - 2]);
        }
        candidates.add(leaf);
        int u = leaf.indexOf('_');
        if (u > 0) {
            candidates.add(leaf.substring(u + 1) + "_" + leaf.substring(0, u)); // lv_solar_panel -> solar_panel_lv
        }
        candidates.add(leaf + "_block");
        for (String c : candidates) {
            if (ctx.knownBlocks.containsKey(ns + ":" + c)) {
                return ns + ":" + c;
            }
        }
        return null;
    }

    private static String sourceDir(String target) {
        return switch (target) {
            case "blocks" -> "block";
            case "items" -> "item";
            default -> target;
        };
    }

    /** {@code ns:misc/gui/add} → {@code ns/textures/misc/gui/add.png}. */
    static String texturePath(String ref) {
        int c = ref.indexOf(':');
        return ref.substring(0, c) + "/textures/" + ref.substring(c + 1) + ".png";
    }
}
