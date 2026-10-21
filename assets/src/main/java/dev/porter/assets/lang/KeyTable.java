package dev.porter.assets.lang;

import dev.porter.assets.Context;
import dev.porter.core.ir.IrNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Translation-key table for the 1.13 flattening boundary (1.13+ keys → ≤ 1.12 keys).
 *
 * <p>Only keys that the target platform computes itself are renamed; every other key (GUI strings, tooltips,
 * chat messages, ...) is looked up by mod code with a literal string and is therefore kept verbatim. Sources, by
 * priority:
 * <ol>
 *   <li>{@code --key-map} overrides (exact source key → target key);</li>
 *   <li>IR nodes: {@code block}, {@code item}, {@code fluid}, {@code effect} (their {@code translationKey}, or the
 *       1.19.2 default key for their id). The legacy unlocalized name is
 *       {@code extensions["porter:legacyName"]} if present, else derived from the id with {@link Context.LegacyNames};</li>
 *   <li>documented defaults by key pattern (below) — every such key is reported as guessed.</li>
 * </ol>
 *
 * <p>Default patterns (target forms verified against the 1.7.10 / Forge 10.13.4 sources):
 * <pre>
 * block.NS.P        → tile.L.name          Block#getUnlocalizedName = "tile." + name, + ".name"
 * item.NS.P         → item.L.name          Item#getUnlocalizedName  = "item." + name, + ".name"
 * fluid_type.NS.P   → fluid.L              Fluid#getUnlocalizedName = "fluid." + name
 * fluid.NS.P        → fluid.L              (mods that key fluids as fluid.NS.P)
 * effect.NS.P       → potion.L             vanilla convention Potion#setPotionName("potion.x")
 * enchantment.NS.P  → enchantment.L        Enchantment#getName = "enchantment." + name
 * entity.NS.P       → entity.NS.P.name     EntityRegistry names mod entities "modid.name"; Entity: "entity." + n + ".name"
 * </pre>
 * where {@code P} must match {@code [a-z0-9_/-]+} (no further dots) and {@code L} is the legacy name. Keys of the form
 * {@code itemGroup.*}, {@code key.*}, {@code death.attack.*} have the same lookup on both sides and are kept.
 */
public final class KeyTable {
    /** How a target key was obtained. */
    public enum Origin { OVERRIDE, IR, DEFAULT, KEPT }

    public record Mapping(String target, Origin origin, String reason) {
        public boolean guessed() {
            return origin == Origin.DEFAULT;
        }

        public boolean renamed(String source) {
            return !target.equals(source);
        }
    }

    private static final Pattern DEFAULT = Pattern.compile(
            "^(block|item|fluid_type|fluid|effect|enchantment|entity)\\.([a-z0-9_-]+)\\.([a-z0-9_/-]+)$");

    private final Map<String, Mapping> explicit = new LinkedHashMap<>();
    private final Context ctx;

    public KeyTable(Context ctx) {
        this.ctx = ctx;
        ctx.keyMapOverrides.forEach((k, v) -> explicit.put(k, new Mapping(v, Origin.OVERRIDE, "--key-map")));
        for (IrNode.Block b : ctx.irNodes(IrNode.Block.class)) {
            String src = b.translationKey() != null ? b.translationKey() : "block." + dotted(b.id());
            putIr(src, "tile." + legacyName(b.id(), b.extensions()) + ".name", "ir block " + b.id());
        }
        for (IrNode.Item i : ctx.irNodes(IrNode.Item.class)) {
            String src = i.translationKey() != null ? i.translationKey() : "item." + dotted(i.id());
            if (i.placesBlock() != null && src.startsWith("block.")) {
                continue; // block items share the block's key; 1.7.10 ItemBlock uses the block name
            }
            putIr(src, "item." + legacyName(i.id(), i.extensions()) + ".name", "ir item " + i.id());
        }
        for (IrNode.Fluid f : ctx.irNodes(IrNode.Fluid.class)) {
            String src = f.translationKey() != null ? f.translationKey() : "fluid_type." + dotted(f.id());
            putIr(src, "fluid." + legacyName(f.id(), f.extensions()), "ir fluid " + f.id());
        }
        for (IrNode.Effect e : ctx.irNodes(IrNode.Effect.class)) {
            String src = e.translationKey() != null ? e.translationKey() : "effect." + dotted(e.id());
            putIr(src, "potion." + legacyName(e.id(), e.extensions()), "ir effect " + e.id());
        }
    }

    private void putIr(String source, String target, String reason) {
        explicit.putIfAbsent(source, new Mapping(target, Origin.IR, reason));
    }

    private static String dotted(String id) {
        return id.replace(':', '.').replace('/', '.');
    }

    private String legacyName(String id, Map<String, JsonNode> ext) {
        if (ext != null && ext.get("porter:legacyName") != null) {
            return ext.get("porter:legacyName").asString();
        }
        int c = id.indexOf(':');
        return legacyName(id.substring(0, c), id.substring(c + 1));
    }

    private String legacyName(String ns, String path) {
        return "minecraft".equals(ns) ? Context.LegacyNames.PATH.apply(ns, path) : ctx.legacyNames.apply(ns, path);
    }

    /** Target key for a source key. */
    public Mapping map(String key) {
        Mapping m = explicit.get(key);
        if (m != null) {
            return m;
        }
        Matcher x = DEFAULT.matcher(key);
        if (x.matches()) {
            String ns = x.group(2);
            String path = x.group(3);
            String l = legacyName(ns, path);
            String target = switch (x.group(1)) {
                case "block" -> "tile." + l + ".name";
                case "item" -> "item." + l + ".name";
                case "fluid_type", "fluid" -> "fluid." + l;
                case "effect" -> "potion." + l;
                case "enchantment" -> "enchantment." + l;
                case "entity" -> "entity." + ns + "." + path.replace('/', '.') + ".name";
                default -> throw new IllegalStateException();
            };
            return new Mapping(target, Origin.DEFAULT, "default pattern " + x.group(1) + ".NS.P");
        }
        return new Mapping(key, Origin.KEPT, null);
    }
}
