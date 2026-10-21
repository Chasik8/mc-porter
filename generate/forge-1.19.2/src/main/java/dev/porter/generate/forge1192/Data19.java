package dev.porter.generate.forge1192;

import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import dev.porter.generate.GenContext;
import dev.porter.generate.GenOutput;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** IR → 1.19.2 resources: lang, sounds.json, recipes, tags (+ harvest tags), blockstates/models, mods.toml, pack.mcmeta. */
final class Data19 {
    private static final String R = GenOutput.RESOURCES_ROOT;
    private final Names19 n;
    private final GenContext ctx;

    Data19(Names19 n) {
        this.n = n;
        this.ctx = n.ctx;
    }

    void run() {
        lang();
        sounds();
        recipes();
        tags();
        models();
        if (!ctx.options.embed()) {
            modsToml();
        }
    }

    private void lang() {
        for (IrNode.Lang l : ctx.ir.all(IrNode.Lang.class)) {
            ctx.out.putJson(R + "assets/" + Names.namespace(l.id()) + "/lang/" + l.locale() + ".json", l.entries());
        }
    }

    private void sounds() {
        Map<String, Map<String, Object>> byNs = new TreeMap<>();
        for (IrNode.Sound s : ctx.ir.all(IrNode.Sound.class)) {
            Map<String, Object> e = new LinkedHashMap<>();
            if (s.category() != null) {
                e.put("category", s.category());
            }
            if (Boolean.TRUE.equals(s.replace())) {
                e.put("replace", true);
            }
            if (s.subtitle() != null) {
                e.put("subtitle", s.subtitle());
            }
            List<Object> files = new ArrayList<>();
            for (IrNode.Sound.SoundFile f : s.sounds()) {
                if (f.volume() == null && f.pitch() == null && f.weight() == null && f.stream() == null) {
                    files.add(f.name());
                } else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", f.name());
                    if (f.volume() != null) {
                        m.put("volume", f.volume());
                    }
                    if (f.pitch() != null) {
                        m.put("pitch", f.pitch());
                    }
                    if (f.weight() != null) {
                        m.put("weight", f.weight());
                    }
                    if (f.stream() != null) {
                        m.put("stream", f.stream());
                    }
                    files.add(m);
                }
            }
            e.put("sounds", files);
            byNs.computeIfAbsent(Names.namespace(s.id()), k -> new LinkedHashMap<>()).put(Names.path(s.id()), e);
        }
        byNs.forEach((ns, m) -> ctx.out.putJson(R + "assets/" + ns + "/sounds.json", m));
    }

    // ---- recipes ---------------------------------------------------------------------------------

    private Object ingredient(IrNode.Ingredient in) {
        if (in.anyOf() != null) {
            List<Object> l = new ArrayList<>();
            in.anyOf().forEach(a -> l.add(ingredient(a)));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (in.item() != null) {
            m.put("item", in.item());
        } else {
            m.put("tag", in.tag());
        }
        if (in.count() != null) {
            m.put("count", in.count());
        }
        return m;
    }

    private Map<String, Object> stack(IrNode.ItemStack s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", s.item());
        if (s.count() != null) {
            m.put("count", s.count());
        }
        if (s.nbt() != null && !s.nbt().isNull()) {
            m.put("nbt", s.nbt());
        }
        return m;
    }

    private void recipes() {
        for (IrNode.Recipe r : ctx.ir.all(IrNode.Recipe.class)) {
            Map<String, Object> j = new LinkedHashMap<>();
            switch (r.recipeType()) {
                case "shaped" -> {
                    j.put("type", "minecraft:crafting_shaped");
                    j.put("pattern", r.pattern());
                    Map<String, Object> key = new LinkedHashMap<>();
                    r.key().forEach((k, v) -> key.put(k, ingredient(v)));
                    j.put("key", key);
                    j.put("result", stack(r.result()));
                }
                case "shapeless" -> {
                    j.put("type", "minecraft:crafting_shapeless");
                    List<Object> l = new ArrayList<>();
                    r.ingredients().forEach(i -> l.add(ingredient(i)));
                    j.put("ingredients", l);
                    j.put("result", stack(r.result()));
                }
                case "smelting", "blasting", "smoking", "campfire" -> {
                    j.put("type", "campfire".equals(r.recipeType()) ? "minecraft:campfire_cooking" : "minecraft:" + r.recipeType());
                    j.put("ingredient", ingredient(r.ingredients().get(0)));
                    j.put("result", r.result().item());
                    if (r.experience() != null) {
                        j.put("experience", r.experience());
                    }
                    if (r.cookingTime() != null) {
                        j.put("cookingtime", r.cookingTime());
                    }
                }
                case "stonecutting" -> {
                    j.put("type", "minecraft:stonecutting");
                    j.put("ingredient", ingredient(r.ingredients().get(0)));
                    j.put("result", r.result().item());
                    j.put("count", r.result().count() == null ? 1 : r.result().count());
                }
                case "smithing" -> {
                    j.put("type", "minecraft:smithing");
                    j.put("base", ingredient(r.ingredients().get(0)));
                    j.put("addition", ingredient(r.ingredients().get(1)));
                    j.put("result", stack(r.result()));
                }
                default -> {
                    j.put("type", r.customType());
                    if (r.ingredients() != null) {
                        List<Object> l = new ArrayList<>();
                        r.ingredients().forEach(i -> l.add(ingredient(i)));
                        j.put("ingredients", l);
                    }
                    if (r.result() != null) {
                        j.put("result", stack(r.result()));
                    }
                    if (r.results() != null) {
                        List<Object> l = new ArrayList<>();
                        r.results().forEach(s -> l.add(stack(s)));
                        j.put("results", l);
                    }
                    if (r.data() != null && r.data().isObject()) {
                        for (var e : r.data().properties()) {
                            j.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                    ctx.info("recipe-custom-layout", r.id(), "custom recipe written as {type, ingredients, result(s), ...data}; "
                            + "the serializer of " + r.customType() + " may expect another layout");
                }
            }
            ctx.out.putJson(R + "data/" + Names.namespace(r.id()) + "/recipes/" + Names.path(r.id()) + ".json", j);
        }
    }

    // ---- tags ------------------------------------------------------------------------------------

    private static String folder(String registry) {
        return switch (registry) {
            case "block" -> "blocks";
            case "item" -> "items";
            case "fluid" -> "fluids";
            default -> "entity_types";
        };
    }

    private void tags() {
        Map<String, Map<String, Object>> files = new TreeMap<>();
        for (IrNode.Tag t : ctx.ir.all(IrNode.Tag.class)) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("replace", Boolean.TRUE.equals(t.replace()));
            List<Object> values = new ArrayList<>();
            for (IrNode.TagValue v : t.values()) {
                if (v.required() == null) {
                    values.add(v.id());
                } else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", v.id());
                    m.put("required", v.required());
                    values.add(m);
                }
            }
            j.put("values", values);
            files.put(R + "data/" + Names.namespace(t.id()) + "/tags/" + folder(t.registry()) + "/" + Names.path(t.id()) + ".json", j);
        }
        // harvest data from block nodes (1.19.2 keeps it in tags)
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (b.harvest() == null || b.harvest().tool() == null) {
                continue;
            }
            addTagValue(files, "minecraft:mineable/" + b.harvest().tool(), b.id());
            Integer level = b.harvest().level();
            String tier = level == null ? null : switch (level) {
                case 1 -> "minecraft:needs_stone_tool";
                case 2 -> "minecraft:needs_iron_tool";
                case 3 -> "minecraft:needs_diamond_tool";
                default -> null;
            };
            if (tier != null) {
                addTagValue(files, tier, b.id());
            }
        }
        files.forEach(ctx.out::putJson);
    }

    @SuppressWarnings("unchecked")
    private void addTagValue(Map<String, Map<String, Object>> files, String tag, String value) {
        String path = R + "data/" + Names.namespace(tag) + "/tags/blocks/" + Names.path(tag) + ".json";
        Map<String, Object> j = files.computeIfAbsent(path, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("replace", false);
            m.put("values", new ArrayList<>());
            return m;
        });
        List<Object> values = (List<Object>) j.get("values");
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    // ---- models ----------------------------------------------------------------------------------

    private void models() {
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            boolean fluidBlock = ctx.ir.all(IrNode.Fluid.class).stream().anyMatch(f -> b.id().equals(f.block()));
            if (fluidBlock) {
                ctx.info("fluid-block-model", b.id(), "fluid block: blockstate/model not generated");
                continue;
            }
            if (b.textures() == null || b.textures().isEmpty()) {
                ctx.skip("block-model-missing", b.id(), "no textures in IR; blockstate/model not generated (copy them from the source mod)");
                continue;
            }
            String ns = Names.namespace(b.id());
            String path = Names.path(b.id());
            String modelRef = b.model() != null && b.model().ref() != null ? b.model().ref() : ns + ":block/" + path;
            String shape = b.model() != null && b.model().shape() != null ? b.model().shape() : null;
            Map<String, Object> model = model(b, shape);
            ctx.out.putJson(R + "assets/" + Names.namespace(modelRef) + "/models/" + Names.path(modelRef) + ".json", model);
            ctx.out.putJson(R + "assets/" + ns + "/blockstates/" + path + ".json", blockstate(b, modelRef));
        }
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            String ns = Names.namespace(i.id());
            String path = Names.path(i.id());
            Map<String, Object> m = new LinkedHashMap<>();
            if (i.placesBlock() != null) {
                var b = ctx.ir.get(IrNode.Block.class, i.placesBlock());
                if (b.isEmpty() || b.get().textures() == null) {
                    continue;
                }
                String ref = b.get().model() != null && b.get().model().ref() != null ? b.get().model().ref()
                        : Names.namespace(b.get().id()) + ":block/" + Names.path(b.get().id());
                m.put("parent", ref);
            } else if (i.textures() != null && !i.textures().isEmpty()) {
                m.put("parent", "minecraft:item/generated");
                m.put("textures", new TreeMap<>(i.textures()));
            } else {
                ctx.skip("item-model-missing", i.id(), "no textures in IR; item model not generated");
                continue;
            }
            ctx.out.putJson(R + "assets/" + ns + "/models/item/" + path + ".json", m);
        }
    }

    private Map<String, Object> model(IrNode.Block b, String shape) {
        Map<String, String> tex = b.textures();
        String all = tex.getOrDefault("all", tex.get("particle"));
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> t = new LinkedHashMap<>();
        boolean uniform = tex.keySet().stream().allMatch(k -> k.equals("all") || k.equals("particle"));
        if ("orientable".equals(shape)) {
            m.put("parent", "minecraft:block/orientable");
            t.put("top", tex.getOrDefault("up", all));
            t.put("front", tex.getOrDefault("north", all));
            t.put("side", tex.getOrDefault("east", all));
        } else if (uniform || "cube_all".equals(shape)) {
            m.put("parent", "minecraft:block/cube_all");
            t.put("all", all);
        } else {
            m.put("parent", "minecraft:block/cube");
            for (String f : new String[] {"down", "up", "north", "south", "west", "east"}) {
                t.put(f, tex.getOrDefault(f, all));
            }
            if (shape != null && !"cube".equals(shape)) {
                ctx.info("block-model-shape", b.id(), "model shape " + shape + " written as a 6-face cube");
            }
        }
        if (tex.containsKey("particle")) {
            t.put("particle", tex.get("particle"));
        }
        t.values().removeIf(v -> v == null);
        m.put("textures", t);
        return m;
    }

    private Map<String, Object> blockstate(IrNode.Block b, String modelRef) {
        List<IrNode.Block.StateProperty> props = b.stateProperties() == null ? List.of() : b.stateProperties();
        Map<String, Object> variants = new LinkedHashMap<>();
        List<Map<String, String>> combos = new ArrayList<>();
        combos.add(new LinkedHashMap<>());
        for (IrNode.Block.StateProperty p : props) {
            List<String> values = p.values() != null && !p.values().isEmpty() ? p.values()
                    : "boolean".equals(p.type()) ? List.of("false", "true") : List.of("down", "up", "north", "south", "west", "east");
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> c : combos) {
                for (String v : values) {
                    Map<String, String> m = new LinkedHashMap<>(c);
                    m.put(p.name(), v);
                    next.add(m);
                }
            }
            combos = next;
            if (combos.size() > 512) {
                ctx.warn("blockstate-too-many", b.id(), "more than 512 states; single variant written");
                combos = List.of(new LinkedHashMap<>());
                break;
            }
        }
        for (Map<String, String> c : combos) {
            List<String> key = new ArrayList<>();
            new TreeMap<>(c).forEach((k, v) -> key.add(k + "=" + v));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("model", modelRef);
            String facing = c.get("facing");
            int y = facing == null ? 0 : switch (facing) {
                case "east" -> 90;
                case "south" -> 180;
                case "west" -> 270;
                default -> 0;
            };
            if (y != 0) {
                v.put("y", y);
            }
            variants.put(String.join(",", key), v);
        }
        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("variants", variants);
        return bs;
    }

    // ---- mod metadata ----------------------------------------------------------------------------

    private void modsToml() {
        StringBuilder b = new StringBuilder();
        b.append("modLoader=\"javafml\"\nloaderVersion=\"[43,)\"\n");
        String license = ctx.ir.mod().map(IrNode.Mod::license).orElse(null);
        b.append("license=\"").append(esc(license == null ? "All rights reserved" : license)).append("\"\n\n[[mods]]\n");
        b.append("modId=\"").append(esc(ctx.options.modId())).append("\"\n");
        b.append("version=\"").append(esc(ctx.options.modVersion())).append("\"\n");
        b.append("displayName=\"").append(esc(ctx.options.modName())).append("\"\n");
        ctx.ir.mod().ifPresent(m -> {
            if (m.authors() != null && !m.authors().isEmpty()) {
                b.append("authors=\"").append(esc(String.join(", ", m.authors()))).append("\"\n");
            }
            if (m.description() != null) {
                b.append("description='''").append(m.description().replace("'''", "")).append("'''\n");
            }
        });
        List<IrNode.Mod.Dependency> deps = ctx.ir.mod().map(IrNode.Mod::dependencies).orElse(null);
        if (deps == null || deps.isEmpty()) {
            deps = List.of(new IrNode.Mod.Dependency("forge", "[43,)", true, null),
                    new IrNode.Mod.Dependency("minecraft", "[1.19.2,1.20)", true, null));
        }
        for (IrNode.Mod.Dependency d : deps) {
            b.append("\n[[dependencies.").append(esc(ctx.options.modId())).append("]]\n");
            b.append("modId=\"").append(esc(d.modId())).append("\"\n");
            b.append("mandatory=").append(!Boolean.FALSE.equals(d.required())).append("\n");
            b.append("versionRange=\"").append(esc(d.versionRange() == null ? "*" : d.versionRange())).append("\"\n");
            b.append("ordering=\"").append(d.ordering() == null ? "NONE" : d.ordering().toUpperCase(java.util.Locale.ROOT)).append("\"\n");
            b.append("side=\"BOTH\"\n");
        }
        ctx.out.put(R + "META-INF/mods.toml", b.toString());
        Map<String, Object> pack = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("description", ctx.options.modName() + " resources");
        p.put("pack_format", 9);
        p.put("forge:resource_pack_format", 9);
        p.put("forge:data_pack_format", 10);
        pack.put("pack", p);
        ctx.out.putJson(R + "pack.mcmeta", pack);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static JsonNode tree(Object o) {
        return Json.json().valueToTree(o);
    }
}
