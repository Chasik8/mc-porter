package dev.porter.generate.forge1710;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * IR {@code recipe} and {@code tag} → GameRegistry / OreDictionary calls. Ingredients: items via the lookup (unresolved
 * → recipe skipped at runtime with a log line), tags via OreDictionary names, {@code anyOf} via a synthetic ore name.
 */
final class RecipeGen {
    private final Plan plan;
    private final GenContext ctx;
    private final MethodSpec.Builder oreInit = MethodSpec.methodBuilder("registerOreDictionary")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Tags → OreDictionary (init, before recipes).\n");
    private final MethodSpec.Builder register = MethodSpec.methodBuilder("register").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Crafting and item-input smelting recipes (init).\n");
    private final MethodSpec.Builder post = MethodSpec.methodBuilder("postInit").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Ore copies and tag-input smelting, after every mod registered its ores (postInit).\n");
    private final List<MethodSpec> recipeMethods = new ArrayList<>();
    private final List<CodeBlock> custom = new ArrayList<>();
    /** Item ids referenced by custom recipes → 1.7.10 target (same resolution as crafting ingredients, --id-map first). */
    private final Map<String, String[]> customIds = new java.util.TreeMap<>();
    private int synthetic;
    int generated;

    RecipeGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        tags();
        for (IrNode.Recipe r : ctx.ir.all(IrNode.Recipe.class)) {
            recipe(r);
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.recipes).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Recipes and OreDictionary entries generated from IR.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(ArrayTypeName.of(ArrayTypeName.of(String.class)), "CUSTOM_RECIPES",
                                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("Custom recipe types as data {id, type, json}; consumed by the machine logic (hook).\n")
                        .initializer(custom.isEmpty() ? CodeBlock.of("{}") : CodeBlock.builder().add("{\n$>")
                                .add(CodeBlock.join(custom, ",\n")).add("$<\n}").build())
                        .build())
                .addField(FieldSpec.builder(ArrayTypeName.of(ArrayTypeName.of(String.class)), "CUSTOM_IDS",
                                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("Item ids used by CUSTOM_RECIPES as {irId, modId, name, meta} on 1.7.10 (--id-map applied);"
                                + " ids without a 1.7.10 counterpart are absent.\n")
                        .initializer(customIds.isEmpty() ? CodeBlock.of("{}") : CodeBlock.builder().add("{\n$>")
                                .add(CodeBlock.join(customIds.entrySet().stream().map(e -> CodeBlock.of("{$S, $S, $S, $S}",
                                        e.getKey(), e.getValue()[0], e.getValue()[1], String.valueOf(Plan.meta(e.getValue())))).toList(), ",\n"))
                                .add("$<\n}").build())
                        .build())
                .addMethod(oreInit.build()).addMethod(register.build()).addMethod(post.build())
                .addMethods(recipeMethods);
        ctx.out.addJava(plan.recipes.packageName(), t.build());
    }

    // ---- tags ------------------------------------------------------------------------------------

    private void tags() {
        for (IrNode.Tag tag : ctx.ir.all(IrNode.Tag.class)) {
            if (!"item".equals(tag.registry()) && !"block".equals(tag.registry())) {
                ctx.skip("tag-registry", tag.id(), tag.registry() + " tags have no 1.7.10 OreDictionary counterpart");
                continue;
            }
            List<String> names = tag.oreDict() != null && !tag.oreDict().isEmpty() ? tag.oreDict()
                    : List.of(plan.oreName(tag.id(), "tag " + tag.id()));
            Set<String> items = new LinkedHashSet<>();
            Set<String> tagRefs = new LinkedHashSet<>();
            flatten(tag, items, tagRefs, new LinkedHashSet<>());
            for (String name : names) {
                for (String item : items) {
                    String[] t = plan.target(item, false);
                    if (t == null) {
                        ctx.warn("vanilla-id-unmapped", tag.id(), item + " is not a 1.7.10 item; not added to " + name);
                        continue;
                    }
                    oreInit.addStatement("$T.addOre($S, $S, $S)", plan.lookup, name, t[0], t[1]);
                }
                for (String ref : tagRefs) {
                    post.addStatement("$T.copyOres($S, $S)", plan.lookup, plan.oreName(ref, "tag " + tag.id()), name);
                }
            }
        }
    }

    private void flatten(IrNode.Tag tag, Set<String> items, Set<String> tagRefs, Set<String> seen) {
        if (!seen.add(tag.id())) {
            return;
        }
        for (IrNode.TagValue v : tag.values()) {
            if (v.id().startsWith("#")) {
                String ref = v.id().substring(1);
                var inner = ctx.ir.get(IrNode.Tag.class, ref);
                if (inner.isPresent() && (inner.get().oreDict() == null || inner.get().oreDict().isEmpty())) {
                    flatten(inner.get(), items, tagRefs, seen);
                } else {
                    tagRefs.add(ref);
                }
            } else {
                items.add(v.id());
            }
        }
    }

    // ---- recipes ---------------------------------------------------------------------------------

    private void recipe(IrNode.Recipe r) {
        switch (r.recipeType()) {
            case "shaped" -> shaped(r);
            case "shapeless" -> shapeless(r);
            case "smelting" -> smelting(r);
            case "custom" -> custom(r);
            case "stonecutting" -> {
                // 1.7.10 has no stonecutter: one input -> result (with its count) as a shapeless crafting recipe
                ctx.info("recipe-stonecutting-crafted", r.id(), "stonecutting recipe emitted as shapeless crafting (1 input)");
                shapeless(r);
            }
            default -> ctx.skip("recipe-type-unsupported", r.id(), r.recipeType() + " recipes have no 1.7.10 counterpart");
        }
    }

    private String methodName(IrNode.Recipe r) {
        return "recipe" + Names.pascal(Names.namespace(r.id()) + "_" + Names.path(r.id()));
    }

    private CodeBlock result(IrNode.Recipe r, IrNode.ItemStack s) {
        if (s.nbt() != null && !s.nbt().isNull()) {
            ctx.warn("recipe-result-nbt", r.id(), "result NBT dropped");
        }
        String[] t = plan.target(s.item(), false);
        if (t == null) {
            ctx.warn("vanilla-id-unmapped", r.id(), "result " + s.item() + " is not a 1.7.10 item; recipe skipped at runtime");
            return CodeBlock.of("null");
        }
        return CodeBlock.of("$T.stack($S, $S, $L, $L)", plan.lookup, t[0], t[1], s.count() == null ? 1 : s.count(), Plan.meta(t));
    }

    /** Ingredient object for ore recipes: ItemStack, ore name String, or null when unresolved. */
    private CodeBlock ingredient(IrNode.Recipe r, IrNode.Ingredient in) {
        if (in.count() != null && in.count() > 1) {
            ctx.warn("recipe-ingredient-count", r.id(), "ingredient count " + in.count() + " not supported by 1.7.10 crafting; 1 used");
        }
        if (in.item() != null) {
            String[] t = plan.target(in.item(), false);
            if (t == null) {
                ctx.warn("vanilla-id-unmapped", r.id(), "ingredient " + in.item() + " is not a 1.7.10 item; recipe skipped at runtime");
                return CodeBlock.of("null");
            }
            return CodeBlock.of("$T.stack($S, $S, 1, $L)", plan.lookup, t[0], t[1], Plan.meta(t));
        }
        if (in.tag() != null) {
            return CodeBlock.of("$S", plan.oreName(in.tag(), r.id()));
        }
        String name = ctx.options.modId() + ":porter/anyOf/" + Names.path(r.id()) + "/" + synthetic++;
        for (IrNode.Ingredient alt : in.anyOf()) {
            if (alt.item() != null) {
                String[] t = plan.target(alt.item(), false);
                if (t == null) {
                    ctx.warn("vanilla-id-unmapped", r.id(), "alternative " + alt.item() + " is not a 1.7.10 item; dropped");
                } else {
                    oreInit.addStatement("$T.addOre($S, $S, $S)", plan.lookup, name, t[0], t[1]);
                }
            } else if (alt.tag() != null) {
                post.addStatement("$T.copyOres($S, $S)", plan.lookup, plan.oreName(alt.tag(), r.id()), name);
            } else {
                ctx.warn("recipe-nested-anyof", r.id(), "nested anyOf flattened away (dropped)");
            }
        }
        ctx.info("recipe-anyof", r.id(), "anyOf ingredient → synthetic OreDictionary name " + name);
        return CodeBlock.of("$S", name);
    }

    private void shaped(IrNode.Recipe r) {
        MethodSpec.Builder m = MethodSpec.methodBuilder(methodName(r)).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("$L (shaped).\n", r.id())
                .addStatement("$T out = $L", T.ITEM_STACK, result(r, r.result()));
        List<CodeBlock> args = new ArrayList<>();
        for (String row : r.pattern()) {
            args.add(CodeBlock.of("$S", row));
        }
        List<String> parts = new ArrayList<>();
        parts.add("out");
        int i = 0;
        for (Map.Entry<String, IrNode.Ingredient> e : r.key().entrySet()) {
            String var = "in" + i++;
            m.addStatement("$T $L = $L", Object.class, var, ingredient(r, e.getValue()));
            args.add(CodeBlock.of("$L, $L", charLiteral(e.getKey().charAt(0)), var));
            parts.add(var);
        }
        m.beginControlFlow("if ($T.missing($S, $L))", plan.lookup, r.id(), String.join(", ", parts)).addStatement("return").endControlFlow()
                .addStatement("$T.addRecipe(new $T(out, $L))", T.GAME_REGISTRY, T.SHAPED_ORE, CodeBlock.join(args, ", "));
        add(m);
    }

    private static String charLiteral(char c) {
        return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
    }

    private void shapeless(IrNode.Recipe r) {
        MethodSpec.Builder m = MethodSpec.methodBuilder(methodName(r)).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("$L (shapeless).\n", r.id())
                .addStatement("$T out = $L", T.ITEM_STACK, result(r, r.result()));
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (IrNode.Ingredient in : r.ingredients()) {
            String var = "in" + i++;
            m.addStatement("$T $L = $L", Object.class, var, ingredient(r, in));
            parts.add(var);
        }
        List<String> all = new ArrayList<>(parts);
        all.add(0, "out");
        m.beginControlFlow("if ($T.missing($S, $L))", plan.lookup, r.id(), String.join(", ", all)).addStatement("return").endControlFlow()
                .addStatement("$T.addRecipe(new $T(out, $L))", T.GAME_REGISTRY, T.SHAPELESS_ORE, String.join(", ", parts));
        add(m);
    }

    private void smelting(IrNode.Recipe r) {
        if (r.cookingTime() != null && r.cookingTime() != 200) {
            ctx.info("smelting-time", r.id(), "cookingTime " + r.cookingTime() + " ignored (1.7.10 furnace: 200 ticks)");
        }
        float xp = r.experience() == null ? 0f : r.experience().floatValue();
        IrNode.Ingredient in = r.ingredients().get(0);
        CodeBlock out = result(r, r.result());
        List<IrNode.Ingredient> alts = in.anyOf() != null ? in.anyOf() : List.of(in);
        MethodSpec.Builder m = MethodSpec.methodBuilder(methodName(r)).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("$L (smelting).\n", r.id())
                .addStatement("$T out = $L", T.ITEM_STACK, out)
                .beginControlFlow("if ($T.missing($S, out))", plan.lookup, r.id()).addStatement("return").endControlFlow();
        boolean postNeeded = false;
        for (IrNode.Ingredient a : alts) {
            if (a.item() != null) {
                String[] t = plan.target(a.item(), false);
                if (t == null) {
                    ctx.warn("vanilla-id-unmapped", r.id(), "input " + a.item() + " is not a 1.7.10 item; dropped");
                    continue;
                }
                m.addStatement("$T.smelt($T.stack($S, $S, 1, $L), out, $LF)", plan.lookup, plan.lookup, t[0], t[1], Plan.meta(t), BlockGen.f(xp));
            } else if (a.tag() != null) {
                m.addStatement("$T.smeltOres($S, out, $LF)", plan.lookup, plan.oreName(a.tag(), r.id()), BlockGen.f(xp));
                postNeeded = true;
            }
        }
        if (postNeeded) {
            recipeMethods.add(m.build());
            post.addStatement("$L()", methodName(r));
            generated++;
        } else {
            add(m);
        }
    }

    private void custom(IrNode.Recipe r) {
        var node = Json.json().valueToTree(r);
        String json = Json.json().writeValueAsString(node).replace("\r\n", "\n").replaceAll("\n\\s*", " ");
        custom.add(CodeBlock.of("{$S, $S, $S}", r.id(), r.customType(), json));
        List<String> ids = new ArrayList<>();
        for (IrNode.Ingredient in : r.ingredients() == null ? List.<IrNode.Ingredient>of() : r.ingredients()) {
            if (in.item() != null) {
                ids.add(in.item());
            }
        }
        if (r.result() != null) {
            ids.add(r.result().item());
        }
        for (IrNode.ItemStack s : r.results() == null ? List.<IrNode.ItemStack>of() : r.results()) {
            ids.add(s.item());
        }
        for (String id : ids) {
            String[] t = id == null || customIds.containsKey(id) ? null : plan.target(id, false);
            if (t != null) {
                customIds.put(id, t);
            }
        }
        ctx.info("recipe-custom", r.id(), "custom type " + r.customType() + " kept as data in CUSTOM_RECIPES (machine logic hook)");
    }

    private void add(MethodSpec.Builder m) {
        MethodSpec spec = m.build();
        recipeMethods.add(spec);
        register.addStatement("$N()", spec);
        generated++;
    }
}
