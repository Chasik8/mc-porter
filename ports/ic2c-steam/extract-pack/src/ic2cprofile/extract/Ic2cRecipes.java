package ic2cprofile.extract;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.concepts.RecipeExtractor;
import dev.porter.extract.forge1192.concepts.Vals;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IC2C recipes from the recorded API calls: crafting (IAdvancedCraftingManager shaped/shapeless/smelting/
 * stonecutting/smithing) and machine recipe lists (IMachineRecipeList, refining...). Machine recipes become
 * {@code custom} recipes whose customType is {@code ic2:<list id>} (the id under which GlobalRecipes creates the
 * list whose loader ran); fluids, chances, ranges and XP go into {@code data}.
 */
final class Ic2cRecipes {
    static final String ID = "recipe/ic2c-api";

    private Ic2cRecipes() {
    }

    static void extract(ExtractContext ctx) {
        Map<String, String> listOfRoot = recipeListsByLoader(ctx);
        Set<String> seen = new HashSet<>();
        for (Facts.Event e : ctx.facts.events) {
            if (!e.kind().equals("ic2c-api")) {
                continue;
            }
            VObj chain = (VObj) e.args().get(0);
            String key = Integer.toHexString(System.identityHashCode(e.site())) + "|" + chain.args.size() + "|"
                    + (chain.args.isEmpty() ? "" : String.valueOf(chain.args.get(0)));
            if (!seen.add(key + "|" + e.name() + "|" + argsKey(chain.args))) {
                continue; // the same loader ran twice
            }
            Provenance prov = ctx.prov(e.site(), ID);
            List<IrNode> nodes;
            try {
                nodes = convert(ctx, e.name(), chain, listOfRoot.get(e.root()), prov);
            } catch (RuntimeException ex) {
                nodes = List.of(unknown(null, "conversion failed: " + ex, prov));
            }
            for (IrNode n : nodes) {
                if (n == null) {
                    continue;
                }
                if (n instanceof IrNode.Unknown u) {
                    ctx.sink.add(u.conceptHint(), withConditions(u, e.conditions()));
                } else if (!ctx.sink.add(n)) {
                    ctx.note(ID, "duplicate recipe id " + n.id());
                }
            }
        }
    }

    private static IrNode.Unknown withConditions(IrNode.Unknown u, List<String> conditions) {
        return u;
    }

    private static String argsKey(List<Object> args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            sb.append(a instanceof V.ResLoc || a instanceof String ? a.toString() : a == null ? "-" : a.getClass()
                    .getSimpleName()).append(',');
        }
        return sb.toString();
    }

    /** loader method ("pkg.Cls#method") → recipe list id, from GlobalRecipes-style createRecipes("id", new X(..., Cls::method)). */
    static Map<String, String> recipeListsByLoader(ExtractContext ctx) {
        Map<String, String> out = new HashMap<>();
        for (CtType<?> t : ctx.model.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (!inv.getExecutable().getSimpleName().equals("createRecipes") || inv.getArguments().size() != 2
                        || !(inv.getArguments().get(0) instanceof CtLiteral<?> lit) || !(lit.getValue() instanceof String id)
                        || !(inv.getArguments().get(1) instanceof CtConstructorCall<?> cc)) {
                    continue;
                }
                for (CtExpression<?> a : cc.getArguments()) {
                    if (a instanceof CtExecutableReferenceExpression<?, ?> ref && ref.getTarget() instanceof CtTypeAccess<?> ta) {
                        out.put(ta.getAccessedType().getQualifiedName() + "#" + ref.getExecutable().getSimpleName(), id);
                    }
                }
            }
        }
        return out;
    }

    static List<IrNode> convert(ExtractContext ctx, String name, VObj chain, String listId, Provenance prov) {
        List<Object> a = chain.args;
        switch (name) {
            case "IAdvancedCraftingManager#addShapedRecipe":
                return List.of(shaped(ctx, a, prov));
            case "IAdvancedCraftingManager#addShapelessRecipe":
                return List.of(shapeless(ctx, a, prov));
            case "IAdvancedCraftingManager#addSmeltingRecipe":
                return smelting(ctx, a, prov);
            case "IAdvancedCraftingManager#addStoneCutterRecipe": {
                String id = id(a, 0);
                IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, arg(a, 1));
                IrNode.Ingredient in = input(ctx, arg(a, 2));
                if (id == null || out == null || in == null) {
                    return List.of(unknown(id, "stonecutter recipe not representable", prov));
                }
                return List.of(new IrNode.Recipe(id, "stonecutting", null, null, null, List.of(in), out, null, null, null,
                        null, prov, null, null));
            }
            case "IAdvancedCraftingManager#addSmithingRecipe": {
                String id = id(a, 0);
                IrNode.Ingredient base = input(ctx, arg(a, 1));
                IrNode.Ingredient add = input(ctx, arg(a, 2));
                IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, arg(a, 3));
                if (id == null || base == null || add == null || out == null) {
                    return List.of(unknown(id, "smithing recipe not representable", prov));
                }
                return List.of(new IrNode.Recipe(id, "smithing", null, null, null, List.of(base, add), out, null, null,
                        null, null, prov, null, null));
            }
            case "IMachineRecipeList#addRecipe":
                return List.of(machine(ctx, a, listId, prov));
            case "IRefiningRecipeList#addRecipe":
                return List.of(refining(ctx, a, prov));
            case "IUUMatterRegistry#registerUUShape":
                return List.of(uu(ctx, a, prov));
            case "IPotionBrewRegistry#registerBrew":
                return List.of(brew(ctx, a, prov));
            default:
                if (!name.contains("Recipe") || name.contains("Repair")) {
                    // registry data that is not a recipe (fuel values, container definitions, names...)
                    return List.of(new IrNode.Unknown(null, "IC2C registry data without an IR concept: " + name, null, null,
                            prov, Map.of("ic2c:apiCall", Vals.F.stringNode(name))));
                }
                return List.of(unknown(a.isEmpty() ? null : id(a, 0), "IC2C API call " + name + " is not mapped to IR"
                        + (listId == null ? "" : " (list " + listId + ")"), prov));
        }
    }

    // ---- crafting ------------------------------------------------------------------------------------------

    static IrNode shaped(ExtractContext ctx, List<Object> a, Provenance prov) {
        String id = id(a, 0);
        IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, arg(a, 1));
        if (id == null || out == null) {
            return unknown(id, "shaped recipe id/result not computable (" + Vals.reason(id == null ? arg(a, 0) : arg(a, 1))
                    + ")", prov);
        }
        List<String> pattern = new ArrayList<>();
        Map<String, IrNode.Ingredient> key = new LinkedHashMap<>();
        Map<String, JsonNode> ext = new LinkedHashMap<>();
        ArrayNode mods = Vals.F.arrayNode();
        Character last = null;
        for (Object o : Vals.flatten(a.subList(2, a.size()))) {
            if (o == V.NULL) {
                continue;
            }
            if (o instanceof String s) {
                pattern.add(s);
            } else if (o instanceof Boolean b) {
                ext.put("ic2c:hidden", Vals.F.booleanNode(b));
            } else if (o instanceof Character c) {
                last = c;
            } else if (last != null) {
                IrNode.Ingredient ing = input(ctx, o);
                if (ing == null) {
                    return unknown(id, "shaped key '" + last + "' not representable: " + describe(o), prov);
                }
                key.put(String.valueOf(last), ing);
                last = null;
            } else if (o instanceof Obj mod) {
                mods.add(mod.enumName != null ? mod.type.getSimpleName() + "." + mod.enumName : mod.typeName());
            } else {
                return unknown(id, "shaped recipe argument not computable: " + describe(o), prov);
            }
        }
        if (pattern.isEmpty() || pattern.size() > 3 || pattern.stream().anyMatch(p -> p.isEmpty() || p.length() > 3)) {
            return unknown(id, "shaped recipe pattern outside 3x3", prov);
        }
        int w = pattern.get(0).length();
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            if (p.length() != w) {
                return unknown(id, "ragged shaped pattern", prov);
            }
        }
        for (String p : pattern) {
            for (char c : p.toCharArray()) {
                if (c != ' ' && !key.containsKey(String.valueOf(c))) {
                    return unknown(id, "pattern char '" + c + "' without ingredient", prov);
                }
            }
        }
        key.keySet().removeIf(k -> pattern.stream().noneMatch(p -> p.contains(k)));
        if (!mods.isEmpty()) {
            ext.put("ic2c:modifiers", mods);
        }
        return new IrNode.Recipe(id, "shaped", null, pattern, key, null, out, null, null, null, null, prov,
                mods.isEmpty() ? null : 0.9, ext.isEmpty() ? null : ext);
    }

    static IrNode shapeless(ExtractContext ctx, List<Object> a, Provenance prov) {
        String id = id(a, 0);
        IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, arg(a, 1));
        if (id == null || out == null) {
            return unknown(id, "shapeless recipe id/result not computable", prov);
        }
        List<IrNode.Ingredient> ings = new ArrayList<>();
        Map<String, JsonNode> ext = new LinkedHashMap<>();
        ArrayNode mods = Vals.F.arrayNode();
        for (Object o : Vals.flatten(a.subList(2, a.size()))) {
            if (o == V.NULL) {
                continue;
            }
            if (o instanceof Boolean b) {
                ext.put("ic2c:hidden", Vals.F.booleanNode(b));
                continue;
            }
            if (o instanceof Obj mod && isModifier(ctx, mod)) {
                mods.add(mod.enumName != null ? mod.type.getSimpleName() + "." + mod.enumName : mod.typeName());
                continue;
            }
            IrNode.Ingredient ing = input(ctx, o);
            if (ing == null) {
                return unknown(id, "shapeless ingredient not representable: " + describe(o), prov);
            }
            ings.add(ing);
        }
        if (ings.isEmpty()) {
            return unknown(id, "shapeless recipe without ingredients", prov);
        }
        if (!mods.isEmpty()) {
            ext.put("ic2c:modifiers", mods);
        }
        return new IrNode.Recipe(id, "shapeless", null, null, null, ings, out, null, null, null, null, prov,
                mods.isEmpty() ? null : 0.9, ext.isEmpty() ? null : ext);
    }

    static boolean isModifier(ExtractContext ctx, Obj o) {
        return ctx.interp.isSameOrSub(o.type, "ic2.core.platform.recipes.mods.IRecipeModifier")
                || ctx.interp.isSameOrSub(o.type, "ic2.api.recipes.misc.RecipeMods")
                || o.type.getSimpleName().equals("RecipeMods");
    }

    /** One recipe per SmeltingType: id = SmeltingType#convert(id), time = (int) (baseTime * extraTime). */
    static List<IrNode> smelting(ExtractContext ctx, List<Object> a, Provenance prov) {
        String base = id(a, 0);
        IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, arg(a, 1));
        IrNode.Ingredient in = input(ctx, arg(a, 2));
        Double xp = Vals.number(arg(a, 3));
        Double extra = Vals.number(arg(a, 4));
        List<Object> types = a.size() > 5 ? Vals.flatten(a.subList(5, a.size())) : List.of();
        if (base == null || out == null || in == null || xp == null || extra == null || types.isEmpty()) {
            return List.of(unknown(base, "smelting recipe not representable", prov));
        }
        List<IrNode> nodes = new ArrayList<>();
        for (Object t : types) {
            if (!(t instanceof Obj type) || type.enumName == null) {
                nodes.add(unknown(base, "smelting type not computable", prov));
                continue;
            }
            Object rl = ctx.interp.callIfDefined(type, "convert", List.of(parse(base)));
            Integer baseTime = Vals.integer(ctx.interp.callIfDefined(type, "getBaseTime", List.of()));
            String kind = switch (type.enumName) {
                case "FURNACE" -> "smelting";
                case "SMOKER" -> "smoking";
                case "BLASTFURNACE" -> "blasting";
                case "CAMPFIRE" -> "campfire";
                default -> null;
            };
            if (!(rl instanceof V.ResLoc id) || baseTime == null || kind == null) {
                nodes.add(unknown(base, "smelting type " + type.enumName + " not representable", prov));
                continue;
            }
            int time = (int) ((float) (int) baseTime * extra.floatValue());
            nodes.add(new IrNode.Recipe(id.toString(), kind, null, null, null, List.of(in), out, null, xp,
                    time >= 1 ? time : null, null, prov, null, null));
        }
        return nodes;
    }

    /** UU-Matter replicator shape: custom ic2:uu_matter, result + data{shape, letters, hidden, special}. */
    static IrNode uu(ExtractContext ctx, List<Object> a, Provenance prov) {
        if (!(arg(a, 0) instanceof Obj b)) {
            return unknown(null, "UU shape builder not computable: " + describe(arg(a, 0)), prov);
        }
        String id = rl(MenuFields.field(ctx, b, "id"));
        IrNode.ItemStack out = RecipeExtractor.stackOf(ctx, MenuFields.field(ctx, b, "output"));
        Object shape = MenuFields.field(ctx, b, "shape");
        Object letters = MenuFields.field(ctx, b, "letters");
        if (id == null || out == null || !(shape instanceof V.Seq rows) || !(letters instanceof V.MapV lm)) {
            return unknown(id, "UU shape not computable (id=" + (id != null) + ", output="
                    + describe(MenuFields.field(ctx, b, "output")) + ", shape=" + describe(shape) + ", letters="
                    + describe(letters) + ")", prov);
        }
        ObjectNode data = Vals.F.objectNode();
        ArrayNode sh = Vals.F.arrayNode();
        for (Object r : rows.items) {
            if (!(r instanceof String row)) {
                return unknown(id, "UU shape row not computable", prov);
            }
            sh.add(row);
        }
        data.set("shape", sh);
        ObjectNode let = Vals.F.objectNode();
        for (Map.Entry<Object, Object> e : lm.map.entrySet()) {
            Integer n = Vals.integer(e.getValue());
            if (!(e.getKey() instanceof Character c) || n == null) {
                return unknown(id, "UU letter cost not computable", prov);
            }
            let.put(String.valueOf(c), n);
        }
        data.set("uuMatterPerLetter", let);
        if (MenuFields.field(ctx, b, "hidden") instanceof Boolean h) {
            data.put("hidden", h);
        }
        if (MenuFields.field(ctx, b, "special") instanceof Boolean sp) {
            data.put("special", sp);
        }
        return new IrNode.Recipe(id, "custom", "ic2:uu_matter", null, null, null, out, null, null, null, data, prov, null,
                null);
    }

    /** Potion brewing step registerBrew(item, from, to); from == null brews from the base potion. */
    static IrNode brew(ExtractContext ctx, List<Object> a, Provenance prov) {
        String item = ctx.itemIdOf(arg(a, 0));
        boolean fromBase = arg(a, 1) == V.NULL;
        String from = fromBase ? null : ctx.idOf(arg(a, 1));
        String to = ctx.idOf(arg(a, 2));
        if (item == null || to == null || !fromBase && from == null) {
            return unknown(null, "potion brew not computable", prov);
        }
        String id = "ic2:potion_brew/" + item.replace(':', '_') + "/" + (fromBase ? "base" : from.replace(':', '_'))
                + "/" + to.replace(':', '_');
        ObjectNode data = Vals.F.objectNode();
        if (from != null) {
            data.put("from", from);
        }
        data.put("to", to);
        return new IrNode.Recipe(id, "custom", "ic2:potion_brew", null, null,
                List.of(new IrNode.Ingredient(item, null, null, null)), null, null, null, null, data, prov, null, null);
    }

    // ---- machine lists -------------------------------------------------------------------------------------

    static IrNode machine(ExtractContext ctx, List<Object> a, String listId, Provenance prov) {
        Object first = arg(a, 0);
        Object output;
        List<Object> inputs;
        String id;
        if (first instanceof Obj entry && a.size() == 1) {
            id = rl(MenuFields.field(ctx, entry, "location"));
            output = MenuFields.field(ctx, entry, "output");
            Object ins = MenuFields.field(ctx, entry, "inputs");
            inputs = ins instanceof V.Seq s ? s.items : null;
        } else {
            id = id(a, 0);
            output = arg(a, 1);
            inputs = a.size() > 2 ? Vals.flatten(a.subList(2, a.size())) : List.of();
        }
        if (listId == null) {
            return unknown(id, "machine recipe whose recipe list is not known (loader not registered via createRecipes)",
                    prov);
        }
        if (id == null || inputs == null) {
            return unknown(id, "machine recipe id/inputs not computable", prov);
        }
        List<IrNode.Ingredient> ings = new ArrayList<>();
        ObjectNode data = Vals.F.objectNode();
        ArrayNode fluidIns = Vals.F.arrayNode();
        for (Object in : inputs) {
            if (in == V.NULL) {
                continue;
            }
            JsonNode fluid = fluidInput(ctx, in);
            if (fluid != null) {
                fluidIns.add(fluid);
                continue;
            }
            if (in instanceof Obj d && d.type.getSimpleName().equals("DamagedInput")) {
                String item = ctx.itemIdOf(MenuFields.field(ctx, d, "item"));
                Object und = MenuFields.field(ctx, d, "undamaged");
                if (item == null || !(und instanceof Boolean u)) {
                    return unknown(id, "machine recipe damaged-item input not computable", prov);
                }
                ObjectNode cond = Vals.F.objectNode();
                cond.put("index", ings.size());
                cond.put("damaged", !u);
                if (!data.has("inputConditions")) {
                    data.set("inputConditions", Vals.F.arrayNode());
                }
                ((ArrayNode) data.get("inputConditions")).add(cond);
                ings.add(new IrNode.Ingredient(item, null, null, null));
                continue;
            }
            IrNode.Ingredient ing = input(ctx, in);
            if (ing == null) {
                return unknown(id, "machine recipe input not representable: " + describe(in), prov);
            }
            ings.add(ing);
        }
        if (!fluidIns.isEmpty()) {
            data.set("fluidInputs", fluidIns);
        }
        List<IrNode.ItemStack> results = new ArrayList<>();
        if (!output(ctx, output, results, data)) {
            return unknown(id, "machine recipe output not representable: " + describe(output), prov);
        }
        return new IrNode.Recipe(id, "custom", "ic2:" + listId, null, null, ings.isEmpty() ? null : ings, null,
                results.isEmpty() ? null : results, null, null, data.isEmpty() ? null : data, prov, null, null);
    }

    /** addRecipe(id, FluidStack main, FluidStack second, IInput catalyst, IFluidRecipeOutput output). */
    static IrNode refining(ExtractContext ctx, List<Object> a, Provenance prov) {
        String id = id(a, 0);
        if (id == null || a.size() < 5) {
            return unknown(id, "refining recipe not computable", prov);
        }
        ObjectNode data = Vals.F.objectNode();
        JsonNode main = fluidStack(ctx, arg(a, 1));
        JsonNode second = fluidStack(ctx, arg(a, 2));
        if (main == null && !absent(arg(a, 1)) || second == null && !absent(arg(a, 2))) {
            return unknown(id, "refining fluid input not computable", prov);
        }
        if (main != null) {
            data.set("mainFluid", main);
        }
        if (second != null) {
            data.set("secondFluid", second);
        }
        List<IrNode.Ingredient> ings = null;
        if (!absent(arg(a, 3)) && !(arg(a, 3) instanceof Obj e && e.type.getSimpleName().equals("EmptyInput"))) {
            IrNode.Ingredient cat = input(ctx, arg(a, 3));
            if (cat == null) {
                return unknown(id, "refining catalyst not representable: " + describe(arg(a, 3)), prov);
            }
            ings = List.of(cat);
        }
        List<IrNode.ItemStack> results = new ArrayList<>();
        if (!output(ctx, arg(a, 4), results, data)) {
            return unknown(id, "refining output not representable: " + describe(arg(a, 4)), prov);
        }
        return new IrNode.Recipe(id, "custom", "ic2:refining", null, null, ings, null, results.isEmpty() ? null : results,
                null, null, data, prov, null, null);
    }

    /** null, FluidStack.EMPTY, ItemStack.EMPTY. */
    static boolean absent(Object v) {
        return v == V.NULL || v instanceof V.Sym s && s.name().equals("EMPTY");
    }

    /** CompoundTag recorder → JSON of its plain puts (recipe modifiers such as time/energy factors). */
    static JsonNode nbtJson(Object v) {
        if (!(v instanceof VObj c) || !c.type.endsWith("CompoundTag")) {
            return null;
        }
        ObjectNode o = Vals.F.objectNode();
        for (V.Call call : c.calls) {
            if (!call.name().startsWith("put") || call.args().size() != 2 || !(call.args().get(0) instanceof String k)) {
                return null;
            }
            Object val = call.args().get(1);
            if (val instanceof Integer i) {
                o.put(k, i);
            } else if (val instanceof Long l) {
                o.put(k, l);
            } else if (val instanceof Number n) {
                o.put(k, n.doubleValue());
            } else if (val instanceof Boolean b) {
                o.put(k, b);
            } else if (val instanceof String str) {
                o.put(k, str);
            } else {
                return null;
            }
        }
        return o;
    }

    /** Fills results/data from an IC2C IRecipeOutput object; false if not understood. */
    static boolean output(ExtractContext ctx, Object out, List<IrNode.ItemStack> results, ObjectNode data) {
        if (!(out instanceof Obj o)) {
            return false;
        }
        String simple = o.type.getSimpleName();
        Object gens = MenuFields.field(ctx, o, "generators");
        if (gens instanceof V.Seq s) {
            for (Object g : s.items) {
                IrNode.ItemStack st = generator(ctx, g, data);
                if (st == null && !(g instanceof Obj go && fluidGenerator(ctx, go, data))) {
                    return false;
                }
                if (st != null) {
                    results.add(st);
                }
            }
        }
        Object single = MenuFields.field(ctx, o, "output");
        if (results.isEmpty() && single instanceof V.Stack) {
            IrNode.ItemStack st = RecipeExtractor.stackOf(ctx, single);
            if (st == null) {
                return false;
            }
            results.add(st);
        }
        Object fluids = MenuFields.field(ctx, o, "fluids");
        if (fluids instanceof V.Seq fs && !fs.items.isEmpty()) {
            ArrayNode arr = Vals.F.arrayNode();
            for (Object f : fs.items) {
                JsonNode fn = fluidStack(ctx, f);
                if (fn == null) {
                    return false;
                }
                arr.add(fn);
            }
            data.set("fluidOutputs", arr);
        }
        for (String num : new String[] {"xp", "chance", "minValue", "maxValue"}) {
            Double d = Vals.number(MenuFields.field(ctx, o, num));
            if (d != null && d != 0) {
                data.put(num, d);
            }
        }
        if (MenuFields.field(ctx, o, "nbt") instanceof VObj nbt && !nbt.calls.isEmpty()) {
            JsonNode j = nbtJson(nbt);
            if (j == null) {
                return false;
            }
            data.set("nbt", j);
        }
        data.put("outputType", simple);
        return !results.isEmpty() || data.has("fluidOutputs");
    }

    static IrNode.ItemStack generator(ExtractContext ctx, Object g, ObjectNode data) {
        if (!(g instanceof Obj o)) {
            return null;
        }
        Integer count = Vals.integer(MenuFields.field(ctx, o, "count"));
        Object stack = MenuFields.field(ctx, o, "stack");
        Object item = MenuFields.field(ctx, o, "item");
        String id = stack instanceof V.Stack s ? ctx.itemIdOf(s.item()) : item != null ? ctx.itemIdOf(item) : null;
        if (id == null || count == null || count < 1) {
            return null;
        }
        return new IrNode.ItemStack(id, count == 1 ? null : count, null);
    }

    static boolean fluidGenerator(ExtractContext ctx, Obj o, ObjectNode data) {
        return false;
    }

    // ---- inputs --------------------------------------------------------------------------------------------

    /** IC2C input (raw object or IInput) → ingredient; null if not representable as item/tag. */
    static IrNode.Ingredient input(ExtractContext ctx, Object v) {
        if (v instanceof Obj o && ctx.interp.isSameOrSub(o.type, "ic2.api.recipes.ingridients.inputs.IInput")) {
            String s = o.type.getSimpleName();
            Integer size = Vals.integer(MenuFields.field(ctx, o, "size"));
            Integer count = size == null || size <= 1 ? null : size;
            switch (s) {
                case "ItemInput": {
                    String item = ctx.itemIdOf(MenuFields.field(ctx, o, "item"));
                    return item == null ? null : new IrNode.Ingredient(item, null, null, count);
                }
                case "ItemTagInput": {
                    Object tag = MenuFields.field(ctx, o, "tag");
                    return tag instanceof V.TagRef t && t.registry().equals("item")
                            ? new IrNode.Ingredient(null, t.id(), null, count) : null;
                }
                case "ItemStackInput": {
                    Object st = MenuFields.field(ctx, o, "stack");
                    return RecipeExtractor.ingredientOf(ctx, st);
                }
                case "DamagedInput":
                    // matches only damaged (or only undamaged) stacks: not an item/tag ingredient
                    return null;
                case "ItemListInput", "ArrayInput": {
                    for (String f : new String[] {"items", "list", "inputs", "stacks"}) {
                        Object l = MenuFields.field(ctx, o, f);
                        if (l instanceof V.Seq seq && !seq.items.isEmpty()) {
                            List<IrNode.Ingredient> any = new ArrayList<>();
                            for (Object x : seq.items) {
                                IrNode.Ingredient i = input(ctx, x);
                                if (i == null) {
                                    return null;
                                }
                                any.add(i);
                            }
                            return any.size() == 1 ? any.get(0) : new IrNode.Ingredient(null, null, any, null);
                        }
                    }
                    return null;
                }
                default:
                    return null;
            }
        }
        if (v instanceof V.Seq s && s.kind.equals("array") && !s.items.isEmpty()) {
            List<IrNode.Ingredient> any = new ArrayList<>();
            for (Object x : s.items) {
                IrNode.Ingredient i = input(ctx, x);
                if (i == null) {
                    return null;
                }
                any.add(i);
            }
            return any.size() == 1 ? any.get(0) : new IrNode.Ingredient(null, null, any, null);
        }
        return RecipeExtractor.ingredientOf(ctx, v);
    }

    static JsonNode fluidInput(ExtractContext ctx, Object v) {
        if (v instanceof Obj o && o.type.getSimpleName().equals("FluidInput")) {
            String f = fluidId(ctx, MenuFields.field(ctx, o, "fluid"));
            Integer amount = Vals.integer(MenuFields.field(ctx, o, "fluidAmount"));
            if (f == null || amount == null) {
                return null;
            }
            ObjectNode n = Vals.F.objectNode();
            n.put("fluid", f);
            n.put("amount", amount);
            return n;
        }
        if (v instanceof VObj fs && fs.type.endsWith("FluidStack")) {
            return fluidStack(ctx, v);
        }
        return null;
    }

    static JsonNode fluidStack(ExtractContext ctx, Object v) {
        if (v instanceof VObj fs && fs.type.endsWith("FluidStack") && fs.args.size() >= 2) {
            String f = fluidId(ctx, fs.args.get(0));
            Integer amount = Vals.integer(fs.args.get(1));
            if (f == null || amount == null) {
                return null;
            }
            ObjectNode n = Vals.F.objectNode();
            n.put("fluid", f);
            n.put("amount", amount);
            return n;
        }
        return null;
    }

    static String fluidId(ExtractContext ctx, Object f) {
        if (f instanceof V.Holder h && h.registry().equals("fluid")) {
            return h.id();
        }
        String id = ctx.idOf(f);
        return id != null && "fluid".equals(ctx.registryOf(f)) ? id : null;
    }

    // ---- small helpers -------------------------------------------------------------------------------------

    static Object arg(List<Object> a, int i) {
        return i < a.size() ? a.get(i) : V.NULL;
    }

    static String id(List<Object> a, int i) {
        return rl(arg(a, i));
    }

    static String rl(Object v) {
        if (v instanceof V.ResLoc rl && Vals.isResourceId(rl.toString())) {
            return rl.toString();
        }
        if (v instanceof String s && Vals.isResourceId(s)) {
            return s;
        }
        return null;
    }

    static V.ResLoc parse(String id) {
        int c = id.indexOf(':');
        return new V.ResLoc(id.substring(0, c), id.substring(c + 1));
    }

    static String describe(Object v) {
        if (v instanceof Obj o) {
            if (o.enumName == null && !o.fields.isEmpty() && o.fields.size() <= 4) {
                StringBuilder sb = new StringBuilder(o.type.getSimpleName()).append('{');
                o.fields.forEach((k, x) -> sb.append(k).append('=').append(Interpreter.describe(x)).append(';'));
                return sb.append('}').toString();
            }
            return o.type.getSimpleName() + (o.enumName == null ? "" : "." + o.enumName);
        }
        if (v instanceof V.RegRef r) {
            return r.registry() + " " + r.id();
        }
        String s = Interpreter.describe(v);
        return s.toLowerCase(Locale.ROOT).contains("fluid") ? "fluid " + s : s;
    }

    static IrNode.Unknown unknown(String id, String reason, Provenance prov) {
        return new IrNode.Unknown(id != null && Vals.isResourceId(id) ? id : null, reason, null, "recipe", prov, null);
    }
}
