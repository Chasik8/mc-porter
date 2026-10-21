package dev.porter.generate.forge1710;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import javax.lang.model.element.Modifier;
import tools.jackson.databind.JsonNode;

/** IR {@code block} → {@code <Name>BlockBase extends Block} (+ stub for the logic class, + state-holder tile). */
final class BlockGen {
    static final String[] SIDES = {"down", "up", "north", "south", "west", "east"};
    /** Horizontal directions clockwise seen from above (model y-rotation order). */
    static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    /** Static method of a block base that names/textures a logic class not extending the base. */
    static final String APPLY_LEGACY = "applyLegacy";
    /** 1.7.10 side whose default-state icon is a logic block's single fallback icon (2 = north). */
    static final int DEFAULT_ICON_SIDE = 2;

    static final Map<String, String> MATERIALS = new HashMap<>();
    static final Map<String, String> SOUNDS = new HashMap<>();

    static {
        String[][] m = {{"stone", "rock"}, {"metal", "iron"}, {"heavy_metal", "anvil"}, {"wood", "wood"},
            {"nether_wood", "wood"}, {"dirt", "ground"}, {"grass", "grass"}, {"sand", "sand"}, {"glass", "glass"},
            {"buildable_glass", "glass"}, {"ice", "ice"}, {"ice_solid", "packedIce"}, {"wool", "cloth"},
            {"cloth_decoration", "carpet"}, {"plant", "plants"}, {"replaceable_plant", "vine"}, {"leaves", "leaves"},
            {"water", "water"}, {"lava", "lava"}, {"clay", "clay"}, {"sponge", "sponge"}, {"cactus", "cactus"},
            {"cake", "cake"}, {"web", "web"}, {"portal", "portal"}, {"explosive", "tnt"}, {"decoration", "circuits"},
            {"piston", "piston"}, {"snow", "craftedSnow"}, {"top_snow", "snow"}, {"fire", "fire"},
            {"vegetable", "gourd"}, {"egg", "dragonEgg"}, {"air", "air"}};
        for (String[] e : m) {
            MATERIALS.put(e[0], e[1]);
        }
        String[][] s = {{"stone", "soundTypeStone"}, {"wood", "soundTypeWood"}, {"gravel", "soundTypeGravel"},
            {"grass", "soundTypeGrass"}, {"metal", "soundTypeMetal"}, {"glass", "soundTypeGlass"},
            {"wool", "soundTypeCloth"}, {"sand", "soundTypeSand"}, {"snow", "soundTypeSnow"},
            {"ladder", "soundTypeLadder"}, {"anvil", "soundTypeAnvil"}};
        for (String[] e : s) {
            SOUNDS.put(e[0], e[1]);
        }
    }

    private final Plan plan;
    private final GenContext ctx;

    BlockGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (FluidGen.fluidOfBlock(ctx, b.id()).isPresent()) {
                continue; // fluid blocks are emitted by FluidGen
            }
            emit(b);
        }
    }

    /** The tile class a block creates (IR block entity, or a generated state holder when state spills), if any. */
    Optional<ClassName> tileFor(IrNode.Block b) {
        Optional<IrNode.BlockEntity> be = blockEntity(ctx, b);
        if (be.isPresent()) {
            return Optional.of(plan.tileClass(be.get()));
        }
        return plan.layouts.get(b.id()).spills() ? Optional.of(plan.stateTile(b.id())) : Optional.empty();
    }

    /** The tile base (generated) that holds spilled state fields for this block. */
    ClassName tileBaseFor(IrNode.Block b) {
        return blockEntity(ctx, b).map(be -> plan.tileBase(be.id())).orElse(plan.stateTile(b.id()));
    }

    static Optional<IrNode.BlockEntity> blockEntity(GenContext ctx, IrNode.Block b) {
        if (b.blockEntity() != null) {
            Optional<IrNode.BlockEntity> be = ctx.ir.get(IrNode.BlockEntity.class, b.blockEntity());
            if (be.isPresent()) {
                return be;
            }
        }
        return ctx.ir.all(IrNode.BlockEntity.class).stream().filter(e -> e.blocks().contains(b.id())).findFirst();
    }

    private void emit(IrNode.Block b) {
        ClassName base = plan.blockBase(b.id());
        MetaLayout layout = plan.layouts.get(b.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.BLOCK)
                .addJavadoc("Block $L (IR $L).\n", b.id(), b.provenance().file());
        if (b.logicClass() != null) {
            t.addJavadoc("Logic: {@code $L} extends this class (stub or rewriter output).\n", b.logicClass());
        }

        // ---- state layout constants
        for (MetaLayout.Prop p : layout.props) {
            String c = dev.porter.generate.Names.constant(p.name());
            t.addField(FieldSpec.builder(ArrayTypeName.of(String.class), "VALUES_" + c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("{$L}", String.join(", ", p.values().stream().map(v -> CodeBlock.of("$S", v).toString()).toList()))
                    .build());
            if (p.inMeta()) {
                t.addField(FieldSpec.builder(TypeName.INT, "META_" + c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("Radix weight of {@code $L} in the metadata.\n", p.name())
                        .initializer("$L", p.multiplier()).build());
            }
        }
        t.addField(FieldSpec.builder(TypeName.INT, "META_STATES", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", layout.metaStates).build());
        t.addField(FieldSpec.builder(TypeName.INT, "DEFAULT_META", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", layout.defaultMeta()).build());

        // ---- constructor
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        ctor.addStatement("super($T.$L)", T.MATERIAL, material(b));
        ctor.addStatement("setBlockName($S)", plan.legacyName(b.id(), b.extensions()));
        if (b.hardness() != null) {
            ctor.addStatement("setHardness($LF)", f(b.hardness()));
        }
        if (b.resistance() != null) {
            // 1.7.10: setResistance(r) stores r*3 and explosions use stored/5, i.e. 0.6*r; 1.19.2 uses r directly.
            ctor.addStatement("setResistance($LF)", f(b.resistance() / 0.6));
        }
        if (b.lightLevel() != null && b.lightLevel() > 0) {
            ctor.addStatement("this.lightValue = $L", Math.min(15, b.lightLevel()));
        }
        if (b.soundType() != null) {
            String s = SOUNDS.get(b.soundType());
            if (s == null) {
                ctx.warn("sound-type-unmapped", b.id(), "soundType " + b.soundType() + " has no 1.7.10 equivalent; stone used");
                s = "soundTypeStone";
            }
            ctor.addStatement("setStepSound($T.$L)", T.BLOCK, s);
        }
        if (b.harvest() != null && b.harvest().tool() != null) {
            String tool = b.harvest().tool();
            if (List.of("pickaxe", "axe", "shovel").contains(tool)) {
                ctor.addStatement("setHarvestLevel($S, $L)", tool, b.harvest().level() == null ? 0 : b.harvest().level());
            } else {
                ctx.warn("harvest-tool-unmapped", b.id(), "harvest tool " + tool + " has no Forge 1.7.10 tool class; skipped");
            }
        }
        if (layout.defaultMeta() != 0) {
            ctx.info("default-meta", b.id(), "default state has meta " + layout.defaultMeta() + "; placement code must set it");
        }
        t.addMethod(ctor.build());

        // ---- icons
        Icons icons = icons(b, layout);
        t.addField(FieldSpec.builder(ArrayTypeName.of(String.class), "ICON_NAMES", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                .initializer("{$L}", String.join(", ", icons.names.stream().map(n -> CodeBlock.of("$S", n).toString()).toList()))
                .build());
        t.addField(FieldSpec.builder(ArrayTypeName.of(ArrayTypeName.of(TypeName.INT)), "STATE_ICONS", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("[state][side] → index into ICON_NAMES; state = meta + META_STATES * spilled index.\n")
                .initializer("$L", intTable(icons.table)).build());
        t.addField(FieldSpec.builder(ArrayTypeName.of(T.ICON), "icons", Modifier.PROTECTED).build());
        t.addMethod(MethodSpec.methodBuilder("registerBlockIcons").addAnnotation(Override.class).addAnnotation(clientOnly())
                .addModifiers(Modifier.PUBLIC).addParameter(T.ICON_REGISTER, "reg")
                .addStatement("icons = new $T[ICON_NAMES.length]", T.ICON)
                .beginControlFlow("for (int i = 0; i < ICON_NAMES.length; i++)")
                .addStatement("icons[i] = reg.registerIcon(ICON_NAMES[i])")
                .endControlFlow()
                .addStatement("blockIcon = icons[0]").build());
        t.addMethod(MethodSpec.methodBuilder("getIcon").addAnnotation(Override.class).addAnnotation(clientOnly())
                .addModifiers(Modifier.PUBLIC).returns(T.ICON).addParameter(TypeName.INT, "side").addParameter(TypeName.INT, "meta")
                .addStatement("int state = meta >= 0 && meta < META_STATES ? meta : DEFAULT_META")
                .addStatement("return icons[STATE_ICONS[state][side < 0 || side > 5 ? 0 : side]]").build());
        if (layout.spills() && icons.dependsOnSpill) {
            MethodSpec.Builder wi = MethodSpec.methodBuilder("getIcon").addAnnotation(Override.class).addAnnotation(clientOnly())
                    .addModifiers(Modifier.PUBLIC).returns(T.ICON).addParameter(T.BLOCK_ACCESS, "world")
                    .addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z")
                    .addParameter(TypeName.INT, "side")
                    .addStatement("int meta = world.getBlockMetadata(x, y, z)")
                    .addStatement("int state = meta >= 0 && meta < META_STATES ? meta : DEFAULT_META")
                    .addStatement("int spill = 0")
                    .addStatement("int weight = 1");
            for (MetaLayout.Prop p : layout.spilled()) {
                wi.addStatement("spill += weight * $L(world, x, y, z)", dev.porter.generate.Names.field(p.name()));
                wi.addStatement("weight *= $L", p.values().size());
            }
            wi.addStatement("state += META_STATES * spill");
            wi.addStatement("return icons[STATE_ICONS[state < STATE_ICONS.length ? state : DEFAULT_META][side < 0 || side > 5 ? 0 : side]]");
            t.addMethod(wi.build());
        }

        // ---- a logic class that does not extend this base (e.g. a closure-ported block whose root extends the
        //      1.19.2-API adapter) still gets the legacy name the lang file uses and a 1.7.10 icon
        if (b.logicClass() != null) {
            MethodSpec.Builder legacy = MethodSpec.methodBuilder(APPLY_LEGACY).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addJavadoc("Legacy name and default icon for a logic class that did not run this base's constructor\n"
                            + "(unlocalized name still unset). Called by the generated registration.\n")
                    .addParameter(T.BLOCK, "block")
                    .beginControlFlow("if ($S.equals(block.getUnlocalizedName()))", "tile.null")
                    .addStatement("block.setBlockName($S)", plan.legacyName(b.id(), b.extensions()));
            if (!icons.guessed()) { // a guessed icon name has no texture: the logic class draws its own icons
                legacy.addStatement("block.setBlockTextureName(ICON_NAMES[STATE_ICONS[DEFAULT_META][$L]])", DEFAULT_ICON_SIDE);
            }
            t.addMethod(legacy.endControlFlow().build());
        }

        // ---- state accessors (meta or tile field)
        Optional<ClassName> tile = tileFor(b);
        for (MetaLayout.Prop p : layout.props) {
            addAccessors(t, b, p, tile.isPresent() ? tileBaseFor(b) : null);
        }

        // ---- drops keep the default state (state lives in the world, not in the item)
        t.addMethod(MethodSpec.methodBuilder("damageDropped").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT).addParameter(TypeName.INT, "meta").addStatement("return 0").build());

        // ---- tile entity
        if (tile.isPresent()) {
            t.addMethod(MethodSpec.methodBuilder("hasTileEntity").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.BOOLEAN).addParameter(TypeName.INT, "meta").addStatement("return true").build());
            t.addMethod(MethodSpec.methodBuilder("createTileEntity").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(T.TILE).addParameter(T.WORLD, "world").addParameter(TypeName.INT, "meta")
                    .addStatement("return new $T()", tile.get()).build());
            Optional<IrNode.Menu> menu = blockEntity(ctx, b).flatMap(be -> MenuGen.menuOf(ctx, be));
            if (menu.isPresent()) {
                int id = plan.guiIds.get(menu.get().id());
                t.addMethod(MethodSpec.methodBuilder("onBlockActivated").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .addJavadoc("Opens menu $L (GUI id $L). Override in the logic class for other behaviour.\n", menu.get().id(), id)
                        .returns(TypeName.BOOLEAN).addParameter(T.WORLD, "world").addParameter(TypeName.INT, "x")
                        .addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z").addParameter(T.PLAYER, "player")
                        .addParameter(TypeName.INT, "side").addParameter(TypeName.FLOAT, "hitX").addParameter(TypeName.FLOAT, "hitY")
                        .addParameter(TypeName.FLOAT, "hitZ")
                        .beginControlFlow("if (player.isSneaking())").addStatement("return false").endControlFlow()
                        .beginControlFlow("if (!world.isRemote)")
                        .addStatement("player.openGui($T.modInstance(), $T.$L, world, x, y, z)", plan.content, plan.guiHandler,
                                MenuGen.guiConstant(menu.get()))
                        .endControlFlow()
                        .addStatement("return true").build());
                ctx.info("menu-open-on-use", b.id(), "right click opens menu " + menu.get().id() + " (1.19.2 use() logic is not ported automatically)");
            }
        }
        plan.ctx.out.addJava(base.packageName(), t.build());
        if (layout.spills() && blockEntity(ctx, b).isEmpty()) {
            TileGen.emitStateTile(plan, b, layout);
        }
        if (b.logicClass() != null && plan.sharedBlockLogic(b)) {
            ctx.info("shared-logic-class", b.id(), "logic class " + b.logicClass() + " is shared with other blocks: "
                    + "registered with the generated base " + base.simpleName() + ", no stub (construct the logic class in the port)");
        } else if (b.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(b.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of $L. Rewriter output goes here.\n", b.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addStatement("super()").build())
                    .build(), "block " + b.id());
        }
        Map<String, Object> m = new LinkedHashMap<>(layout.describe());
        m.put("class", base.canonicalName());
        m.put("registryName", ctx.options.modId() + ":" + plan.blockRegistryName(b.id()));
        m.put("icons", icons.names);
        plan.blockManifest.put(b.id(), m);
    }

    private void addAccessors(TypeSpec.Builder t, IrNode.Block b, MetaLayout.Prop p, ClassName tileBase) {
        String c = dev.porter.generate.Names.constant(p.name());
        String getter = dev.porter.generate.Names.field(p.name());
        String setter = "set" + dev.porter.generate.Names.pascal(p.name());
        MethodSpec.Builder g = MethodSpec.methodBuilder(getter).addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.INT)
                .addJavadoc("Value index of {@code $L} (into VALUES_$L) at a position.\n", p.name(), c)
                .addParameter(T.BLOCK_ACCESS, "world").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                .addParameter(TypeName.INT, "z");
        MethodSpec.Builder s = MethodSpec.methodBuilder(setter).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.WORLD, "world").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                .addParameter(TypeName.INT, "z").addParameter(TypeName.INT, "index");
        int n = p.values().size();
        if (p.inMeta()) {
            g.addStatement("return world.getBlockMetadata(x, y, z) / META_$L % $L", c, n);
            s.addStatement("int meta = world.getBlockMetadata(x, y, z)")
                    .addStatement("meta = meta - (meta / META_$L % $L) * META_$L + (index % $L) * META_$L", c, n, c, n, c)
                    .addStatement("world.setBlockMetadataWithNotify(x, y, z, meta, 3)");
        } else {
            g.addStatement("$T te = world.getTileEntity(x, y, z)", T.TILE)
                    .addStatement("return te instanceof $T ? (($T) te).$L : $L", tileBase, tileBase, p.field(), p.defaultIndex());
            s.addStatement("$T te = world.getTileEntity(x, y, z)", T.TILE)
                    .beginControlFlow("if (te instanceof $T)", tileBase)
                    .addStatement("(($T) te).$L = index % $L", tileBase, p.field(), n)
                    .addStatement("te.markDirty()")
                    .addStatement("world.markBlockForUpdate(x, y, z)")
                    .endControlFlow();
        }
        t.addMethod(g.build());
        t.addMethod(s.build());
    }

    static AnnotationSpec clientOnly() {
        return AnnotationSpec.builder(T.SIDE_ONLY).addMember("value", "$T.CLIENT", T.SIDE).build();
    }

    private String material(IrNode.Block b) {
        if (b.material() == null) {
            ctx.info("material-default", b.id(), "no material in IR; rock used");
            return "rock";
        }
        String m = MATERIALS.get(b.material());
        if (m == null) {
            ctx.warn("material-unmapped", b.id(), "material " + b.material() + " has no 1.7.10 equivalent; rock used");
            return "rock";
        }
        return m;
    }

    static String f(double d) {
        float v = (float) d;
        return Float.toString(v);
    }

    private static CodeBlock intTable(int[][] table) {
        CodeBlock.Builder c = CodeBlock.builder().add("{");
        for (int i = 0; i < table.length; i++) {
            c.add(i == 0 ? "\n$>" : ",\n");
            StringBuilder row = new StringBuilder("{");
            for (int j = 0; j < table[i].length; j++) {
                row.append(j == 0 ? "" : ", ").append(table[i][j]);
            }
            c.add("$L", row.append("}"));
        }
        return c.add("$<\n}").build();
    }

    // ---- icon resolution --------------------------------------------------------------------------

    /** @param guessed no textures known: the icon names are the {@code ns:registryName} fallback */
    record Icons(List<String> names, int[][] table, boolean dependsOnSpill, boolean guessed) {
    }

    Icons icons(IrNode.Block b, MetaLayout layout) {
        Map<String, String> faces = baseFaces(b);
        Map<String, Map<String, String>> stateIcons = stateIcons(b);
        MetaLayout.Prop facing = layout.prop("facing");
        // Rotate unless assets:stateIcons already resolves icons per facing (a key for a facing other than north).
        boolean rotate = facing != null && "direction".equals(facing.type())
                && stateIcons.keySet().stream().noneMatch(k -> k.matches(".*facing=(?!north\\b).*"));
        if (rotate) {
            ctx.info("icon-facing-rotation", b.id(), "icons rotated by facing (vanilla orientable convention: model front = north)");
        }
        int spillStates = 1;
        for (MetaLayout.Prop p : layout.spilled()) {
            spillStates *= p.values().size();
        }
        int full = layout.metaStates * spillStates;
        boolean spillUsed = layout.spills() && (rotate && !facing.inMeta() || stateIcons.keySet().stream().anyMatch(k ->
                layout.spilled().stream().anyMatch(p -> k.contains(p.name() + "=") || k.equals(p.name()))));
        if (full > 1024) {
            ctx.warn("icon-table-truncated", b.id(), full + " states; icons resolved for meta states only");
            full = layout.metaStates;
            spillUsed = false;
        }
        int states = spillUsed ? full : layout.metaStates;
        TreeSet<String> unique = new TreeSet<>();
        String[][] bySide = new String[states][6];
        boolean warned = false;
        for (int s = 0; s < states; s++) {
            Map<String, String> state = decode(layout, s);
            Map<String, String> f = new LinkedHashMap<>(faces);
            String key = bestStateKey(stateIcons, state, layout);
            if (key != null) {
                f.putAll(stateIcons.get(key));
            }
            for (int side = 0; side < 6; side++) {
                String modelFace = SIDES[side];
                if (rotate) {
                    String dir = state.get("facing");
                    int k = HORIZONTAL.indexOf(dir);
                    if (k < 0 && !warned && dir != null && !"north".equals(dir)) {
                        ctx.warn("icon-facing-vertical", b.id(), "facing=" + dir + " is not horizontal; icons not rotated for it");
                        warned = true;
                    }
                    int h = HORIZONTAL.indexOf(modelFace);
                    if (k >= 0 && h >= 0) {
                        modelFace = HORIZONTAL.get(Math.floorMod(h - k, 4));
                    }
                }
                String icon = f.getOrDefault(modelFace, f.get("all"));
                if (icon == null) {
                    icon = f.get("particle");
                }
                if (icon == null) {
                    icon = plan.iconNs(dev.porter.generate.Names.namespace(b.id())) + ":" + plan.registryName(b.id());
                }
                bySide[s][side] = icon;
                unique.add(icon);
            }
        }
        if (faces.isEmpty() && stateIcons.isEmpty()) {
            ctx.warn("block-no-textures", b.id(), "no textures/assets:icons; icon " + bySide[0][0] + " assumed");
        }
        List<String> names = new ArrayList<>(unique);
        int[][] table = new int[states][6];
        for (int s = 0; s < states; s++) {
            for (int side = 0; side < 6; side++) {
                table[s][side] = names.indexOf(bySide[s][side]);
            }
        }
        return new Icons(names, table, spillUsed, faces.isEmpty() && stateIcons.isEmpty());
    }

    /** Face → icon name from {@code assets:icons}, else derived from IR textures. */
    private Map<String, String> baseFaces(IrNode.Block b) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode icons = b.extensions() == null ? null : b.extensions().get("assets:icons");
        if (icons != null && icons.isObject()) {
            for (var e : icons.properties()) {
                out.put(e.getKey(), plan.renameIcon(e.getValue().asString()));
            }
            return out;
        }
        if (b.textures() != null) {
            b.textures().forEach((k, v) -> out.put(k, plan.icon(v)));
            ctx.info("icons-derived", b.id(), "no assets:icons; icon names derived from IR textures (run porter assets for exact icons)");
        }
        return out;
    }

    private Map<String, Map<String, String>> stateIcons(IrNode.Block b) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        JsonNode si = b.extensions() == null ? null : b.extensions().get("assets:stateIcons");
        if (si != null && si.isObject()) {
            for (var e : si.properties()) {
                Map<String, String> f = new LinkedHashMap<>();
                for (var g : e.getValue().properties()) {
                    if (!g.getValue().isNull()) {
                        f.put(g.getKey(), plan.renameIcon(g.getValue().asString()));
                    }
                }
                out.put(e.getKey(), f);
            }
        }
        return out;
    }

    /** Decodes a full state index (meta + META_STATES * spill) into property values. */
    static Map<String, String> decode(MetaLayout layout, int s) {
        Map<String, String> state = new LinkedHashMap<>();
        int meta = s % layout.metaStates;
        int spill = s / layout.metaStates;
        for (MetaLayout.Prop p : layout.props) {
            if (p.values().isEmpty()) {
                continue;
            }
            if (p.inMeta()) {
                state.put(p.name(), p.values().get(layout.index(p, meta)));
            } else {
                state.put(p.name(), p.values().get(spill % p.values().size()));
                spill /= p.values().size();
            }
        }
        return state;
    }

    /**
     * The {@code assets:stateIcons} key matching a state best: {@code a=b,c=d} keys must match every pair; a bare
     * key {@code name} means boolean property {@code name=true}; {@code default} never matches. Most pairs win.
     */
    private String bestStateKey(Map<String, Map<String, String>> stateIcons, Map<String, String> state, MetaLayout layout) {
        String best = null;
        int bestScore = 0;
        for (String key : stateIcons.keySet()) {
            int score = 0;
            boolean ok = true;
            for (String part : key.split(",")) {
                String[] kv = part.split("=", 2);
                String name = kv[0].trim();
                String value = kv.length > 1 ? kv[1].trim() : null;
                if (value == null) {
                    MetaLayout.Prop p = layout.prop(name);
                    if (p == null || !"boolean".equals(p.type())) {
                        ok = false;
                        break;
                    }
                    value = "true";
                }
                if (!value.equals(state.get(name))) {
                    ok = false;
                    break;
                }
                score++;
            }
            if (ok && score > bestScore) {
                best = key;
                bestScore = score;
            }
        }
        return best;
    }
}
