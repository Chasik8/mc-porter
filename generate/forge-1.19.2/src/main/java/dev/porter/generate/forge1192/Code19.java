package dev.porter.generate.forge1192;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;
import tools.jackson.databind.JsonNode;

/** IR → Forge 1.19.2 Java sources (DeferredRegister registration, bases + logic stubs). */
final class Code19 {
    static final Map<String, String> VANILLA_TABS = Map.ofEntries(Map.entry("building_blocks", "TAB_BUILDING_BLOCKS"),
            Map.entry("decorations", "TAB_DECORATIONS"), Map.entry("redstone", "TAB_REDSTONE"),
            Map.entry("transportation", "TAB_TRANSPORTATION"), Map.entry("misc", "TAB_MISC"), Map.entry("food", "TAB_FOOD"),
            Map.entry("tools", "TAB_TOOLS"), Map.entry("combat", "TAB_COMBAT"), Map.entry("brewing", "TAB_BREWING"),
            Map.entry("materials", "TAB_MATERIALS"), Map.entry("search", "TAB_SEARCH"));
    static final Map<String, String> ATTRIBUTES = Map.ofEntries(Map.entry("minecraft:generic.max_health", "MAX_HEALTH"),
            Map.entry("minecraft:generic.follow_range", "FOLLOW_RANGE"),
            Map.entry("minecraft:generic.knockback_resistance", "KNOCKBACK_RESISTANCE"),
            Map.entry("minecraft:generic.movement_speed", "MOVEMENT_SPEED"), Map.entry("minecraft:generic.flying_speed", "FLYING_SPEED"),
            Map.entry("minecraft:generic.attack_damage", "ATTACK_DAMAGE"),
            Map.entry("minecraft:generic.attack_knockback", "ATTACK_KNOCKBACK"),
            Map.entry("minecraft:generic.attack_speed", "ATTACK_SPEED"), Map.entry("minecraft:generic.armor", "ARMOR"),
            Map.entry("minecraft:generic.armor_toughness", "ARMOR_TOUGHNESS"), Map.entry("minecraft:generic.luck", "LUCK"));

    private final Names19 n;
    private final GenContext ctx;
    private final TypeSpec.Builder reg;
    private final MethodSpec.Builder regInit;
    private final Map<String, String> tabFields = new TreeMap<>();

    Code19(Names19 n) {
        this.n = n;
        this.ctx = n.ctx;
        reg = TypeSpec.classBuilder(n.registries).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("DeferredRegisters of everything the IR declares. Call {@link #register} from the mod constructor.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(String.class, "MOD_ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", ctx.options.modId()).build());
        regInit = MethodSpec.methodBuilder("register").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter(T.EVENT_BUS, "bus");
    }

    static TypeName ro(TypeName t) {
        return ParameterizedTypeName.get(T.REGISTRY_OBJECT, t);
    }

    private void deferred(String field, TypeName type, CodeBlock registry) {
        reg.addField(FieldSpec.builder(ParameterizedTypeName.get(T.DEFERRED_REGISTER, type), field, Modifier.PUBLIC, Modifier.STATIC,
                Modifier.FINAL).initializer("$T.create($L, MOD_ID)", T.DEFERRED_REGISTER, registry).build());
        regInit.addStatement("$L.register(bus)", field);
    }

    void run() {
        deferred("BLOCKS", T.BLOCK, CodeBlock.of("$T.BLOCKS", T.FORGE_REGISTRIES));
        deferred("ITEMS", T.ITEM, CodeBlock.of("$T.ITEMS", T.FORGE_REGISTRIES));
        deferred("BLOCK_ENTITY_TYPES", ParameterizedTypeName.get(T.BLOCK_ENTITY_TYPE, WildcardTypeName.subtypeOf(Object.class)),
                CodeBlock.of("$T.BLOCK_ENTITY_TYPES", T.FORGE_REGISTRIES));
        deferred("MENU_TYPES", ParameterizedTypeName.get(T.MENU_TYPE, WildcardTypeName.subtypeOf(Object.class)),
                CodeBlock.of("$T.MENU_TYPES", T.FORGE_REGISTRIES));
        deferred("FLUID_TYPES", T.FLUID_TYPE, CodeBlock.of("$T.FLUID_TYPES", T.FORGE_REGISTRY_KEYS));
        deferred("FLUIDS", T.FLUID, CodeBlock.of("$T.FLUIDS", T.FORGE_REGISTRIES));
        deferred("MOB_EFFECTS", T.MOB_EFFECT, CodeBlock.of("$T.MOB_EFFECTS", T.FORGE_REGISTRIES));
        deferred("SOUND_EVENTS", T.SOUND_EVENT, CodeBlock.of("$T.SOUND_EVENTS", T.FORGE_REGISTRIES));
        tabs();
        for (IrNode.Fluid f : ctx.ir.all(IrNode.Fluid.class)) {
            fluid(f);
        }
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (fluidOfBlock(b.id()).isEmpty()) {
                block(b);
            }
        }
        for (IrNode.BlockEntity be : ctx.ir.all(IrNode.BlockEntity.class)) {
            blockEntity(be);
        }
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            item(i);
        }
        for (IrNode.Menu m : ctx.ir.all(IrNode.Menu.class)) {
            menu(m);
            screen(m);
        }
        for (IrNode.Effect e : ctx.ir.all(IrNode.Effect.class)) {
            effect(e);
        }
        for (IrNode.Sound s : ctx.ir.all(IrNode.Sound.class)) {
            reg.addField(FieldSpec.builder(ro(T.SOUND_EVENT), n.sound(s.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("SOUND_EVENTS.register($S, () -> new $T(new $T($S)))", n.path(s.id()), T.SOUND_EVENT, T.RL, s.id())
                    .build());
        }
        reg.addMethod(regInit.build());
        ctx.out.addJava(n.registries.packageName(), reg.build());
        if (!ctx.ir.all(IrNode.Packet.class).isEmpty()) {
            new Network19(n).run();
        }
        if (!ctx.ir.all(IrNode.Multiblock.class).isEmpty()) {
            new Multiblock19(n).run();
        }
        client();
        if (!ctx.options.embed()) {
            mod();
        }
    }

    Optional<IrNode.Fluid> fluidOfBlock(String blockId) {
        return ctx.ir.all(IrNode.Fluid.class).stream().filter(f -> blockId.equals(f.block())).findFirst();
    }

    Optional<IrNode.Fluid> fluidOfBucket(String itemId) {
        return ctx.ir.all(IrNode.Fluid.class).stream().filter(f -> itemId.equals(f.bucket())).findFirst();
    }

    // ---- creative tabs ---------------------------------------------------------------------------

    private void tabs() {
        Map<String, String> icon = new TreeMap<>();
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (i.creativeTab() != null) {
                icon.putIfAbsent(i.creativeTab(), n.item(i.id()));
            }
        }
        for (Map.Entry<String, String> e : icon.entrySet()) {
            if (vanillaTab(e.getKey()) != null) {
                continue;
            }
            String f = "TAB_" + Names.constant(Names.path(e.getKey()));
            tabFields.put(e.getKey(), f);
            TypeSpec anon = TypeSpec.anonymousClassBuilder("$S", Names.path(e.getKey()).replace('/', '.')).superclass(T.CREATIVE_TAB)
                    .addMethod(MethodSpec.methodBuilder("makeIcon").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .returns(T.ITEM_STACK).addStatement("return new $T($L.get())", T.ITEM_STACK, e.getValue()).build()).build();
            reg.addField(FieldSpec.builder(T.CREATIVE_TAB, f, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", anon).build());
        }
    }

    private String vanillaTab(String tab) {
        if (tab.contains(":") && !"minecraft".equals(Names.namespace(tab))) {
            return null;
        }
        return VANILLA_TABS.get(Names.path(tab));
    }

    private CodeBlock tab(String tab) {
        if (tab == null) {
            return null;
        }
        String v = vanillaTab(tab);
        if (v != null) {
            return CodeBlock.of("$T.$L", T.CREATIVE_TAB, v);
        }
        String f = tabFields.get(tab);
        return f == null ? null : CodeBlock.of("$T.$L", n.registries, f);
    }

    // ---- blocks ----------------------------------------------------------------------------------

    record Prop(String name, String type, List<String> values, String def, String constant, TypeName enumType) {
    }

    private List<Prop> props(IrNode.Block b, TypeSpec.Builder t) {
        List<Prop> out = new ArrayList<>();
        if (b.stateProperties() == null) {
            return out;
        }
        for (IrNode.Block.StateProperty p : b.stateProperties()) {
            List<String> values = p.values() != null && !p.values().isEmpty() ? p.values()
                    : "boolean".equals(p.type()) ? List.of("false", "true")
                    : "direction".equals(p.type()) ? List.of("down", "up", "north", "south", "west", "east") : List.of();
            String c = Names.constant(p.name());
            String def = p.defaultValue() != null ? p.defaultValue() : values.isEmpty() ? null : values.get(0);
            switch (p.type()) {
                case "boolean" -> t.addField(FieldSpec.builder(T.BOOLEAN_PROPERTY, c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.create($S)", T.BOOLEAN_PROPERTY, p.name()).build());
                case "integer" -> {
                    List<Integer> ints = values.stream().map(Integer::parseInt).sorted().toList();
                    int min = ints.get(0);
                    int max = ints.get(ints.size() - 1);
                    if (max - min + 1 != ints.size()) {
                        ctx.warn("int-property-gaps", b.id() + "#" + p.name(), "values " + values + " are not contiguous; range " + min + ".." + max);
                    }
                    t.addField(FieldSpec.builder(T.INTEGER_PROPERTY, c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$T.create($S, $L, $L)", T.INTEGER_PROPERTY, p.name(), min, max).build());
                }
                case "direction" -> t.addField(FieldSpec.builder(T.DIRECTION_PROPERTY, c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.create($S, $L)", T.DIRECTION_PROPERTY, p.name(), String.join(", ",
                                values.stream().map(v -> CodeBlock.of("$T.$L", T.DIRECTION, v.toUpperCase(Locale.ROOT)).toString()).toList()))
                        .build());
                default -> {
                    String en = Names.pascal(p.name());
                    ClassName et = n.blockBase(b.id()).nestedClass(en);
                    TypeSpec.Builder e = TypeSpec.enumBuilder(en).addModifiers(Modifier.PUBLIC).addSuperinterface(T.STRING_REPRESENTABLE)
                            .addField(String.class, "serializedName", Modifier.PRIVATE, Modifier.FINAL)
                            .addMethod(MethodSpec.constructorBuilder().addParameter(String.class, "serializedName")
                                    .addStatement("this.serializedName = serializedName").build())
                            .addMethod(MethodSpec.methodBuilder("getSerializedName").addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC).returns(String.class).addStatement("return serializedName").build());
                    for (String v : values) {
                        e.addEnumConstant(Names.constant(v), TypeSpec.anonymousClassBuilder("$S", v).build());
                    }
                    t.addType(e.build());
                    t.addField(FieldSpec.builder(ParameterizedTypeName.get(T.ENUM_PROPERTY, et), c, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$T.create($S, $T.class)", T.ENUM_PROPERTY, p.name(), et).build());
                    out.add(new Prop(p.name(), p.type(), values, def, c, et));
                    continue;
                }
            }
            out.add(new Prop(p.name(), p.type(), values, def, c, null));
        }
        return out;
    }

    private CodeBlock value(Prop p, String v) {
        return switch (p.type()) {
            case "boolean" -> CodeBlock.of("$L", Boolean.parseBoolean(v));
            case "integer" -> CodeBlock.of("$L", Integer.parseInt(v));
            case "direction" -> CodeBlock.of("$T.$L", T.DIRECTION, v.toUpperCase(Locale.ROOT));
            default -> CodeBlock.of("$T.$L", p.enumType(), Names.constant(v));
        };
    }

    CodeBlock properties(IrNode.Block b) {
        String mat = b.material() == null ? "STONE" : b.material().toUpperCase(Locale.ROOT);
        if (!Names19.MATERIALS.contains(mat)) {
            ctx.warn("material-unknown", b.id(), "material " + b.material() + " is not a 1.19.2 Material; STONE used");
            mat = "STONE";
        }
        CodeBlock.Builder c = CodeBlock.builder().add("$T.of($T.$L)", T.BLOCK_PROPERTIES, T.MATERIAL, mat);
        if (b.hardness() != null || b.resistance() != null) {
            double h = b.hardness() == null ? 0 : b.hardness();
            c.add(".strength($LF, $LF)", (float) h, (float) (b.resistance() == null ? h : b.resistance().doubleValue()));
        }
        if (b.soundType() != null) {
            String s = b.soundType().toUpperCase(Locale.ROOT);
            if (Names19.SOUNDS.contains(s)) {
                c.add(".sound($T.$L)", T.SOUND_TYPE, s);
            } else {
                ctx.warn("sound-type-unknown", b.id(), "soundType " + b.soundType() + " is not a 1.19.2 SoundType; default kept");
            }
        }
        if (b.lightLevel() != null && b.lightLevel() > 0) {
            c.add(".lightLevel(state -> $L)", b.lightLevel());
        }
        if (Boolean.TRUE.equals(b.requiresTool())) {
            c.add(".requiresCorrectToolForDrops()");
        }
        return c.build();
    }

    private void block(IrNode.Block b) {
        ClassName base = n.blockBase(b.id());
        Optional<IrNode.BlockEntity> be = blockEntity(b);
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.BLOCK)
                .addJavadoc("Block $L.\n", b.id());
        List<Prop> props = props(b, t);
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addStatement("super($L)", properties(b));
        if (!props.isEmpty()) {
            CodeBlock.Builder def = CodeBlock.builder().add("stateDefinition.any()");
            for (Prop p : props) {
                if (p.def() != null) {
                    def.add(".setValue($L, $L)", p.constant(), value(p, p.def()));
                }
            }
            ctor.addStatement("registerDefaultState($L)", def.build());
            t.addMethod(MethodSpec.methodBuilder("createBlockStateDefinition").addAnnotation(Override.class)
                    .addModifiers(Modifier.PROTECTED)
                    .addParameter(ParameterizedTypeName.get(T.STATE_DEFINITION_BUILDER, T.BLOCK, T.BLOCK_STATE), "builder")
                    .addStatement("builder.add($L)", String.join(", ", props.stream().map(Prop::constant).toList())).build());
        }
        t.addMethod(ctor.build());
        if (be.isPresent()) {
            ClassName beCls = n.beClass(be.get());
            t.addSuperinterface(T.ENTITY_BLOCK);
            t.addMethod(MethodSpec.methodBuilder("newBlockEntity").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(T.BLOCK_ENTITY).addParameter(T.BLOCK_POS, "pos").addParameter(T.BLOCK_STATE, "state")
                    .addStatement("return new $T(pos, state)", beCls).build());
            String ticking = be.get().ticking();
            if (ticking != null && !"none".equals(ticking)) {
                TypeVariableName tv = TypeVariableName.get("T", T.BLOCK_ENTITY);
                String side = switch (ticking) {
                    case "server" -> "level.isClientSide ? null : ";
                    case "client" -> "!level.isClientSide ? null : ";
                    default -> "";
                };
                t.addMethod(MethodSpec.methodBuilder("getTicker").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .addTypeVariable(tv).returns(ParameterizedTypeName.get(T.BLOCK_ENTITY_TICKER, tv))
                        .addParameter(T.LEVEL, "level").addParameter(T.BLOCK_STATE, "state")
                        .addParameter(ParameterizedTypeName.get(T.BLOCK_ENTITY_TYPE, tv), "type")
                        .addStatement("return $Ltype == $T.$L.get() ? (lvl, pos, st, be) -> (($T) be).tick() : null", side,
                                n.registries, n.be(be.get().id()), n.beBase(be.get().id())).build());
            }
            if (menuOf(be.get()).isPresent()) {
                t.addMethod(MethodSpec.methodBuilder("use").addAnnotation(Override.class)
                        .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "deprecation").build())
                        .addModifiers(Modifier.PUBLIC).returns(T.INTERACTION_RESULT).addParameter(T.BLOCK_STATE, "state")
                        .addParameter(T.LEVEL, "level").addParameter(T.BLOCK_POS, "pos").addParameter(T.PLAYER, "player")
                        .addParameter(T.INTERACTION_HAND, "hand").addParameter(T.BLOCK_HIT_RESULT, "hit")
                        .beginControlFlow("if (!level.isClientSide && player instanceof $T sp && level.getBlockEntity(pos) instanceof $T provider)",
                                T.SERVER_PLAYER, T.MENU_PROVIDER)
                        .addStatement("$T.openScreen(sp, provider, pos)", T.NETWORK_HOOKS).endControlFlow()
                        .addStatement("return $T.sidedSuccess(level.isClientSide)", T.INTERACTION_RESULT).build());
            }
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (b.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Names19.cls(b.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of $L.\n", b.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build()).build(), "block " + b.id());
        }
        reg.addField(FieldSpec.builder(ro(T.BLOCK), n.block(b.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("BLOCKS.register($S, $T::new)", n.path(b.id()), n.blockClass(b)).build());
    }

    Optional<IrNode.BlockEntity> blockEntity(IrNode.Block b) {
        if (b.blockEntity() != null && ctx.ir.get(IrNode.BlockEntity.class, b.blockEntity()).isPresent()) {
            return ctx.ir.get(IrNode.BlockEntity.class, b.blockEntity());
        }
        return ctx.ir.all(IrNode.BlockEntity.class).stream().filter(e -> e.blocks().contains(b.id())).findFirst();
    }

    Optional<IrNode.Menu> menuOf(IrNode.BlockEntity be) {
        if (be.menu() != null && ctx.ir.get(IrNode.Menu.class, be.menu()).isPresent()) {
            return ctx.ir.get(IrNode.Menu.class, be.menu());
        }
        return ctx.ir.all(IrNode.Menu.class).stream().filter(m -> be.id().equals(m.blockEntity())).findFirst();
    }

    Optional<IrNode.BlockEntity> blockEntityOf(IrNode.Menu m) {
        if (m.blockEntity() != null && ctx.ir.get(IrNode.BlockEntity.class, m.blockEntity()).isPresent()) {
            return ctx.ir.get(IrNode.BlockEntity.class, m.blockEntity());
        }
        return ctx.ir.all(IrNode.BlockEntity.class).stream().filter(b -> m.id().equals(b.menu())).findFirst();
    }

    static int dataCount(IrNode.Menu m) {
        return m.dataSlots() == null ? 0 : m.dataSlots().stream().mapToInt(d -> d.index() + 1).max().orElse(0);
    }

    static int slotCount(IrNode.Menu m) {
        return m.slots().stream().mapToInt(s -> s.index() + 1).max().orElse(0);
    }

    // ---- block entities --------------------------------------------------------------------------

    private void blockEntity(IrNode.BlockEntity be) {
        ClassName base = n.beBase(be.id());
        ClassName cls = n.beClass(be);
        Optional<IrNode.Menu> menu = menuOf(be);
        int inv = be.inventorySize() != null ? be.inventorySize() : menu.map(Code19::slotCount).orElse(0);
        int data = menu.map(Code19::dataCount).orElse(0);
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.BLOCK_ENTITY)
                .addJavadoc("Block entity $L. Logic: {@code $L} extends this class.\n", be.id(), be.logicClass())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.BLOCK_POS, "pos")
                        .addParameter(T.BLOCK_STATE, "state")
                        .addStatement("super($T.$L.get(), pos, state)", n.registries, n.be(be.id())).build());
        t.addMethod(MethodSpec.methodBuilder("tick").addModifiers(Modifier.PUBLIC)
                .beginControlFlow("if (level == null)").addStatement("return").endControlFlow()
                .beginControlFlow("if (level.isClientSide)").addStatement("clientTick()").nextControlFlow("else")
                .addStatement("serverTick()").endControlFlow().build());
        t.addMethod(MethodSpec.methodBuilder("serverTick").addModifiers(Modifier.PROTECTED).addJavadoc("Hook: server tick.\n").build());
        t.addMethod(MethodSpec.methodBuilder("clientTick").addModifiers(Modifier.PROTECTED).addJavadoc("Hook: client tick.\n").build());
        if (data > 0) {
            t.addField(FieldSpec.builder(ArrayTypeName.of(TypeName.INT), "dataSlots", Modifier.PROTECTED, Modifier.FINAL)
                    .initializer("new int[$L]", data).build());
            TypeSpec cd = TypeSpec.anonymousClassBuilder("").addSuperinterface(T.CONTAINER_DATA)
                    .addMethod(MethodSpec.methodBuilder("get").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .returns(TypeName.INT).addParameter(TypeName.INT, "index").addStatement("return dataSlots[index]").build())
                    .addMethod(MethodSpec.methodBuilder("set").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .addParameter(TypeName.INT, "index").addParameter(TypeName.INT, "value")
                            .addStatement("dataSlots[index] = value").build())
                    .addMethod(MethodSpec.methodBuilder("getCount").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                            .returns(TypeName.INT).addStatement("return dataSlots.length").build())
                    .build();
            t.addField(FieldSpec.builder(T.CONTAINER_DATA, "data", Modifier.PUBLIC, Modifier.FINAL).initializer("$L", cd).build());
        }
        MethodSpec.Builder load = MethodSpec.methodBuilder("load").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.COMPOUND, "tag").addStatement("super.load(tag)");
        MethodSpec.Builder save = MethodSpec.methodBuilder("saveAdditional").addAnnotation(Override.class).addModifiers(Modifier.PROTECTED)
                .addParameter(T.COMPOUND, "tag").addStatement("super.saveAdditional(tag)");
        if (inv > 0) {
            t.addSuperinterface(T.CONTAINER);
            t.addField(FieldSpec.builder(ParameterizedTypeName.get(T.NON_NULL_LIST, T.ITEM_STACK), "items", Modifier.PROTECTED, Modifier.FINAL)
                    .initializer("$T.withSize($L, $T.EMPTY)", T.NON_NULL_LIST, inv, T.ITEM_STACK).build());
            load.addStatement("$T.loadAllItems(tag, items)", T.CONTAINER_HELPER);
            save.addStatement("$T.saveAllItems(tag, items)", T.CONTAINER_HELPER);
            if (be.nbt() != null && be.nbt().stream().anyMatch(k -> "itemStacks".equals(k.type()) && !"Items".equals(k.key()))) {
                ctx.warn("nbt-items-key", be.id(), "inventory NBT key is not \"Items\"; ContainerHelper writes \"Items\"");
            }
            t.addMethod(over("getContainerSize", TypeName.INT).addStatement("return items.size()").build());
            t.addMethod(over("isEmpty", TypeName.BOOLEAN).addStatement("return items.stream().allMatch($T::isEmpty)", T.ITEM_STACK).build());
            t.addMethod(over("getItem", T.ITEM_STACK).addParameter(TypeName.INT, "slot").addStatement("return items.get(slot)").build());
            t.addMethod(over("removeItem", T.ITEM_STACK).addParameter(TypeName.INT, "slot").addParameter(TypeName.INT, "count")
                    .addStatement("$T s = $T.removeItem(items, slot, count)", T.ITEM_STACK, T.CONTAINER_HELPER)
                    .beginControlFlow("if (!s.isEmpty())").addStatement("setChanged()").endControlFlow().addStatement("return s").build());
            t.addMethod(over("removeItemNoUpdate", T.ITEM_STACK).addParameter(TypeName.INT, "slot")
                    .addStatement("return $T.takeItem(items, slot)", T.CONTAINER_HELPER).build());
            t.addMethod(over("setItem", TypeName.VOID).addParameter(TypeName.INT, "slot").addParameter(T.ITEM_STACK, "stack")
                    .addStatement("items.set(slot, stack)")
                    .beginControlFlow("if (stack.getCount() > getMaxStackSize())").addStatement("stack.setCount(getMaxStackSize())").endControlFlow()
                    .addStatement("setChanged()").build());
            t.addMethod(over("stillValid", TypeName.BOOLEAN).addParameter(T.PLAYER, "player")
                    .addStatement("return level != null && level.getBlockEntity(worldPosition) == this"
                            + " && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D")
                    .build());
            t.addMethod(over("clearContent", TypeName.VOID).addStatement("items.clear()").build());
            if (menu.isPresent()) {
                t.addMethod(over("canPlaceItem", TypeName.BOOLEAN).addParameter(TypeName.INT, "slot").addParameter(T.ITEM_STACK, "stack")
                        .addStatement("return $T.accepts(slot, stack)", n.menuBase(menu.get().id())).build());
            }
        }
        t.addMethod(load.build()).addMethod(save.build());
        if (menu.isPresent()) {
            IrNode.Menu m = menu.get();
            t.addSuperinterface(T.MENU_PROVIDER);
            t.addMethod(over("getDisplayName", T.COMPONENT).addStatement("return $T.translatable($S)", T.COMPONENT,
                    m.title() != null ? m.title() : "container." + ctx.options.modId() + "." + Names.path(m.id())).build());
            t.addMethod(over("createMenu", T.MENU).addParameter(TypeName.INT, "id").addParameter(T.INVENTORY, "inventory")
                    .addParameter(T.PLAYER, "player").addStatement("return new $T(id, inventory, this)", n.menuClass(m)).build());
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (ctx.options.stubs()) {
            ctx.out.addStub(cls.packageName(), TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of block entity $L.\n", be.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.BLOCK_POS, "pos")
                            .addParameter(T.BLOCK_STATE, "state").addStatement("super(pos, state)").build()).build(), "blockEntity " + be.id());
        }
        List<CodeBlock> blocks = new ArrayList<>();
        for (String b : be.blocks()) {
            if (ctx.ir.get(IrNode.Block.class, b).isPresent()) {
                blocks.add(CodeBlock.of("$L.get()", n.block(b)));
            } else {
                ctx.warn("block-entity-block-unknown", be.id(), "block " + b + " is not in the IR");
            }
        }
        TypeName type = ParameterizedTypeName.get(T.BLOCK_ENTITY_TYPE, cls);
        reg.addField(FieldSpec.builder(ro(type), n.be(be.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "ConstantConditions").build())
                .initializer("BLOCK_ENTITY_TYPES.register($S, () -> $T.Builder.of($T::new$L).build(null))", n.path(be.id()),
                        T.BLOCK_ENTITY_TYPE, cls, blocks.isEmpty() ? "" : ", " + CodeBlock.join(blocks, ", "))
                .build());
    }

    private static MethodSpec.Builder over(String name, TypeName ret) {
        MethodSpec.Builder b = MethodSpec.methodBuilder(name).addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        return TypeName.VOID.equals(ret) ? b : b.returns(ret);
    }

    // ---- items -----------------------------------------------------------------------------------

    private void item(IrNode.Item i) {
        CodeBlock.Builder props = CodeBlock.builder().add("new $T()", T.ITEM_PROPERTIES);
        CodeBlock tab = tab(i.creativeTab());
        if (tab != null) {
            props.add(".tab($L)", tab);
        }
        if (i.maxStackSize() != null && i.maxStackSize() != 64) {
            props.add(".stacksTo($L)", i.maxStackSize());
        }
        if (i.maxDamage() != null && i.maxDamage() > 0) {
            props.add(".durability($L)", i.maxDamage());
        }
        if (i.rarity() != null && !"common".equals(i.rarity())) {
            props.add(".rarity($T.$L)", T.RARITY, i.rarity().toUpperCase(Locale.ROOT));
        }
        if (i.food() != null) {
            IrNode.Item.Food f = i.food();
            CodeBlock.Builder food = CodeBlock.builder().add("new $T()", T.FOOD_BUILDER);
            if (f.nutrition() != null) {
                food.add(".nutrition($L)", f.nutrition());
            }
            if (f.saturation() != null) {
                food.add(".saturationMod($LF)", f.saturation().floatValue());
            }
            if (Boolean.TRUE.equals(f.alwaysEdible())) {
                food.add(".alwaysEat()");
            }
            if (f.effects() != null) {
                for (IrNode.Item.FoodEffect e : f.effects()) {
                    food.add(".effect(() -> new $T($L, $L, $L), $LF)", T.MOB_EFFECT_INSTANCE, effectRef(e.effect()),
                            e.duration() == null ? 0 : e.duration(), e.amplifier() == null ? 0 : e.amplifier(),
                            (float) (e.probability() == null ? 1.0 : e.probability()));
                }
            }
            props.add(".food($L.build())", food.build());
        }
        Optional<IrNode.Fluid> bucketOf = fluidOfBucket(i.id());
        CodeBlock init;
        ClassName base = n.itemBase(i.id());
        if (i.placesBlock() != null) {
            if (ctx.ir.get(IrNode.Block.class, i.placesBlock()).isEmpty()) {
                ctx.warn("block-item-unknown-block", i.id(), "placed block " + i.placesBlock() + " is not in the IR; item skipped");
                return;
            }
            if (i.logicClass() != null && ctx.options.stubs()) {
                ClassName logic = Names19.cls(i.logicClass());
                ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(T.BLOCK_ITEM)
                        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.BLOCK, "block")
                                .addParameter(T.ITEM_PROPERTIES, "properties").addStatement("super(block, properties)").build())
                        .build(), "blockItem " + i.id());
            }
            init = CodeBlock.of("() -> new $T($L.get(), $L)", i.logicClass() != null ? Names19.cls(i.logicClass()) : T.BLOCK_ITEM,
                    n.block(i.placesBlock()), props.build());
        } else if (bucketOf.isPresent()) {
            init = CodeBlock.of("() -> new $T($L, $L)", T.BUCKET_ITEM, n.fluid(bucketOf.get().id()), props.build());
            ctx.info("bucket-item", i.id(), "bucket of fluid " + bucketOf.get().id() + " generated as BucketItem");
        } else {
            TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.ITEM)
                    .addJavadoc("Item $L.\n", i.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.ITEM_PROPERTIES, "properties")
                            .addStatement("super(properties)").build());
            if (i.food() != null && Boolean.TRUE.equals(i.food().drink())) {
                t.addMethod(over("getUseAnimation", T.USE_ANIM).addParameter(T.ITEM_STACK, "stack").addStatement("return $T.DRINK", T.USE_ANIM).build());
            }
            if (i.containerItem() != null) {
                t.addMethod(over("hasCraftingRemainingItem", TypeName.BOOLEAN).addParameter(T.ITEM_STACK, "stack").addStatement("return true").build());
                t.addMethod(over("getCraftingRemainingItem", T.ITEM_STACK).addParameter(T.ITEM_STACK, "stack")
                        .addStatement("$T item = $T.ITEMS.getValue(new $T($S))", T.ITEM, T.FORGE_REGISTRIES, T.RL, i.containerItem())
                        .addStatement("return item == null ? $T.EMPTY : new $T(item)", T.ITEM_STACK, T.ITEM_STACK).build());
            }
            ctx.out.addJava(base.packageName(), t.build());
            if (i.logicClass() != null && ctx.options.stubs()) {
                ClassName logic = Names19.cls(i.logicClass());
                ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.ITEM_PROPERTIES, "properties")
                                .addStatement("super(properties)").build()).build(), "item " + i.id());
            }
            init = CodeBlock.of("() -> new $T($L)", n.itemClass(i), props.build());
        }
        reg.addField(FieldSpec.builder(ro(T.ITEM), n.item(i.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("ITEMS.register($S, $L)", n.path(i.id()), init).build());
    }

    private CodeBlock effectRef(String id) {
        if (ctx.ir.get(IrNode.Effect.class, id).isPresent()) {
            return CodeBlock.of("$T.$L.get()", n.registries, n.effect(id));
        }
        return CodeBlock.of("$T.MOB_EFFECTS.getValue(new $T($S))", T.FORGE_REGISTRIES, T.RL, id);
    }

    // ---- fluids ----------------------------------------------------------------------------------

    private void fluid(IrNode.Fluid f) {
        ClassName type = n.fluidType(f.id());
        CodeBlock.Builder p = CodeBlock.builder().add("$T.create()", T.FLUID_TYPE_PROPERTIES);
        if (f.density() != null) {
            p.add(".density($L)", f.density());
        }
        if (f.viscosity() != null) {
            p.add(".viscosity($L)", f.viscosity());
        }
        if (f.temperature() != null) {
            p.add(".temperature($L)", f.temperature());
        }
        if (f.luminosity() != null) {
            p.add(".lightLevel($L)", f.luminosity());
        }
        TypeSpec.Builder ext = TypeSpec.anonymousClassBuilder("").addSuperinterface(T.CLIENT_FLUID_EXT);
        if (f.stillTexture() != null) {
            ext.addMethod(over("getStillTexture", T.RL).addStatement("return new $T($S)", T.RL, f.stillTexture()).build());
        }
        if (f.flowingTexture() != null) {
            ext.addMethod(over("getFlowingTexture", T.RL).addStatement("return new $T($S)", T.RL, f.flowingTexture()).build());
        }
        if (f.color() != null) {
            String hex = f.color().substring(1);
            long argb = hex.length() == 8 ? Long.parseLong(hex.substring(6) + hex.substring(0, 6), 16) : Long.parseLong("FF" + hex, 16);
            ext.addMethod(over("getTintColor", TypeName.INT).addStatement("return 0x$L", String.format("%08X", argb)).build());
        }
        TypeSpec t = TypeSpec.classBuilder(type).addModifiers(Modifier.PUBLIC).superclass(T.FLUID_TYPE)
                .addJavadoc("Fluid type of $L.\n", f.id())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addStatement("super($L)", p.build()).build())
                .addMethod(over("initializeClient", TypeName.VOID)
                        .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.function.Consumer.class), T.CLIENT_FLUID_EXT), "consumer")
                        .addStatement("consumer.accept($L)", ext.build()).build())
                .build();
        ctx.out.addJava(type.packageName(), t);
        if (f.logicClass() != null) {
            ctx.info("fluid-logic", f.id(), "fluid logic " + f.logicClass() + " not referenced; ForgeFlowingFluid used");
        }
        String path = n.path(f.id());
        reg.addField(FieldSpec.builder(ro(T.FLUID_TYPE), n.fluidTypeField(f.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("FLUID_TYPES.register($S, $T::new)", path, type).build());
        reg.addField(FieldSpec.builder(ro(T.FLOWING_FLUID), n.fluid(f.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("FLUIDS.register($S, () -> new $T($L()))", path, T.FORGE_FLOWING_SOURCE, propsMethod(f)).build());
        reg.addField(FieldSpec.builder(ro(T.FLOWING_FLUID), n.flowing(f.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("FLUIDS.register($S, () -> new $T($L()))", "flowing_" + path, T.FORGE_FLOWING_FLOWING, propsMethod(f)).build());
        ctx.info("fluid-flowing-id", f.id(), "flowing variant registered as " + Names.namespace(f.id()) + ":flowing_" + path);
        CodeBlock.Builder props = CodeBlock.builder().add("new $T($L, $L, $L)", T.FORGE_FLOWING_PROPERTIES, n.fluidTypeField(f.id()),
                n.fluid(f.id()), n.flowing(f.id()));
        if (f.bucket() != null && ctx.ir.get(IrNode.Item.class, f.bucket()).isPresent()) {
            props.add(".bucket($L)", n.item(f.bucket()));
        }
        if (f.block() != null) {
            props.add(".block($L)", n.block(f.block()));
            reg.addField(FieldSpec.builder(ro(T.LIQUID_BLOCK), n.block(f.block()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("BLOCKS.register($S, () -> new $T($L, $T.of($T.WATER).noCollission().strength(100.0F).noLootTable()))",
                            n.path(f.block()), T.LIQUID_BLOCK, n.fluid(f.id()), T.BLOCK_PROPERTIES, T.MATERIAL).build());
        }
        reg.addMethod(MethodSpec.methodBuilder(propsMethod(f)).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(T.FORGE_FLOWING_PROPERTIES).addStatement("return $L", props.build()).build());
    }

    private String propsMethod(IrNode.Fluid f) {
        return Names.field(Names.path(f.id())) + "FluidProperties";
    }

    // ---- effects ---------------------------------------------------------------------------------

    private void effect(IrNode.Effect e) {
        ClassName base = n.effectBase(e.id());
        String cat = switch (e.category()) {
            case "harmful" -> "HARMFUL";
            case "neutral" -> "NEUTRAL";
            default -> "BENEFICIAL";
        };
        int color = e.color() == null ? 0xFFFFFF : Integer.parseInt(e.color().substring(1, 7), 16);
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addStatement("super($T.$L, 0x$L)", T.MOB_EFFECT_CATEGORY, cat, String.format("%06X", color));
        if (e.attributeModifiers() != null) {
            for (IrNode.Effect.AttributeModifier a : e.attributeModifiers()) {
                String attr = ATTRIBUTES.get(a.attribute());
                String op = a.operation().toUpperCase(Locale.ROOT);
                if (attr != null) {
                    ctor.addStatement("addAttributeModifier($T.$L, $S, $LD, $T.$L)", T.ATTRIBUTES, attr, a.uuid(), a.amount(), T.ATTRIBUTE_OPERATION, op);
                } else {
                    ctor.addStatement("addAttributeModifier($T.requireNonNull($T.ATTRIBUTES.getValue(new $T($S))), $S, $LD, $T.$L)",
                            java.util.Objects.class, T.FORGE_REGISTRIES, T.RL, a.attribute(), a.uuid(), a.amount(), T.ATTRIBUTE_OPERATION, op);
                }
            }
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.MOB_EFFECT)
                .addJavadoc("Effect $L.\n", e.id()).addMethod(ctor.build());
        if (Boolean.TRUE.equals(e.instant())) {
            t.addMethod(over("isInstantenous", TypeName.BOOLEAN).addStatement("return true").build());
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (e.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Names19.cls(e.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build()).build(), "effect " + e.id());
        }
        reg.addField(FieldSpec.builder(ro(T.MOB_EFFECT), n.effect(e.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("MOB_EFFECTS.register($S, $T::new)", n.path(e.id()), n.effectClass(e)).build());
    }

    // ---- menus & screens -------------------------------------------------------------------------

    private void menu(IrNode.Menu m) {
        ClassName base = n.menuBase(m.id());
        ClassName cls = n.menuClass(m);
        int slots = slotCount(m);
        int data = dataCount(m);
        Optional<IrNode.BlockEntity> be = blockEntityOf(m);
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.MENU)
                .addJavadoc("Menu $L: $L machine slots, $L data slots.\n", m.id(), slots, data)
                .addField(FieldSpec.builder(TypeName.INT, "MACHINE_SLOTS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", slots).build())
                .addField(T.CONTAINER, "container", Modifier.PROTECTED, Modifier.FINAL)
                .addField(T.CONTAINER_DATA, "data", Modifier.PROTECTED, Modifier.FINAL)
                .addField(T.BLOCK_ENTITY, "blockEntity", Modifier.PROTECTED, Modifier.FINAL);
        t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                .addParameter(T.INVENTORY, "inventory").addParameter(T.FRIENDLY_BUF, "buf")
                .addJavadoc("Client side (IForgeMenuType): the server wrote the block position.\n")
                .addStatement("this(id, inventory, inventory.player.level.getBlockEntity(buf.readBlockPos()))").build());
        t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                .addParameter(T.INVENTORY, "inventory").addParameter(T.BLOCK_ENTITY, "blockEntity")
                .addStatement("this(id, inventory, containerOf(blockEntity), dataOf(blockEntity), blockEntity)").build());
        MethodSpec.Builder full = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                .addParameter(T.INVENTORY, "inventory").addParameter(T.CONTAINER, "container").addParameter(T.CONTAINER_DATA, "data")
                .addParameter(T.BLOCK_ENTITY, "blockEntity")
                .addStatement("super($T.$L.get(), id)", n.registries, n.menu(m.id()))
                .addStatement("this.container = container").addStatement("this.data = data").addStatement("this.blockEntity = blockEntity")
                .addStatement("checkContainerSize(container, $L)", slots);
        for (IrNode.Menu.Slot s : m.slots()) {
            full.addStatement("addSlot(new MachineSlot(container, $L, $L, $L))", s.index(), s.x(), s.y());
        }
        if (m.playerInventory() != null) {
            int px = m.playerInventory().x();
            int py = m.playerInventory().y();
            int hy = m.playerInventory().hotbarY() != null ? m.playerInventory().hotbarY() : py + 58;
            full.beginControlFlow("for (int row = 0; row < 3; row++)").beginControlFlow("for (int col = 0; col < 9; col++)")
                    .addStatement("addSlot(new $T(inventory, col + row * 9 + 9, $L + col * 18, $L + row * 18))", T.SLOT, px, py)
                    .endControlFlow().endControlFlow()
                    .beginControlFlow("for (int col = 0; col < 9; col++)")
                    .addStatement("addSlot(new $T(inventory, col, $L + col * 18, $L))", T.SLOT, px, hy).endControlFlow();
        }
        if (data > 0) {
            full.addStatement("checkContainerDataCount(data, $L)", data).addStatement("addDataSlots(data)");
        }
        t.addMethod(full.build());
        ClassName beBase = be.map(b -> n.beBase(b.id())).orElse(null);
        MethodSpec.Builder containerOf = MethodSpec.methodBuilder("containerOf").addModifiers(Modifier.PROTECTED, Modifier.STATIC)
                .returns(T.CONTAINER).addParameter(T.BLOCK_ENTITY, "be")
                .addStatement("return be instanceof $T c ? c : new $T($L)", T.CONTAINER, T.SIMPLE_CONTAINER, Math.max(1, slots));
        MethodSpec.Builder dataOf = MethodSpec.methodBuilder("dataOf").addModifiers(Modifier.PROTECTED, Modifier.STATIC)
                .returns(T.CONTAINER_DATA).addParameter(T.BLOCK_ENTITY, "be");
        if (beBase != null && data > 0) {
            dataOf.addStatement("return be instanceof $T b ? b.data : new $T($L)", beBase, T.SIMPLE_CONTAINER_DATA, data);
        } else {
            dataOf.addStatement("return new $T($L)", T.SIMPLE_CONTAINER_DATA, data);
        }
        t.addMethod(containerOf.build()).addMethod(dataOf.build());
        MethodSpec.Builder accepts = MethodSpec.methodBuilder("accepts").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.BOOLEAN).addParameter(TypeName.INT, "slot").addParameter(T.ITEM_STACK, "stack")
                .beginControlFlow("switch (slot)");
        for (IrNode.Menu.Slot s : m.slots()) {
            if ("output".equals(s.role())) {
                accepts.addStatement("case $L: return false", s.index());
            } else if (s.filter() != null) {
                if (s.filter().startsWith("#")) {
                    accepts.addStatement("case $L: return stack.is($T.create($T.ITEM_REGISTRY, new $T($S)))", s.index(), T.TAG_KEY,
                            T.REGISTRY, T.RL, s.filter().substring(1));
                } else {
                    accepts.addStatement("case $L: return new $T($S).equals($T.ITEMS.getKey(stack.getItem()))", s.index(), T.RL,
                            s.filter(), T.FORGE_REGISTRIES);
                }
            }
        }
        accepts.addStatement("default: return true").endControlFlow();
        t.addMethod(accepts.build());
        t.addType(TypeSpec.classBuilder("MachineSlot").addModifiers(Modifier.PUBLIC, Modifier.STATIC).superclass(T.SLOT)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.CONTAINER, "container")
                        .addParameter(TypeName.INT, "index").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                        .addStatement("super(container, index, x, y)").build())
                .addMethod(over("mayPlace", TypeName.BOOLEAN).addParameter(T.ITEM_STACK, "stack")
                        .addStatement("return accepts(getContainerSlot(), stack)").build())
                .build());
        t.addMethod(over("stillValid", TypeName.BOOLEAN).addParameter(T.PLAYER, "player").addStatement("return container.stillValid(player)").build());
        t.addMethod(MethodSpec.methodBuilder("data").addModifiers(Modifier.PUBLIC).returns(TypeName.INT).addParameter(TypeName.INT, "index")
                .addStatement("return data.get(index)").build());
        MethodSpec.Builder q = over("quickMoveStack", T.ITEM_STACK).addParameter(T.PLAYER, "player").addParameter(TypeName.INT, "index")
                .addStatement("$T slot = slots.get(index)", T.SLOT)
                .beginControlFlow("if (!slot.hasItem())").addStatement("return $T.EMPTY", T.ITEM_STACK).endControlFlow()
                .addStatement("$T stack = slot.getItem()", T.ITEM_STACK).addStatement("$T copy = stack.copy()", T.ITEM_STACK);
        if (m.playerInventory() != null) {
            q.beginControlFlow("if (index < MACHINE_SLOTS)")
                    .beginControlFlow("if (!moveItemStackTo(stack, MACHINE_SLOTS, MACHINE_SLOTS + 36, true))").addStatement("return $T.EMPTY", T.ITEM_STACK).endControlFlow()
                    .nextControlFlow("else")
                    .addStatement("boolean moved = false")
                    .beginControlFlow("for (int i = 0; i < MACHINE_SLOTS && !moved; i++)")
                    .beginControlFlow("if (accepts(i, stack) && moveItemStackTo(stack, i, i + 1, false))").addStatement("moved = true").endControlFlow()
                    .endControlFlow()
                    .beginControlFlow("if (!moved)").addStatement("return $T.EMPTY", T.ITEM_STACK).endControlFlow()
                    .endControlFlow()
                    .beginControlFlow("if (stack.isEmpty())").addStatement("slot.set($T.EMPTY)", T.ITEM_STACK)
                    .nextControlFlow("else").addStatement("slot.setChanged()").endControlFlow()
                    .addStatement("return copy");
        } else {
            q.addStatement("return $T.EMPTY", T.ITEM_STACK);
        }
        t.addMethod(q.build());
        ctx.out.addJava(base.packageName(), t.build());
        if (m.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Names19.cls(m.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                            .addParameter(T.INVENTORY, "inventory").addParameter(T.FRIENDLY_BUF, "buf")
                            .addStatement("super(id, inventory, buf)").build())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                            .addParameter(T.INVENTORY, "inventory").addParameter(T.BLOCK_ENTITY, "blockEntity")
                            .addStatement("super(id, inventory, blockEntity)").build())
                    .build(), "menu " + m.id());
        }
        reg.addField(FieldSpec.builder(ro(ParameterizedTypeName.get(T.MENU_TYPE, cls)), n.menu(m.id()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("MENU_TYPES.register($S, () -> $T.create($T::new))", n.path(m.id()), T.FORGE_MENU_TYPE, cls).build());
    }

    private void screen(IrNode.Menu m) {
        ClassName base = n.screenBase(m.id());
        ClassName menuBase = n.menuBase(m.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC)
                .superclass(ParameterizedTypeName.get(T.SCREEN, menuBase)).addJavadoc("Screen of menu $L.\n", m.id());
        int tw = 256;
        int th = 256;
        int u0 = 0;
        int v0 = 0;
        if (m.texture() != null) {
            t.addField(FieldSpec.builder(T.RL, "TEXTURE", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S)", T.RL, m.texture().path()).build());
            tw = m.texture().textureWidth() != null ? m.texture().textureWidth() : 256;
            th = m.texture().textureHeight() != null ? m.texture().textureHeight() : 256;
            u0 = m.texture().u() != null ? m.texture().u() : 0;
            v0 = m.texture().v() != null ? m.texture().v() : 0;
        }
        t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(menuBase, "menu")
                .addParameter(T.INVENTORY, "inventory").addParameter(T.COMPONENT, "title")
                .addStatement("super(menu, inventory, title)").addStatement("imageWidth = $L", m.size().width())
                .addStatement("imageHeight = $L", m.size().height()).addStatement("inventoryLabelY = imageHeight - 94").build());
        List<IrNode.Menu.Button> buttons = m.buttons() == null ? List.of() : m.buttons();
        if (!buttons.isEmpty()) {
            MethodSpec.Builder init = MethodSpec.methodBuilder("init").addAnnotation(Override.class).addModifiers(Modifier.PROTECTED)
                    .addStatement("super.init()");
            for (IrNode.Menu.Button b : buttons) {
                init.addStatement("addRenderableWidget(new $T(leftPos + $L, topPos + $L, $L, $L, $T.translatable($S), b -> onButton($S)))",
                        T.BUTTON, b.x(), b.y(), b.width(), b.height(), T.COMPONENT, b.label() == null ? "" : b.label(), b.id());
            }
            t.addMethod(init.build());
            t.addMethod(MethodSpec.methodBuilder("onButton").addModifiers(Modifier.PROTECTED).addParameter(String.class, "id")
                    .addJavadoc("Hook: a button was pressed (the logic sends its packet).\n").build());
        }
        MethodSpec.Builder bg = MethodSpec.methodBuilder("renderBg").addAnnotation(Override.class).addModifiers(Modifier.PROTECTED)
                .addParameter(T.POSE_STACK, "pose").addParameter(TypeName.FLOAT, "partialTick")
                .addParameter(TypeName.INT, "mouseX").addParameter(TypeName.INT, "mouseY");
        if (m.texture() != null) {
            bg.addStatement("$T.setShader($T::getPositionTexShader)", T.RENDER_SYSTEM, T.GAME_RENDERER)
                    .addStatement("$T.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)", T.RENDER_SYSTEM)
                    .addStatement("$T.setShaderTexture(0, TEXTURE)", T.RENDER_SYSTEM)
                    .addStatement("blit(pose, leftPos, topPos, $LF, $LF, imageWidth, imageHeight, $L, $L)", u0, v0, tw, th);
            for (IrNode.Menu.Bar bar : m.bars() == null ? List.<IrNode.Menu.Bar>of() : m.bars()) {
                int vi = dataIndex(m, bar.value());
                CodeBlock value = vi >= 0 ? CodeBlock.of("menu.data($L)", vi) : CodeBlock.of("0");
                JsonNode mx = bar.max();
                CodeBlock max = mx == null || mx.isNull() ? CodeBlock.of("100") : mx.isNumber() ? CodeBlock.of("$L", mx.asInt())
                        : dataIndex(m, mx.asString()) >= 0 ? CodeBlock.of("menu.data($L)", dataIndex(m, mx.asString())) : CodeBlock.of("0");
                boolean hor = bar.direction() == null || "right".equals(bar.direction()) || "left".equals(bar.direction());
                int len = hor ? bar.width() : bar.height();
                bg.beginControlFlow("").addStatement("int value = $L", value).addStatement("int max = $L", max)
                        .beginControlFlow("if (max > 0 && value > 0)").addStatement("int n = Math.min($L, value * $L / max)", len, len);
                switch (bar.direction() == null ? "right" : bar.direction()) {
                    case "left" -> bg.addStatement("blit(pose, leftPos + $L + $L - n, topPos + $L, $L + $L - n, $L, n, $L, $L, $L)",
                            bar.x(), bar.width(), bar.y(), bar.u(), bar.width(), bar.v(), bar.height(), tw, th);
                    case "up" -> bg.addStatement("blit(pose, leftPos + $L, topPos + $L + $L - n, $L, $L + $L - n, $L, n, $L, $L)",
                            bar.x(), bar.y(), bar.height(), bar.u(), bar.v(), bar.height(), bar.width(), tw, th);
                    case "down" -> bg.addStatement("blit(pose, leftPos + $L, topPos + $L, $L, $L, $L, n, $L, $L)",
                            bar.x(), bar.y(), bar.u(), bar.v(), bar.width(), tw, th);
                    default -> bg.addStatement("blit(pose, leftPos + $L, topPos + $L, $L, $L, n, $L, $L, $L)",
                            bar.x(), bar.y(), bar.u(), bar.v(), bar.height(), tw, th);
                }
                bg.endControlFlow().endControlFlow();
            }
        }
        t.addMethod(bg.build());
        t.addMethod(MethodSpec.methodBuilder("render").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.POSE_STACK, "pose").addParameter(TypeName.INT, "mouseX").addParameter(TypeName.INT, "mouseY")
                .addParameter(TypeName.FLOAT, "partialTick")
                .addStatement("renderBackground(pose)").addStatement("super.render(pose, mouseX, mouseY, partialTick)")
                .addStatement("renderTooltip(pose, mouseX, mouseY)").build());
        ctx.out.addJava(base.packageName(), t.build());
        if (m.screenClass() != null && ctx.options.stubs()) {
            ClassName logic = Names19.cls(m.screenClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(menuBase, "menu")
                            .addParameter(T.INVENTORY, "inventory").addParameter(T.COMPONENT, "title")
                            .addStatement("super(menu, inventory, title)").build()).build(), "screen " + m.id());
        }
    }

    private static int dataIndex(IrNode.Menu m, String name) {
        if (name == null || m.dataSlots() == null) {
            return -1;
        }
        return m.dataSlots().stream().filter(d -> d.name().equals(name)).mapToInt(IrNode.Menu.DataSlot::index).findFirst().orElse(-1);
    }

    // ---- client setup and mod class ----------------------------------------------------------------

    private void client() {
        MethodSpec.Builder setup = MethodSpec.methodBuilder("clientSetup").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(T.CLIENT_SETUP, "event");
        if (!ctx.ir.all(IrNode.Menu.class).isEmpty()) {
            setup.beginControlFlow("event.enqueueWork(() ->");
            for (IrNode.Menu m : ctx.ir.all(IrNode.Menu.class)) {
                setup.addStatement("$T.<$T, $T>register($T.$L.get(), $T::new)", T.MENU_SCREENS, n.menuBase(m.id()), n.screenClass(m),
                        n.registries, n.menu(m.id()), n.screenClass(m));
            }
            setup.endControlFlow(")");
        }
        TypeSpec t = TypeSpec.classBuilder(n.client).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Client-only setup (menu screens). Registered on the mod bus by the mod class on the client dist.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(setup.build()).build();
        ctx.out.addJava(n.client.packageName(), t);
    }

    private void mod() {
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addStatement("$T bus = $T.get().getModEventBus()", T.EVENT_BUS, T.MOD_LOADING_CONTEXT)
                .addStatement("$T.register(bus)", n.registries);
        if (!ctx.ir.all(IrNode.Packet.class).isEmpty()) {
            ctor.addStatement("bus.addListener(($T event) -> $T.init())", T.COMMON_SETUP, n.network);
        }
        ctor.beginControlFlow("if ($T.dist == $T.CLIENT)", T.FML_ENV, T.DIST)
                .addStatement("bus.addListener($T::clientSetup)", n.client).endControlFlow();
        TypeSpec t = TypeSpec.classBuilder(n.modClass).addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(T.MOD).addMember("value", "$S", ctx.options.modId()).build())
                .addJavadoc("Forge 1.19.2 entry point (omitted with --embed).\n")
                .addMethod(ctor.build()).build();
        ctx.out.addJava(n.modClass.packageName(), t);
    }
}
