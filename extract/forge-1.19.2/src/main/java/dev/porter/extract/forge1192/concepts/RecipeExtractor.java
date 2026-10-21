package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.core.json.Json;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code recipe}: data-pack JSON ({@code data/<ns>/recipes/**.json}, incl. datagen output) and vanilla recipe
 * builders run in code (ShapedRecipeBuilder/ShapelessRecipeBuilder/SimpleCookingRecipeBuilder ... save(consumer)).
 */
public final class RecipeExtractor {
    public static final String ID_JSON = "recipe/json";
    public static final String ID_BUILDER = "recipe/builder";
    private static final Pattern FILE = Pattern.compile("^data/([a-z0-9_.-]+)/recipes/(.+)\\.json$");
    private static final Map<String, String> TYPES = Map.of(
            "minecraft:crafting_shaped", "shaped",
            "minecraft:crafting_shapeless", "shapeless",
            "minecraft:smelting", "smelting",
            "minecraft:blasting", "blasting",
            "minecraft:smoking", "smoking",
            "minecraft:campfire_cooking", "campfire",
            "minecraft:stonecutting", "stonecutting",
            "minecraft:smithing", "smithing");

    private RecipeExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        for (Path f : ctx.layout.resourceFiles(p -> FILE.matcher(p).matches())) {
            String rel = ctx.layout.resourcePath(f);
            Matcher m = FILE.matcher(rel);
            if (!m.matches()) {
                continue;
            }
            String id = m.group(1) + ":" + m.group(2);
            Provenance prov = ctx.provResource(f, ID_JSON);
            JsonNode root;
            try {
                root = Json.json().readTree(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                ctx.sink.unknown("recipe", id, "unreadable recipe json", rel, prov);
                continue;
            }
            IrNode node = fromJson(id, root, prov);
            if (node instanceof IrNode.Unknown u) {
                ctx.sink.add("recipe", u);
            } else {
                ctx.sink.add(node);
            }
        }
        fromBuilders(ctx);
    }

    // ---- JSON ----------------------------------------------------------------------------------------------

    static IrNode fromJson(String id, JsonNode root, Provenance prov) {
        String type = root.path("type").asString(null);
        if (type == null || !Vals.isResourceId(type)) {
            return unknown(id, "recipe json without a valid type", prov);
        }
        String kind = TYPES.get(type);
        try {
            if (kind == null) {
                return custom(id, type, root, prov);
            }
            IrNode.ItemStack result = stack(root.get("result"));
            switch (kind) {
                case "shaped": {
                    List<String> pattern = new ArrayList<>();
                    root.path("pattern").forEach(p -> pattern.add(p.asString()));
                    Map<String, IrNode.Ingredient> key = new LinkedHashMap<>();
                    for (var e : root.path("key").properties()) {
                        IrNode.Ingredient ing = ingredient(e.getValue());
                        if (ing == null) {
                            return unknown(id, "shaped recipe key '" + e.getKey() + "' is not an item/tag ingredient", prov);
                        }
                        key.put(e.getKey(), ing);
                    }
                    if (result == null || pattern.isEmpty()) {
                        return unknown(id, "shaped recipe without pattern or item result", prov);
                    }
                    return new IrNode.Recipe(id, "shaped", null, pattern, key, null, result, null, null, null, null, prov,
                            null, null);
                }
                case "shapeless": {
                    List<IrNode.Ingredient> ings = ingredients(root.get("ingredients"));
                    if (ings == null || result == null) {
                        return unknown(id, "shapeless recipe with unsupported ingredient or result", prov);
                    }
                    return new IrNode.Recipe(id, "shapeless", null, null, null, ings, result, null, null, null, null, prov,
                            null, null);
                }
                case "smithing": {
                    IrNode.Ingredient base = ingredient(root.get("base"));
                    IrNode.Ingredient add = ingredient(root.get("addition"));
                    if (base == null || add == null || result == null) {
                        return unknown(id, "smithing recipe with unsupported ingredient or result", prov);
                    }
                    return new IrNode.Recipe(id, "smithing", null, null, null, List.of(base, add), result, null, null, null,
                            null, prov, null, null);
                }
                default: {
                    IrNode.Ingredient in = ingredient(root.get("ingredient"));
                    if (kind.equals("stonecutting") && root.has("count") && result == null) {
                        result = null;
                    }
                    IrNode.ItemStack res = result;
                    if (kind.equals("stonecutting") && root.get("result") != null && root.get("result").isString()) {
                        int count = root.path("count").asInt(1);
                        res = new IrNode.ItemStack(root.get("result").asString(), count == 1 ? null : count, null);
                    }
                    if (in == null || res == null) {
                        return unknown(id, kind + " recipe with unsupported ingredient or result", prov);
                    }
                    Double xp = root.has("experience") ? root.get("experience").asDouble() : null;
                    Integer time = root.has("cookingtime") ? root.get("cookingtime").asInt() : null;
                    return new IrNode.Recipe(id, kind, null, null, null, List.of(in), res, null, xp, time, null, prov, null,
                            null);
                }
            }
        } catch (RuntimeException e) {
            return unknown(id, "malformed recipe json: " + e.getMessage(), prov);
        }
    }

    private static IrNode custom(String id, String type, JsonNode root, Provenance prov) {
        ObjectNode data = Vals.F.objectNode();
        List<IrNode.Ingredient> ings = null;
        IrNode.ItemStack result = null;
        for (var e : root.properties()) {
            String k = e.getKey();
            if (k.equals("type")) {
                continue;
            }
            if ((k.equals("ingredients") || k.equals("inputs")) && ings == null) {
                List<IrNode.Ingredient> l = ingredients(e.getValue());
                if (l != null) {
                    ings = l;
                    continue;
                }
            }
            if ((k.equals("ingredient") || k.equals("input")) && ings == null) {
                IrNode.Ingredient one = ingredient(e.getValue());
                if (one != null) {
                    ings = List.of(one);
                    continue;
                }
            }
            if ((k.equals("result") || k.equals("output")) && result == null) {
                IrNode.ItemStack s = stack(e.getValue());
                if (s != null) {
                    result = s;
                    continue;
                }
            }
            data.set(k, e.getValue());
        }
        return new IrNode.Recipe(id, "custom", type, null, null, ings, result, null, null, null,
                data.isEmpty() ? null : data, prov, null, null);
    }

    static IrNode.Unknown unknown(String id, String reason, Provenance prov) {
        return new IrNode.Unknown(Vals.isResourceId(id) ? id : null, reason, null, "recipe", prov, null);
    }

    static IrNode.ItemStack stack(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isString()) {
            return Vals.isResourceId(n.asString()) ? new IrNode.ItemStack(n.asString(), null, null) : null;
        }
        String item = n.path("item").asString(null);
        if (item == null || !Vals.isResourceId(item)) {
            return null;
        }
        Integer count = n.has("count") ? n.get("count").asInt() : null;
        JsonNode nbt = n.get("nbt");
        if (nbt != null && !nbt.isObject()) {
            return null; // SNBT string: not decoded
        }
        return new IrNode.ItemStack(item, count == null || count == 1 ? null : count, nbt);
    }

    static List<IrNode.Ingredient> ingredients(JsonNode n) {
        if (n == null || !n.isArray() || n.isEmpty()) {
            return null;
        }
        List<IrNode.Ingredient> out = new ArrayList<>();
        for (JsonNode e : n) {
            IrNode.Ingredient i = ingredient(e);
            if (i == null) {
                return null;
            }
            out.add(i);
        }
        return out;
    }

    static IrNode.Ingredient ingredient(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isArray()) {
            List<IrNode.Ingredient> any = new ArrayList<>();
            for (JsonNode e : n) {
                IrNode.Ingredient i = ingredient(e);
                if (i == null) {
                    return null;
                }
                any.add(i);
            }
            return any.size() == 1 ? any.get(0) : any.isEmpty() ? null : new IrNode.Ingredient(null, null, any, null);
        }
        if (!n.isObject()) {
            return null;
        }
        Set<String> keys = new java.util.HashSet<>();
        n.properties().forEach(e -> keys.add(e.getKey()));
        keys.remove("count");
        Integer count = n.has("count") ? n.get("count").asInt() : null;
        if (keys.equals(Set.of("item")) && Vals.isResourceId(n.get("item").asString())) {
            return new IrNode.Ingredient(n.get("item").asString(), null, null, count);
        }
        if (keys.equals(Set.of("tag")) && Vals.isResourceId(n.get("tag").asString())) {
            return new IrNode.Ingredient(null, n.get("tag").asString(), null, count);
        }
        return null;
    }

    // ---- values from the interpreter ------------------------------------------------------------------------

    /** Ingredient from an interpreter value (item-like, tag key, item stack); null if not representable. */
    public static IrNode.Ingredient ingredientOf(ExtractContext ctx, Object v) {
        if (v instanceof V.TagRef t) {
            return t.registry().equals("item") ? new IrNode.Ingredient(null, t.id(), null, null) : null;
        }
        if (v instanceof V.Stack s) {
            String item = ctx.itemIdOf(s.item());
            Integer count = Vals.integer(s.count());
            if (item == null || count == null || count < 1 || s.nbt() != null) {
                return null;
            }
            return new IrNode.Ingredient(item, null, null, count == 1 ? null : count);
        }
        if (v instanceof VObj vo && vo.type.equals("net.minecraft.world.item.crafting.Ingredient")) {
            List<IrNode.Ingredient> any = new ArrayList<>();
            for (Object a : Vals.flatten(vo.args)) {
                IrNode.Ingredient i = ingredientOf(ctx, a);
                if (i == null) {
                    return null;
                }
                any.add(i);
            }
            return any.size() == 1 ? any.get(0) : any.size() > 1 ? new IrNode.Ingredient(null, null, any, null) : null;
        }
        String item = ctx.itemIdOf(v);
        return item == null ? null : new IrNode.Ingredient(item, null, null, null);
    }

    public static IrNode.ItemStack stackOf(ExtractContext ctx, Object v) {
        if (v instanceof V.Stack s) {
            String item = ctx.itemIdOf(s.item());
            Integer count = Vals.integer(s.count());
            if (item == null || count == null || count < 1 || s.nbt() != null) {
                return null;
            }
            return new IrNode.ItemStack(item, count == 1 ? null : count, null);
        }
        String item = ctx.itemIdOf(v);
        return item == null ? null : new IrNode.ItemStack(item, null, null);
    }

    // ---- vanilla recipe builders run in code ----------------------------------------------------------------

    private static void fromBuilders(ExtractContext ctx) {
        for (Facts.Event e : ctx.facts.events) {
            if (!e.kind().equals("vanilla-recipe-builder")) {
                continue;
            }
            Provenance prov = ctx.prov(e.site(), ID_BUILDER);
            VObj b = (VObj) e.args().get(0);
            Object idArg = e.args().size() > 1 ? e.args().get(1) : null;
            IrNode node = fromBuilder(ctx, b, idArg, prov);
            if (node instanceof IrNode.Unknown u) {
                ctx.sink.add("recipe", u);
            } else if (!ctx.sink.add(node)) {
                ctx.note(ID_BUILDER, "duplicate recipe " + node.id());
            }
        }
    }

    static IrNode fromBuilder(ExtractContext ctx, VObj b, Object idArg, Provenance prov) {
        String simple = b.type.substring(b.type.lastIndexOf('.') + 1);
        String fact = b.factory;
        IrNode.ItemStack result = null;
        Object resultItem = null;
        Object countArg = null;
        if (simple.equals("ShapedRecipeBuilder") || simple.equals("ShapelessRecipeBuilder")) {
            resultItem = b.args.isEmpty() ? null : b.args.get(0);
            countArg = b.args.size() > 1 ? b.args.get(1) : 1;
        } else if (simple.equals("SimpleCookingRecipeBuilder")) {
            resultItem = b.args.size() > 1 ? b.args.get(1) : null;
            countArg = 1;
        } else if (simple.equals("SingleItemRecipeBuilder")) {
            resultItem = b.args.size() > 1 ? b.args.get(1) : null;
            countArg = b.args.size() > 2 ? b.args.get(2) : 1;
        }
        String item = ctx.itemIdOf(resultItem);
        Integer count = Vals.integer(countArg);
        if (item == null || count == null) {
            return unknown(null, "recipe builder result not computable", prov);
        }
        result = new IrNode.ItemStack(item, count == 1 ? null : count, null);
        String id;
        if (idArg == null) {
            id = item;
        } else if (idArg instanceof String s) {
            id = s.contains(":") ? s : "minecraft:" + s;
        } else if (idArg instanceof V.ResLoc rl) {
            id = rl.toString();
        } else {
            return unknown(null, "recipe id not computable", prov);
        }
        switch (simple) {
            case "ShapedRecipeBuilder": {
                List<String> pattern = new ArrayList<>();
                Map<String, IrNode.Ingredient> key = new LinkedHashMap<>();
                for (V.Call c : b.calls) {
                    if (c.name().equals("pattern") && c.args().size() == 1 && c.args().get(0) instanceof String p) {
                        pattern.add(p);
                    } else if (c.name().equals("define") && c.args().size() == 2) {
                        Object ch = c.args().get(0);
                        IrNode.Ingredient ing = ingredientOf(ctx, c.args().get(1));
                        if (!(ch instanceof Character k) || ing == null) {
                            return unknown(id, "shaped builder key not representable", prov);
                        }
                        key.put(String.valueOf(k), ing);
                    }
                }
                return new IrNode.Recipe(id, "shaped", null, pattern, key, null, result, null, null, null, null, prov,
                        null, null);
            }
            case "ShapelessRecipeBuilder": {
                List<IrNode.Ingredient> ings = new ArrayList<>();
                for (V.Call c : b.calls) {
                    if (c.name().equals("requires") && !c.args().isEmpty()) {
                        IrNode.Ingredient ing = ingredientOf(ctx, c.args().get(0));
                        Integer times = c.args().size() > 1 ? Vals.integer(c.args().get(1)) : Integer.valueOf(1);
                        if (ing == null || times == null) {
                            return unknown(id, "shapeless builder ingredient not representable", prov);
                        }
                        for (int i = 0; i < times; i++) {
                            ings.add(ing);
                        }
                    }
                }
                return new IrNode.Recipe(id, "shapeless", null, null, null, ings, result, null, null, null, null, prov,
                        null, null);
            }
            case "SimpleCookingRecipeBuilder": {
                String kind = switch (fact == null ? "" : fact) {
                    case "smelting" -> "smelting";
                    case "blasting" -> "blasting";
                    case "smoking" -> "smoking";
                    case "campfireCooking" -> "campfire";
                    default -> null;
                };
                IrNode.Ingredient in = b.args.isEmpty() ? null : ingredientOf(ctx, b.args.get(0));
                Double xp = b.args.size() > 2 ? Vals.number(b.args.get(2)) : null;
                Integer time = b.args.size() > 3 ? Vals.integer(b.args.get(3)) : null;
                if (kind == null || in == null) {
                    return unknown(id, "cooking builder not representable", prov);
                }
                return new IrNode.Recipe(id, kind, null, null, null, List.of(in), result, null, xp, time, null, prov, null,
                        null);
            }
            case "SingleItemRecipeBuilder": {
                IrNode.Ingredient in = b.args.isEmpty() ? null : ingredientOf(ctx, b.args.get(0));
                if (in == null) {
                    return unknown(id, "stonecutting builder not representable", prov);
                }
                return new IrNode.Recipe(id, "stonecutting", null, null, null, List.of(in), result, null, null, null,
                        null, prov, null, null);
            }
            default:
                return unknown(id, "recipe builder " + simple + " not supported", prov);
        }
    }

    /** Interpreter description, used by packs. */
    public static String describe(Object v) {
        return Interpreter.describe(v);
    }
}
