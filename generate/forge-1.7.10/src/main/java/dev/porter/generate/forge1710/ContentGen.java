package dev.porter.generate.forge1710;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.GenOutput;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;

/** Registration entry point ({@code <Mod>Content}), lookup helper, client events, {@code @Mod} class, mcmod.info. */
final class ContentGen {
    static final Map<String, String> VANILLA_TABS = Map.ofEntries(Map.entry("building_blocks", "tabBlock"),
            Map.entry("decorations", "tabDecorations"), Map.entry("redstone", "tabRedstone"),
            Map.entry("transportation", "tabTransport"), Map.entry("misc", "tabMisc"), Map.entry("food", "tabFood"),
            Map.entry("tools", "tabTools"), Map.entry("combat", "tabCombat"), Map.entry("brewing", "tabBrewing"),
            Map.entry("materials", "tabMaterials"), Map.entry("search", "tabAllSearch"));

    private final Plan plan;
    private final GenContext ctx;
    private final BlockGen blocks;
    private final FluidGen fluids;
    /** creative tab name → field (own tabs only). */
    private final Map<String, String> tabFields = new TreeMap<>();

    ContentGen(Plan plan, BlockGen blocks, FluidGen fluids) {
        this.plan = plan;
        this.ctx = plan.ctx;
        this.blocks = blocks;
        this.fluids = fluids;
    }

    static String blockField(String id) {
        return "block" + Names.pascal(Names.path(id));
    }

    static String itemField(String id) {
        return "item" + Names.pascal(Names.path(id));
    }

    void run() {
        lookup();
        content();
        clientEvents();
        if (!ctx.options.embed()) {
            mod();
            mcmodInfo();
        }
    }

    // ---- content ---------------------------------------------------------------------------------

    private void content() {
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.content).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Everything the IR declares, registered the 1.7.10 way. Call {@link #preInit}, {@link #init} and\n"
                        + "{@link #postInit} from the mod's FML lifecycle handlers$L.\n",
                        ctx.options.embed() ? " (embedded: the host mod calls them and delegates GUI ids to "
                                + plan.guiHandler.simpleName() + ")" : "")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(String.class, "MOD_ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", ctx.options.modId()).build())
                .addField(FieldSpec.builder(Object.class, "modInstance", Modifier.PRIVATE, Modifier.STATIC).build())
                .addMethod(MethodSpec.methodBuilder("modInstance").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(Object.class)
                        .addJavadoc("The mod object passed to preInit (for openGui).\n").addStatement("return modInstance").build());

        MethodSpec.Builder tabs = MethodSpec.methodBuilder("createTabs").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder fl = MethodSpec.methodBuilder("registerFluids").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder bl = MethodSpec.methodBuilder("registerBlocks").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder it = MethodSpec.methodBuilder("registerItems").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder te = MethodSpec.methodBuilder("registerTileEntities").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder po = MethodSpec.methodBuilder("registerPotions").addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        MethodSpec.Builder links = MethodSpec.methodBuilder("linkItems").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Container items and fluid containers (init: every mod's items exist).\n");

        // creative tabs
        Map<String, CodeBlock> tabIcons = new TreeMap<>();
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (i.creativeTab() != null && i.placesBlock() == null && !Boolean.TRUE.equals(i.hidden())) {
                tabIcons.putIfAbsent(i.creativeTab(), CodeBlock.of("$L", itemField(i.id())));
            }
        }
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (b.creativeTab() != null && registersAsBlock(b) && !Boolean.TRUE.equals(b.hidden())) {
                tabIcons.putIfAbsent(b.creativeTab(), CodeBlock.of("$T.getItemFromBlock($L)", T.ITEM, blockField(b.id())));
            }
        }
        for (Map.Entry<String, CodeBlock> e : tabIcons.entrySet()) {
            String tab = e.getKey();
            if (vanillaTab(tab) != null) {
                continue;
            }
            String f = "tab" + Names.pascal(Names.path(tab));
            tabFields.put(tab, f);
            t.addField(FieldSpec.builder(T.CREATIVE_TABS, f, Modifier.PUBLIC, Modifier.STATIC).build());
            TypeSpec anon = TypeSpec.anonymousClassBuilder("$S", Names.path(tab).replace('/', '.')).superclass(T.CREATIVE_TABS)
                    .addMethod(MethodSpec.methodBuilder("getTabIconItem").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .returns(T.ITEM).addStatement("return $L", e.getValue()).build()).build();
            tabs.addStatement("$L = $L", f, anon);
            ctx.info("creative-tab", tab, "own creative tab, label itemGroup." + Names.path(tab).replace('/', '.'));
        }

        // fluids (before blocks: fluid blocks need them)
        for (IrNode.Fluid f : ctx.ir.all(IrNode.Fluid.class)) {
            String field = FluidGen.field(f.id());
            t.addField(FieldSpec.builder(T.FLUID, field, Modifier.PUBLIC, Modifier.STATIC).build());
            String name = fluids.fluidName(f);
            fl.beginControlFlow("if ($T.isFluidRegistered($S))", T.FLUID_REGISTRY, name)
                    .addStatement("$L = $T.getFluid($S)", field, T.FLUID_REGISTRY, name)
                    .nextControlFlow("else")
                    .addStatement("$L = new $T()", field, plan.fluidClassFor(f))
                    .addStatement("$T.registerFluid($L)", T.FLUID_REGISTRY, field)
                    .endControlFlow();
            if (f.logicClass() != null && ctx.options.stubs()) {
                ClassName logic = Plan.cls(f.logicClass());
                ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC)
                        .superclass(plan.fluidClass(f.id())).addJavadoc("Fluid logic of $L.\n", f.id())
                        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build()).build(), "fluid " + f.id());
            }
            if (f.block() != null) {
                String bf = FluidGen.blockField(f.id());
                t.addField(FieldSpec.builder(T.BLOCK, bf, Modifier.PUBLIC, Modifier.STATIC).build());
                fl.beginControlFlow("if ($L.getBlock() == null)", field)
                        .addStatement("$L = new $T($L)", bf, fluids.blockClass(f), field)
                        .addStatement("$T.registerBlock($L, $S)", T.GAME_REGISTRY, bf, plan.blockRegistryName(f.block()))
                        .addStatement("$L.setBlock($L)", field, bf)
                        .endControlFlow();
            }
            if (f.bucket() != null) {
                Optional<IrNode.Item> bucket = ctx.ir.get(IrNode.Item.class, f.bucket());
                String empty = bucket.map(IrNode.Item::containerItem).orElse("minecraft:bucket");
                CodeBlock filled = itemRef(f.bucket(), f.id());
                CodeBlock emptyRef = itemRef(empty, f.id());
                if (filled != null && emptyRef != null) {
                    links.beginControlFlow("if ($L != null && $L != null)", filled, emptyRef)
                            .addStatement("$T.registerFluidContainer(new $T($L, 1000), new $T($L), new $T($L))", T.FLUID_CONTAINERS,
                                    T.FLUID_STACK, field, T.ITEM_STACK, filled, T.ITEM_STACK, emptyRef)
                            .endControlFlow();
                    ctx.info("fluid-container", f.id(), "bucket " + f.bucket() + " registered as a 1000 mB container of " + empty);
                }
            }
        }

        // blocks
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (!registersAsBlock(b)) {
                continue;
            }
            String field = blockField(b.id());
            t.addField(FieldSpec.builder(T.BLOCK, field, Modifier.PUBLIC, Modifier.STATIC).build());
            if (plan.sourceConstructed(b)) {
                bl.addStatement("$L = $L", field, plan.sourceNew(b, b.logicClass()));
            } else {
                bl.addStatement("$L = new $T()", field, plan.blockClass(b));
            }
            if (b.logicClass() != null) {
                bl.addStatement("$T.$L($L)", plan.blockBase(b.id()), BlockGen.APPLY_LEGACY, field);
            }
            CodeBlock tab = Boolean.TRUE.equals(b.hidden()) ? null : tab(b.creativeTab(), b.id());
            if (tab != null) {
                bl.addStatement("$L.setCreativeTab($L)", field, tab);
            } else if (Boolean.TRUE.equals(b.hidden())) {
                ctx.info("hidden-no-tab", b.id(), "hidden block: no creative tab");
            }
            Optional<IrNode.Item> item = ctx.ir.all(IrNode.Item.class).stream().filter(i -> b.id().equals(i.placesBlock())).findFirst();
            String reg = plan.blockRegistryName(b.id());
            if (item.isPresent() && item.get().logicClass() != null) {
                bl.addStatement("$T.registerBlock($L, $T.class, $S)", T.GAME_REGISTRY, field, Plan.cls(item.get().logicClass()), reg);
            } else if (item.isPresent() || b.item() != null) {
                bl.addStatement("$T.registerBlock($L, $S)", T.GAME_REGISTRY, field, reg);
            } else {
                bl.addStatement("$T.registerBlock($L, null, $S)", T.GAME_REGISTRY, field, reg);
                ctx.info("block-no-item", b.id(), "no block item in IR; registered without ItemBlock");
            }
            if (item.isPresent() && !Names.path(item.get().id()).equals(Names.path(b.id()))) {
                ctx.warn("block-item-name", item.get().id(), "1.7.10 names the ItemBlock like its block: " + ctx.options.modId() + ":" + reg);
            }
        }
        // tile entities
        for (IrNode.BlockEntity be : ctx.ir.all(IrNode.BlockEntity.class)) {
            te.addStatement("$T.registerTileEntity($T.class, $S)", T.GAME_REGISTRY, plan.tileClass(be),
                    ctx.options.modId() + ":" + plan.registryName(be.id()));
        }
        for (Map.Entry<String, ClassName> st : plan.stateTiles.entrySet()) {
            te.addStatement("$T.registerTileEntity($T.class, $S)", T.GAME_REGISTRY, st.getValue(),
                    ctx.options.modId() + ":" + plan.registryName(st.getKey()) + "_state");
        }
        // items
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (i.placesBlock() != null) {
                continue;
            }
            String field = itemField(i.id());
            t.addField(FieldSpec.builder(T.ITEM, field, Modifier.PUBLIC, Modifier.STATIC).build());
            boolean hasIcon = new ItemGen(plan).checkIcon(i);
            if (plan.sourceConstructed(i)) {
                it.addStatement("$L = $L", field, plan.sourceNew(i, i.logicClass()));
                it.addStatement("$L.setUnlocalizedName($S)", field, plan.legacyName(i.id(), i.extensions()));
                if (hasIcon) {
                    it.addStatement("$L.setTextureName($S)", field, new ItemGen(plan).icon(i));
                }
            } else if (ItemGen.shared(ctx, i)) {
                if (i.textures() == null || i.textures().get("layer0") == null) ctx.warn("item-no-texture", i.id(), "no layer0 texture; icon " + new ItemGen(plan).icon(i) + " assumed");
                it.addStatement("$L = new $T($S, $S, $S)", field, plan.itemClass(i), plan.registryName(i.id()),
                        plan.legacyName(i.id(), i.extensions()), hasIcon ? new ItemGen(plan).icon(i) : null);
            } else {
                it.addStatement("$L = new $T()", field, plan.itemClass(i));
            }
            CodeBlock tab = Boolean.TRUE.equals(i.hidden()) ? null : tab(i.creativeTab(), i.id());
            if (tab != null) {
                it.addStatement("$L.setCreativeTab($L)", field, tab);
            } else if (Boolean.TRUE.equals(i.hidden())) {
                ctx.info("hidden-no-tab", i.id(), "hidden item: no creative tab");
            }
            it.addStatement("$T.registerItem($L, $S)", T.GAME_REGISTRY, field, plan.registryName(i.id()));
            if (i.containerItem() != null) {
                CodeBlock c = itemRef(i.containerItem(), i.id());
                if (c != null) {
                    links.addStatement("$L.setContainerItem($L)", field, c);
                }
            }
        }
        // potions
        for (IrNode.Effect e : ctx.ir.all(IrNode.Effect.class)) {
            String field = PotionGen.field(e.id());
            String idConst = "POTION_ID_" + Names.constant(Names.path(e.id()));
            int id = plan.potionIds.get(e.id());
            t.addField(FieldSpec.builder(TypeName.INT, idConst, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("Potion id of $L (--potion-ids $L).\n", e.id(), ctx.options.potionIds()).initializer("$L", id).build());
            t.addField(FieldSpec.builder(T.POTION, field, Modifier.PUBLIC, Modifier.STATIC).build());
            String idVar = "id" + Names.pascal(Names.path(e.id()));
            po.addStatement("int $L = $T.potionId($L, $S)", idVar, plan.lookup, idConst, e.id())
                    .beginControlFlow("if ($L < 0)", idVar)
                    .addStatement("throw new IllegalStateException($S + $L + $S)", "porter: no free potion id for ", "\"" + e.id() + "\"",
                            " (planned id taken, 32..127 full)")
                    .endControlFlow()
                    .addStatement("$L = new $T($L)", field, plan.potionClass(e), idVar);
        }

        MethodSpec.Builder pre = MethodSpec.methodBuilder("preInit").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.PRE_INIT, "event").addParameter(Object.class, "mod")
                .addStatement("modInstance = mod")
                .addStatement("createTabs()").addStatement("registerFluids()").addStatement("registerBlocks()")
                .addStatement("registerItems()").addStatement("registerTileEntities()").addStatement("registerPotions()");
        if (!ctx.ir.all(IrNode.Packet.class).isEmpty()) {
            pre.addStatement("$T.init()", plan.network);
        }
        pre.beginControlFlow("if (event.getSide().isClient())")
                .addStatement("$T.EVENT_BUS.register(new $T())", T.FORGE, plan.clientEvents).endControlFlow();
        MethodSpec.Builder init = MethodSpec.methodBuilder("init").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.INIT, "event")
                .addStatement("linkItems()")
                .addStatement("$T.registerOreDictionary()", plan.recipes)
                .addStatement("$T.register()", plan.recipes);
        if (!ctx.ir.all(IrNode.Multiblock.class).isEmpty()) {
            init.addStatement("$T.init()", plan.multiblocks);
        }
        if (!ctx.ir.all(IrNode.Menu.class).isEmpty()) {
            if (ctx.options.embed()) {
                ctx.info("gui-handler-embedded", plan.guiHandler.canonicalName(), "embedded: the host IGuiHandler must delegate ids "
                        + ctx.options.guiIds() + " (" + plan.guiHandler.simpleName() + ".handles(id))");
            } else {
                init.addStatement("$T.INSTANCE.registerGuiHandler(modInstance, $T.INSTANCE)", T.NETWORK_REGISTRY, plan.guiHandler);
            }
        }
        MethodSpec.Builder post = MethodSpec.methodBuilder("postInit").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.POST_INIT, "event").addStatement("$T.postInit()", plan.recipes);
        t.addMethod(pre.build()).addMethod(init.build()).addMethod(post.build())
                .addMethod(tabs.build()).addMethod(fl.build()).addMethod(bl.build()).addMethod(it.build())
                .addMethod(te.build()).addMethod(po.build()).addMethod(links.build());
        ctx.out.addJava(plan.content.packageName(), t.build());
    }

    /** True for blocks registered as ordinary blocks (fluid blocks are registered with their fluid). */
    private boolean registersAsBlock(IrNode.Block b) {
        return FluidGen.fluidOfBlock(ctx, b.id()).isEmpty();
    }

    private String vanillaTab(String tab) {
        String ns = Names.namespace(tab);
        if (!"minecraft".equals(ns) && tab.contains(":")) {
            return null;
        }
        return VANILLA_TABS.get(Names.path(tab));
    }

    private CodeBlock tab(String tab, String subject) {
        if (tab == null) {
            return null;
        }
        String v = vanillaTab(tab);
        if (v != null) {
            return CodeBlock.of("$T.$L", T.CREATIVE_TABS, v);
        }
        String f = tabFields.get(tab);
        if (f == null) {
            ctx.warn("creative-tab-empty", subject, "creative tab " + tab + " has no item to show; not set");
            return null;
        }
        return CodeBlock.of("$L", f);
    }

    /** Expression for an Item of an id (field for our own items/blocks, lookup otherwise), or null if unresolvable. */
    CodeBlock itemRef(String id, String subject) {
        if (ctx.isOwn(id)) {
            if (ctx.ir.get(IrNode.Item.class, id).filter(i -> i.placesBlock() == null).isPresent()) {
                return CodeBlock.of("$L", itemField(id));
            }
            if (ctx.ir.get(IrNode.Block.class, id).isPresent()) {
                return CodeBlock.of("$T.getItemFromBlock($L)", T.ITEM, blockField(id));
            }
        }
        String[] t = plan.target(id, false);
        if (t == null) {
            ctx.warn("vanilla-id-unmapped", subject, id + " is not a 1.7.10 item; reference dropped");
            return null;
        }
        return CodeBlock.of("$T.item($S, $S)", plan.lookup, t[0], t[1]);
    }

    // ---- lookup helper ---------------------------------------------------------------------------

    private void lookup() {
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.lookup).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Runtime lookups by registry name; anything unresolved is logged and skipped, never guessed.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(T.LOGGER, "LOG", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($S)", T.LOG_MANAGER, "porter-" + ctx.options.modId()).build());
        t.addMethod(MethodSpec.methodBuilder("item").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(T.ITEM)
                .addParameter(String.class, "modId").addParameter(String.class, "name")
                .addStatement("$T item = $T.findItem(modId, name)", T.ITEM, T.GAME_REGISTRY)
                .beginControlFlow("if (item == null)")
                .addStatement("$T block = $T.findBlock(modId, name)", T.BLOCK, T.GAME_REGISTRY)
                .beginControlFlow("if (block != null)").addStatement("item = $T.getItemFromBlock(block)", T.ITEM).endControlFlow()
                .endControlFlow()
                .addStatement("return item").build());
        t.addMethod(MethodSpec.methodBuilder("block").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(T.BLOCK)
                .addParameter(String.class, "modId").addParameter(String.class, "name")
                .addStatement("return $T.findBlock(modId, name)", T.GAME_REGISTRY).build());
        t.addMethod(MethodSpec.methodBuilder("stack").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(T.ITEM_STACK)
                .addParameter(String.class, "modId").addParameter(String.class, "name").addParameter(TypeName.INT, "count")
                .addParameter(TypeName.INT, "meta")
                .addStatement("$T item = item(modId, name)", T.ITEM)
                .beginControlFlow("if (item == null)")
                .addStatement("LOG.warn($S, modId, name)", "porter: unknown item {}:{}").addStatement("return null").endControlFlow()
                .addStatement("return new $T(item, count, meta)", T.ITEM_STACK).build());
        t.addMethod(MethodSpec.methodBuilder("missing").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.BOOLEAN)
                .addParameter(String.class, "recipe").addParameter(Object[].class, "parts").varargs()
                .beginControlFlow("for (Object p : parts)")
                .beginControlFlow("if (p == null)")
                .addStatement("LOG.warn($S, recipe)", "porter: recipe {} skipped (unresolved item)").addStatement("return true")
                .endControlFlow().endControlFlow()
                .addStatement("return false").build());
        t.addMethod(MethodSpec.methodBuilder("hasOre").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.BOOLEAN)
                .addParameter(T.ITEM_STACK, "stack").addParameter(String.class, "ore")
                .beginControlFlow("if (stack == null || stack.getItem() == null)").addStatement("return false").endControlFlow()
                .addStatement("int id = $T.getOreID(ore)", T.ORE_DICT)
                .beginControlFlow("for (int i : $T.getOreIDs(stack))", T.ORE_DICT)
                .beginControlFlow("if (i == id)").addStatement("return true").endControlFlow()
                .endControlFlow().addStatement("return false").build());
        t.addMethod(MethodSpec.methodBuilder("addOre").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(String.class, "ore").addParameter(String.class, "modId").addParameter(String.class, "name")
                .addStatement("$T s = stack(modId, name, 1, 0)", T.ITEM_STACK)
                .beginControlFlow("if (s != null)").addStatement("$T.registerOre(ore, s)", T.ORE_DICT).endControlFlow().build());
        t.addMethod(MethodSpec.methodBuilder("copyOres").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(String.class, "from").addParameter(String.class, "to")
                .beginControlFlow("if (from.equals(to))").addStatement("return").endControlFlow()
                .beginControlFlow("for ($T s : new $T<$T>($T.getOres(from)))", T.ITEM_STACK, ArrayList.class, T.ITEM_STACK, T.ORE_DICT)
                .addStatement("$T.registerOre(to, s.copy())", T.ORE_DICT).endControlFlow().build());
        t.addMethod(MethodSpec.methodBuilder("smelt").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.ITEM_STACK, "in").addParameter(T.ITEM_STACK, "out").addParameter(TypeName.FLOAT, "xp")
                .beginControlFlow("if (in != null && out != null)")
                .addStatement("$T.addSmelting(in, out.copy(), xp)", T.GAME_REGISTRY).endControlFlow().build());
        t.addMethod(MethodSpec.methodBuilder("smeltOres").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(String.class, "ore").addParameter(T.ITEM_STACK, "out").addParameter(TypeName.FLOAT, "xp")
                .beginControlFlow("for ($T s : new $T<$T>($T.getOres(ore)))", T.ITEM_STACK, ArrayList.class, T.ITEM_STACK, T.ORE_DICT)
                .addStatement("smelt(s.copy(), out, xp)").endControlFlow().build());
        if (!ctx.ir.all(IrNode.Effect.class).isEmpty()) {
            // modpacks often take the --potion-ids slots: keep the planned id when free, else the first free id 32..127
            // (array grown to 128 like other 1.7.10 mods do; effect ids are saved as a byte)
            ClassName field = ClassName.get(java.lang.reflect.Field.class);
            ClassName mods = ClassName.get(java.lang.reflect.Modifier.class);
            t.addMethod(MethodSpec.methodBuilder("potionId").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.INT)
                    .addJavadoc("Planned potion id if free, else the first free id 32..127 (logged); -1 if none.\n")
                    .addParameter(TypeName.INT, "wanted").addParameter(String.class, "effect")
                    .beginControlFlow("if ($T.potionTypes.length < 128)", T.POTION)
                    .beginControlFlow("try")
                    .beginControlFlow("for ($T f : $T.class.getDeclaredFields())", field, T.POTION)
                    .beginControlFlow("if (f.getType() == $T[].class && $T.isStatic(f.getModifiers()))", T.POTION, mods)
                    .addStatement("f.setAccessible(true)")
                    .addStatement("$T m = $T.class.getDeclaredField($S)", field, field, "modifiers")
                    .addStatement("m.setAccessible(true)")
                    .addStatement("m.setInt(f, f.getModifiers() & ~$T.FINAL)", mods)
                    .addStatement("$T[] bigger = new $T[128]", T.POTION, T.POTION)
                    .addStatement("System.arraycopy($T.potionTypes, 0, bigger, 0, $T.potionTypes.length)", T.POTION, T.POTION)
                    .addStatement("f.set(null, bigger)")
                    .endControlFlow().endControlFlow()
                    .nextControlFlow("catch ($T e)", Exception.class)
                    .addStatement("LOG.error($S, e)", "porter: could not grow the potion id array")
                    .endControlFlow().endControlFlow()
                    .beginControlFlow("if (wanted > 0 && wanted < $T.potionTypes.length && $T.potionTypes[wanted] == null)", T.POTION, T.POTION)
                    .addStatement("return wanted").endControlFlow()
                    .beginControlFlow("for (int i = 32; i < $T.potionTypes.length && i < 128; i++)", T.POTION)
                    .beginControlFlow("if ($T.potionTypes[i] == null)", T.POTION)
                    .addStatement("LOG.warn($S, wanted, effect, i)", "porter: potion id {} for {} is taken, using {}")
                    .addStatement("return i").endControlFlow().endControlFlow()
                    .addStatement("return -1").build());
        }
        ctx.out.addJava(plan.lookup.packageName(), t.build());
    }

    // ---- client events -----------------------------------------------------------------------------

    private void clientEvents() {
        MethodSpec.Builder stitch = MethodSpec.methodBuilder("onTextureStitch").addModifiers(Modifier.PUBLIC)
                .addAnnotation(T.SUBSCRIBE).addParameter(T.TEXTURE_STITCH_PRE, "event")
                .beginControlFlow("if (event.map.getTextureType() != 0)").addStatement("return").endControlFlow();
        for (IrNode.Fluid f : ctx.ir.all(IrNode.Fluid.class)) {
            ClassName cls = plan.fluidClass(f.id());
            stitch.beginControlFlow("if ($T.$L instanceof $T)", plan.content, FluidGen.field(f.id()), cls)
                    .addStatement("$T.$L.setIcons(event.map.registerIcon($T.STILL_ICON), event.map.registerIcon($T.FLOWING_ICON))",
                            plan.content, FluidGen.field(f.id()), cls, cls)
                    .endControlFlow();
        }
        TypeSpec t = TypeSpec.classBuilder(plan.clientEvents).addModifiers(Modifier.PUBLIC).addAnnotation(BlockGen.clientOnly())
                .addJavadoc("Client-only Forge event handlers (fluid icons).\n").addMethod(stitch.build()).build();
        ctx.out.addJava(plan.clientEvents.packageName(), t);
    }

    // ---- standalone mod ----------------------------------------------------------------------------

    private void mod() {
        StringBuilder deps = new StringBuilder();
        ctx.ir.mod().ifPresent(m -> {
            if (m.dependencies() != null) {
                for (IrNode.Mod.Dependency d : m.dependencies()) {
                    if (List.of("forge", "minecraft").contains(d.modId())) {
                        continue;
                    }
                    deps.append(deps.isEmpty() ? "" : ";").append(Boolean.FALSE.equals(d.required()) ? "after:" : "required-after:")
                            .append(d.modId());
                    ctx.info("mod-dependency", d.modId(), "1.7.10 dependency " + d.modId() + " (version range not carried over)");
                }
            }
        });
        AnnotationSpec.Builder a = AnnotationSpec.builder(T.MOD).addMember("modid", "$S", ctx.options.modId())
                .addMember("name", "$S", ctx.options.modName()).addMember("version", "$S", ctx.options.modVersion());
        if (!deps.isEmpty()) {
            a.addMember("dependencies", "$S", deps.toString());
        }
        TypeSpec t = TypeSpec.classBuilder(plan.modClass).addModifiers(Modifier.PUBLIC).addAnnotation(a.build())
                .addJavadoc("Standalone FML entry point (omitted with --embed).\n")
                .addField(FieldSpec.builder(plan.modClass, "instance", Modifier.PUBLIC, Modifier.STATIC)
                        .addAnnotation(AnnotationSpec.builder(T.MOD_INSTANCE).addMember("value", "$S", ctx.options.modId()).build()).build())
                .addMethod(handler("preInit", T.PRE_INIT, CodeBlock.of("$T.preInit(event, this)", plan.content)))
                .addMethod(handler("init", T.INIT, CodeBlock.of("$T.init(event)", plan.content)))
                .addMethod(handler("postInit", T.POST_INIT, CodeBlock.of("$T.postInit(event)", plan.content)))
                .build();
        ctx.out.addJava(plan.modClass.packageName(), t);
    }

    private static MethodSpec handler(String name, ClassName event, CodeBlock body) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(T.MOD_EVENT_HANDLER)
                .addParameter(event, "event").addStatement(body).build();
    }

    private void mcmodInfo() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("modid", ctx.options.modId());
        e.put("name", ctx.options.modName());
        ctx.ir.mod().ifPresent(m -> {
            if (m.description() != null) {
                e.put("description", m.description());
            }
        });
        e.put("version", ctx.options.modVersion());
        e.put("mcversion", "1.7.10");
        e.put("authorList", ctx.ir.mod().map(IrNode.Mod::authors).orElse(List.of()));
        ctx.out.putJson(GenOutput.RESOURCES_ROOT + "mcmod.info", List.of(e));
    }
}
