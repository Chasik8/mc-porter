package dev.porter.generate.forge1710;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.GenOutput;
import dev.porter.generate.Names;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;
import tools.jackson.databind.JsonNode;

/**
 * NotEnoughItems integration from the IR (1.7.10 players live in NEI). NEI already shows vanilla crafting and smelting;
 * this adds what only the IR knows:
 *
 * <ul>
 *   <li>one NEI page type per {@code customType} of the custom recipes: item inputs (items, OreDictionary, any-of),
 *       fluid inputs/outputs (RFC 003 {@code fluidIngredients}/{@code fluidResults}), outputs with chances, the
 *       machine ({@code machines}), time ({@code duration}) and notes ({@code notes}; without notes the scalar
 *       {@code data} entries are shown);</li>
 *   <li>"how to build" pages for every {@code multiblock} (bill of materials + one page per layer);</li>
 *   <li>items and blocks marked {@code hidden} are hidden from the item list.</li>
 * </ul>
 *
 * <p>Output (package {@code <pkg>.nei}): {@code NeiModel} (data model, no NEI types), {@code <Mod>NeiData} (the IR as
 * data), {@code NeiRecipeHandler}/{@code NeiStructureHandler} (fixed NEI code) with one subclass per page type, and
 * {@code NEI<Mod>Config} (found by NEI's class scan: name starts with NEI and ends with Config). Nothing else refers to
 * the NEI classes, so the mod runs without NEI (dedicated server). Compile against NotEnoughItems 1.0.5.x
 * (codechicken.nei: TemplateRecipeHandler, IConfigureNEI), CodeChickenCore and CodeChickenLib as compileOnly.
 */
final class NeiGen {
    static final String TEMPLATES = "/porter/generate/forge1710/nei/";
    static final int RECIPES_PER_METHOD = 60;

    private final Plan plan;
    private final GenContext ctx;
    private final String pkg;
    private final ClassName model;
    private final ClassName data;
    private final ClassName config;
    private final ClassName recipeHandler;
    private final ClassName structureHandler;
    /** Manifest section. */
    final Map<String, Object> manifest = new LinkedHashMap<>();

    NeiGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
        this.pkg = plan.pkg + ".nei";
        model = ClassName.get(pkg, "NeiModel");
        data = ClassName.get(pkg, plan.modPascal + "NeiData");
        config = ClassName.get(pkg, "NEI" + plan.modPascal + "Config");
        recipeHandler = ClassName.get(pkg, "NeiRecipeHandler");
        structureHandler = ClassName.get(pkg, "NeiStructureHandler");
    }

    void run() {
        Map<String, List<IrNode.Recipe>> categories = new TreeMap<>();
        for (IrNode.Recipe r : ctx.ir.all(IrNode.Recipe.class)) {
            if ("custom".equals(r.recipeType())) {
                categories.computeIfAbsent(r.customType(), k -> new ArrayList<>()).add(r);
            } else if (r.fluidIngredients() != null || r.fluidResults() != null || r.machines() != null) {
                ctx.info("nei-vanilla-type", r.id(), r.recipeType() + " recipe: NEI's own handlers show it; RFC 003 viewer"
                        + " fields of non-custom recipes are not displayed");
            }
        }
        List<IrNode.Multiblock> structures = new ArrayList<>(ctx.ir.all(IrNode.Multiblock.class));
        List<String[]> hidden = hidden();
        if (categories.isEmpty() && structures.isEmpty() && hidden.isEmpty()) {
            // nothing NEI does not already show (vanilla crafting/smelting)
            ctx.info("nei-nothing", ctx.options.modId(), "no custom recipes, multiblocks or hidden objects: no NEI integration");
            return;
        }
        template("NeiModel");
        TypeSpec.Builder d = TypeSpec.classBuilder(data).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Recipe-viewer data of the IR (custom recipe types, multiblocks, hidden objects). No NEI types.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        TypeName catList = ParameterizedTypeName.get(ClassName.get(List.class), model.nestedClass("Category"));
        TypeName structList = ParameterizedTypeName.get(ClassName.get(List.class), model.nestedClass("Structure"));
        TypeName ingList = ParameterizedTypeName.get(ClassName.get(List.class), model.nestedClass("Ing"));
        d.addField(FieldSpec.builder(catList, "categories", Modifier.PRIVATE, Modifier.STATIC).build());
        d.addField(FieldSpec.builder(structList, "structures", Modifier.PRIVATE, Modifier.STATIC).build());

        // ---- recipe categories
        MethodSpec.Builder cats = MethodSpec.methodBuilder("categories").addModifiers(Modifier.PUBLIC, Modifier.STATIC,
                Modifier.SYNCHRONIZED).returns(catList).addJavadoc("Every custom recipe type of the IR, sorted by id.\n")
                .beginControlFlow("if (categories == null)")
                .addStatement("$T list = new $T<$T>()", catList, ArrayList.class, model.nestedClass("Category"));
        Map<String, Object> catManifest = new TreeMap<>();
        List<TypeSpec> handlers = new ArrayList<>();
        for (Map.Entry<String, List<IrNode.Recipe>> e : categories.entrySet()) {
            String type = e.getKey();
            String m = "category" + Names.pascal(Names.namespace(type) + "_" + Names.path(type).replace('/', '_'));
            int shown = category(d, m, type, e.getValue());
            if (shown == 0) {
                ctx.warn("nei-category-empty", type, "no recipe of this type exists on 1.7.10; no NEI page type");
                continue;
            }
            cats.addStatement("list.add($L())", m);
            ClassName h = ClassName.get(pkg, Names.pascal(Names.namespace(type) + "_" + Names.path(type).replace('/', '_'))
                    + "NeiHandler");
            handlers.add(TypeSpec.classBuilder(h).addModifiers(Modifier.PUBLIC).superclass(recipeHandler)
                    .addJavadoc("NEI pages of recipe type $L.\n", type)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                            .addStatement("super($T.category($S))", data, type).build())
                    .build());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("handler", h.canonicalName());
            c.put("recipes", e.getValue().size());
            c.put("emitted", shown);
            catManifest.put(type, c);
            ctx.info("nei-category", type, shown + "/" + e.getValue().size() + " recipes on NEI pages (" + h.simpleName() + ")");
        }
        cats.addStatement("categories = $T.unmodifiableList(list)", java.util.Collections.class).endControlFlow()
                .addStatement("return categories");
        d.addMethod(cats.build());
        d.addMethod(MethodSpec.methodBuilder("category").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(model.nestedClass("Category")).addParameter(String.class, "id")
                .addJavadoc("The recipe type with this IR customType id.\n")
                .beginControlFlow("for ($T c : categories())", model.nestedClass("Category"))
                .beginControlFlow("if (c.id.equals(id))").addStatement("return c").endControlFlow().endControlFlow()
                .addStatement("throw new $T($S + id)", IllegalArgumentException.class, "no recipe type ").build());

        // ---- structures
        MethodSpec.Builder st = MethodSpec.methodBuilder("structures").addModifiers(Modifier.PUBLIC, Modifier.STATIC,
                Modifier.SYNCHRONIZED).returns(structList).addJavadoc("Every multiblock of the IR.\n")
                .beginControlFlow("if (structures == null)")
                .addStatement("$T list = new $T<$T>()", structList, ArrayList.class, model.nestedClass("Structure"));
        for (IrNode.Multiblock mb : structures) {
            st.addStatement("list.add($L)", structure(mb));
        }
        st.addStatement("structures = $T.unmodifiableList(list)", java.util.Collections.class).endControlFlow()
                .addStatement("return structures");
        d.addMethod(st.build());

        // ---- hidden
        MethodSpec.Builder hid = MethodSpec.methodBuilder("hidden").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ingList).addJavadoc("Items and blocks the IR marks hidden (every metadata).\n")
                .addStatement("$T list = new $T<$T>()", ingList, ArrayList.class, model.nestedClass("Ing"));
        for (String[] h : hidden) {
            hid.addStatement("list.add($T.item($S, $S, $L, 1))", model, h[0], h[1], 32767);
        }
        d.addMethod(hid.addStatement("return list").build());
        d.addMethod(MethodSpec.methodBuilder("report").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(String.class)
                .addJavadoc("What NEI shows with the current resolver (works without NEI, e.g. on a server after postInit).\n")
                .addStatement("return $T.report(categories(), structures())", model).build());
        ctx.out.addJava(pkg, d.build());

        // ---- NEI side
        if (!handlers.isEmpty()) {
            template("NeiRecipeHandler");
            handlers.forEach(h -> ctx.out.addJava(pkg, h));
        }
        ClassName structHandler = ClassName.get(pkg, plan.modPascal + "StructureNeiHandler");
        if (!structures.isEmpty()) {
            if (handlers.isEmpty()) {
                template("NeiRecipeHandler");
            }
            template("NeiStructureHandler");
            ctx.out.addJava(pkg, TypeSpec.classBuilder(structHandler).addModifiers(Modifier.PUBLIC).superclass(structureHandler)
                    .addJavadoc("NEI pages of the multiblocks of $L.\n", ctx.options.modId())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                            .addStatement("super($T.structures())", data).build())
                    .addMethod(MethodSpec.methodBuilder("handlerId").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .returns(String.class).addStatement("return $S", "porter." + plan.pkg + ".structures").build())
                    .build());
        }
        configClass(handlers, structures.isEmpty() ? null : structHandler);

        manifest.put("config", config.canonicalName());
        manifest.put("data", data.canonicalName());
        manifest.put("categories", catManifest);
        manifest.put("structures", structures.stream().map(IrNode::id).toList());
        manifest.put("hidden", hidden.stream().map(h -> h[0] + ":" + h[1]).toList());
        manifest.put("compileOnly", "NotEnoughItems 1.0.5.x API (codechicken.nei: TemplateRecipeHandler, IConfigureNEI)"
                + " + CodeChickenCore + CodeChickenLib; not needed at runtime");
    }

    private void configClass(List<TypeSpec> handlers, ClassName structHandler) {
        ClassName api = ClassName.get("codechicken.nei.api", "API");
        MethodSpec.Builder load = MethodSpec.methodBuilder("loadConfig").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        int n = 0;
        for (TypeSpec h : handlers) {
            ClassName c = ClassName.get(pkg, h.name);
            String v = "handler" + n++;
            load.addStatement("$T $L = new $T()", c, v, c)
                    .addStatement("$T.registerRecipeHandler($L)", api, v)
                    .addStatement("$T.registerUsageHandler($L)", api, v);
        }
        if (structHandler != null) {
            load.addStatement("$T structures = new $T()", structHandler, structHandler)
                    .addStatement("$T.registerRecipeHandler(structures)", api)
                    .addStatement("$T.registerUsageHandler(structures)", api);
        }
        load.beginControlFlow("for ($T.Ing ing : $T.hidden())", model, data)
                .beginControlFlow("for ($T s : ing.stacks())", T.ITEM_STACK)
                .addStatement("$T.hideItem(s)", api)
                .endControlFlow().endControlFlow();
        TypeSpec t = TypeSpec.classBuilder(config).addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ClassName.get("codechicken.nei.api", "IConfigureNEI"))
                .addJavadoc("NotEnoughItems plugin (NEI finds it by its name, client side only; absent NEI = never loaded).\n")
                .addMethod(load.build())
                .addMethod(MethodSpec.methodBuilder("getName").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(String.class).addStatement("return $S", ctx.options.modName() + " (porter)").build())
                .addMethod(MethodSpec.methodBuilder("getVersion").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(String.class).addStatement("return $S", ctx.options.modVersion()).build())
                .build();
        ctx.out.addJava(pkg, t);
    }

    // ---- recipes ------------------------------------------------------------------------------------------------

    /** Adds the category method(s); returns how many recipes were emitted. */
    private int category(TypeSpec.Builder d, String method, String type, List<IrNode.Recipe> recipes) {
        ClassName cat = model.nestedClass("Category");
        String key = ctx.options.modId() + ".nei." + Names.namespace(type) + "." + Names.path(type).replace('/', '.');
        MethodSpec.Builder m = MethodSpec.methodBuilder(method).addModifiers(Modifier.PRIVATE, Modifier.STATIC).returns(cat)
                .addJavadoc("$L ($L recipes in the IR).\n", type, recipes.size())
                .addStatement("$T c = new $T($S, $S, $S)", cat, cat, type, key, Names.humanize(Names.path(type)));
        List<CodeBlock> lines = new ArrayList<>();
        for (IrNode.Recipe r : recipes) {
            CodeBlock b = recipe(r);
            if (b != null) {
                lines.add(b);
            }
        }
        if (lines.isEmpty()) {
            return 0;
        }
        int part = 0;
        for (int i = 0; i < lines.size(); i += RECIPES_PER_METHOD) {
            String pm = method + "Part" + part++;
            MethodSpec.Builder p = MethodSpec.methodBuilder(pm).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(cat, "c");
            for (CodeBlock b : lines.subList(i, Math.min(lines.size(), i + RECIPES_PER_METHOD))) {
                p.addStatement("$L", b);
            }
            d.addMethod(p.build());
            m.addStatement("$L(c)", pm);
        }
        d.addMethod(m.addStatement("return c").build());
        return lines.size();
    }

    /** {@code c.recipe(id).in(...).fluidIn(...).out(...)...}, or null when an input/output has no 1.7.10 counterpart. */
    private CodeBlock recipe(IrNode.Recipe r) {
        CodeBlock.Builder b = CodeBlock.builder().add("c.recipe($S)", r.id());
        for (IrNode.Ingredient in : r.ingredients() == null ? List.<IrNode.Ingredient>of() : r.ingredients()) {
            CodeBlock ing = ingredient(r, in);
            if (ing == null) {
                return null;
            }
            b.add("\n.in($L)", ing);
        }
        for (IrNode.FluidStack f : r.fluidIngredients() == null ? List.<IrNode.FluidStack>of() : r.fluidIngredients()) {
            b.add("\n.fluidIn($S, $S, $L)", f.fluid(), fluidName(f.fluid()), f.amount());
        }
        List<IrNode.ItemStack> outs = new ArrayList<>();
        if (r.result() != null) {
            outs.add(r.result());
        }
        if (r.results() != null) {
            outs.addAll(r.results());
        }
        for (IrNode.ItemStack s : outs) {
            CodeBlock ref = item(r, s.item(), s.count() == null ? 1 : s.count());
            if (ref == null) {
                return null;
            }
            b.add("\n.out($L, $L)", ref, s.chance() == null ? "1.0" : s.chance().toString());
        }
        for (IrNode.FluidStack f : r.fluidResults() == null ? List.<IrNode.FluidStack>of() : r.fluidResults()) {
            b.add("\n.fluidOut($S, $S, $L, $L)", f.fluid(), fluidName(f.fluid()), f.amount(),
                    f.chance() == null ? "1.0" : f.chance().toString());
        }
        for (String machine : r.machines() == null ? List.<String>of() : r.machines()) {
            CodeBlock ref = item(r, machine, 1);
            if (ref != null) {
                b.add("\n.machine($L)", ref);
            } else {
                ctx.warn("nei-machine-unmapped", r.id(), "machine " + machine + " has no 1.7.10 counterpart; not shown");
            }
        }
        if (r.duration() != null) {
            b.add("\n.time($L)", r.duration());
        }
        if (r.notes() != null) {
            for (IrNode.Note n : r.notes()) {
                b.add("\n.note($S, $S)", n.key(), n.text());
            }
        } else if (r.data() != null && r.data().isObject()) {
            List<String> shown = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : r.data().properties()) {
                JsonNode v = e.getValue();
                if (v.isValueNode() && !v.isNull()) {
                    b.add("\n.note(null, $S)", Names.humanize(e.getKey()) + ": " + (v.isNumber() ? number(v) : v.asString()));
                    shown.add(e.getKey());
                }
            }
            if (!shown.isEmpty()) {
                ctx.info("nei-data-notes", r.id(), "no notes in IR: data " + shown + " shown as text");
            }
        }
        return b.build();
    }

    private static String number(JsonNode v) {
        double d = v.asDouble();
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private CodeBlock ingredient(IrNode.Recipe r, IrNode.Ingredient in) {
        int count = in.count() == null ? 1 : in.count();
        if (in.item() != null) {
            return item(r, in.item(), count);
        }
        if (in.tag() != null) {
            return CodeBlock.of("$T.ore($S, $L)", model, plan.oreName(in.tag(), r.id()), count);
        }
        List<CodeBlock> alts = new ArrayList<>();
        for (IrNode.Ingredient a : in.anyOf()) {
            CodeBlock c = a.item() != null ? item(r, a.item(), 1)
                    : a.tag() != null ? CodeBlock.of("$T.ore($S, 1)", model, plan.oreName(a.tag(), r.id())) : null;
            if (c != null) {
                alts.add(c);
            }
        }
        if (alts.isEmpty()) {
            ctx.warn("nei-recipe-unmapped", r.id(), "no alternative of an anyOf ingredient exists on 1.7.10; recipe not shown");
            return null;
        }
        return CodeBlock.of("$T.any($L, $L)", model, count, CodeBlock.join(alts, ", "));
    }

    private CodeBlock item(IrNode.Recipe r, String id, int count) {
        String[] t = plan.target(id, false);
        if (t == null) {
            ctx.warn("nei-recipe-unmapped", r.id(), id + " has no 1.7.10 counterpart; recipe not shown in NEI");
            return null;
        }
        return CodeBlock.of("$T.item($S, $S, $L, $L)", model, t[0], t[1], Plan.meta(t), count);
    }

    /** FluidRegistry name of an IR fluid id on 1.7.10. */
    String fluidName(String id) {
        String mapped = ctx.options.idMap().get(id);
        if (mapped != null) {
            return mapped.substring(mapped.indexOf(':') + 1);
        }
        Optional<IrNode.Fluid> own = ctx.ir.get(IrNode.Fluid.class, id);
        if (own.isPresent() && ctx.isOwn(id)) {
            return new FluidGen(plan).fluidName(own.get());
        }
        return Names.path(id).toLowerCase(java.util.Locale.ROOT);
    }

    // ---- structures ---------------------------------------------------------------------------------------------

    private CodeBlock structure(IrNode.Multiblock m) {
        ClassName s = model.nestedClass("Structure");
        CodeBlock.Builder layers = CodeBlock.builder().add("new String[][] {");
        for (int y = 0; y < m.layers().size(); y++) {
            List<CodeBlock> rows = m.layers().get(y).stream().map(row -> CodeBlock.of("$S", row)).toList();
            layers.add(y == 0 ? "" : ", ").add("{$L}", CodeBlock.join(rows, ", "));
        }
        layers.add("}");
        String ctrlId = m.controller() != null ? m.controller().block() : null;
        CodeBlock ctrl = ctrlId == null ? CodeBlock.of("null") : blockRef(m.id(), ctrlId);
        String key = ctx.options.modId() + ".nei.structure." + Names.namespace(m.id()) + "." + Names.path(m.id()).replace('/', '.');
        CodeBlock.Builder b = CodeBlock.builder().add("new $T($S, $S, $S, $L, $L, $L, $L, $L)", s, m.id(), key,
                Names.humanize(Names.path(m.id())), ctrl, m.size().get(0), m.size().get(1), m.size().get(2), layers.build());
        for (Map.Entry<String, IrNode.BlockRef> e : m.palette().entrySet()) {
            IrNode.BlockRef ref = e.getValue();
            if ("minecraft:air".equals(ref.block()) || "minecraft:cave_air".equals(ref.block())) {
                continue; // must stay empty: not a block to place, not listed
            }
            CodeBlock block = Boolean.TRUE.equals(ref.any()) ? CodeBlock.of("null") : blockRef(m.id(), ref.block());
            if (ref.state() != null && !ref.state().isEmpty()) {
                ctx.info("nei-structure-state", m.id(), "palette '" + e.getKey() + "' state " + ref.state()
                        + " not shown (block only)");
            }
            b.add("\n.block($L, $L)", charLiteral(e.getKey().charAt(0)), block);
        }
        return b.build();
    }

    private CodeBlock blockRef(String subject, String blockId) {
        String[] t = plan.target(blockId, true);
        if (t == null) {
            ctx.warn("nei-structure-unmapped", subject, blockId + " has no 1.7.10 counterpart; shown as missing");
            return CodeBlock.of("$T.item($S, $S, 0, 1)", model, "minecraft", "air");
        }
        return CodeBlock.of("$T.item($S, $S, $L, 1)", model, t[0], t[1], Plan.meta(t));
    }

    private static String charLiteral(char c) {
        return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
    }

    // ---- hidden -------------------------------------------------------------------------------------------------

    private List<String[]> hidden() {
        List<String[]> out = new ArrayList<>();
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (Boolean.TRUE.equals(i.hidden())) {
                String[] t = plan.target(i.id(), false);
                if (t != null) {
                    out.add(t);
                    ctx.info("nei-hidden", i.id(), "hidden in NEI");
                }
            }
        }
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (Boolean.TRUE.equals(b.hidden())) {
                String[] t = plan.target(b.id(), true);
                if (t != null && out.stream().noneMatch(o -> o[0].equals(t[0]) && o[1].equals(t[1]))) {
                    out.add(t);
                    ctx.info("nei-hidden", b.id(), "hidden in NEI");
                }
            }
        }
        return out;
    }

    // ---- templates ----------------------------------------------------------------------------------------------

    /** Fixed source from resources, package substituted. */
    private void template(String name) {
        String path = GenOutput.JAVA_ROOT + pkg.replace('.', '/') + "/" + name + ".java";
        if (ctx.out.files().containsKey(path)) {
            return;
        }
        try (InputStream in = NeiGen.class.getResourceAsStream(TEMPLATES + name + ".java.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${package}", pkg);
            ctx.out.put(path, "// Generated by porter generate (NEI integration template). Do not edit: regenerate instead.\n" + text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
