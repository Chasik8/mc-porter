package dev.porter.generate.forge1710;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;

/** IR {@code item} → {@code <Name>ItemBase extends Item | ItemFood} (+ logic stub). Block items go with their block. */
final class ItemGen {
    /** 1.19.2 vanilla effect ids → 1.7.10 Potion ids (Potion.java, ids 1..23). */
    static final Map<String, Integer> VANILLA_POTIONS = Map.ofEntries(Map.entry("minecraft:speed", 1),
            Map.entry("minecraft:slowness", 2), Map.entry("minecraft:haste", 3), Map.entry("minecraft:mining_fatigue", 4),
            Map.entry("minecraft:strength", 5), Map.entry("minecraft:instant_health", 6), Map.entry("minecraft:instant_damage", 7),
            Map.entry("minecraft:jump_boost", 8), Map.entry("minecraft:nausea", 9), Map.entry("minecraft:regeneration", 10),
            Map.entry("minecraft:resistance", 11), Map.entry("minecraft:fire_resistance", 12),
            Map.entry("minecraft:water_breathing", 13), Map.entry("minecraft:invisibility", 14),
            Map.entry("minecraft:blindness", 15), Map.entry("minecraft:night_vision", 16), Map.entry("minecraft:hunger", 17),
            Map.entry("minecraft:weakness", 18), Map.entry("minecraft:poison", 19), Map.entry("minecraft:wither", 20),
            Map.entry("minecraft:health_boost", 21), Map.entry("minecraft:absorption", 22), Map.entry("minecraft:saturation", 23));

    private final Plan plan;
    private final GenContext ctx;

    ItemGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        Map<String, List<IrNode.Item>> byLogic = new java.util.TreeMap<>();
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (i.placesBlock() == null && i.logicClass() != null) {
                byLogic.computeIfAbsent(i.logicClass(), k -> new java.util.ArrayList<>()).add(i);
            }
        }
        java.util.Set<String> sharedDone = new java.util.TreeSet<>();
        for (IrNode.Item i : ctx.ir.all(IrNode.Item.class)) {
            if (i.placesBlock() != null) {
                blockItem(i);
            } else if (i.logicClass() != null && byLogic.get(i.logicClass()).size() > 1) {
                if (sharedDone.add(i.logicClass())) {
                    emitShared(byLogic.get(i.logicClass()));
                }
            } else {
                emit(i);
            }
        }
    }

    /** True when the item's logic class is shared with other items (one class, constructor gets the names). */
    static boolean shared(GenContext ctx, IrNode.Item i) {
        return i.placesBlock() == null && i.logicClass() != null && ctx.ir.all(IrNode.Item.class).stream()
                .filter(o -> o.placesBlock() == null && i.logicClass().equals(o.logicClass())).count() > 1;
    }

    /** Icon name of a (non-block) item. */
    String icon(IrNode.Item i) {
        return i.textures() != null && i.textures().get("layer0") != null ? plan.icon(i.textures().get("layer0"))
                : plan.iconNs(dev.porter.generate.Names.namespace(i.id())) + ":" + plan.registryName(i.id());
    }

    /** Base class of a logic class shared by several items. */
    ClassName sharedBase(String logicClass) {
        return ClassName.get(plan.pkg + ".item", Plan.cls(logicClass).simpleName() + "SharedItemBase");
    }

    /**
     * Several items with one logic class (1.19.2 passes per-item constructor arguments, e.g. {@code new
     * MugItem("mug_beer", BEER)}; the IR records only the class): one base with a
     * {@code (registryName, unlocalizedName, textureName)} constructor, item properties from the first item (others
     * must agree, else reported), one stub; the content class passes each item's names.
     */
    private void emitShared(List<IrNode.Item> items) {
        IrNode.Item first = items.get(0);
        String logicFqn = first.logicClass();
        ClassName base = sharedBase(logicFqn);
        for (IrNode.Item o : items.subList(1, items.size())) {
            if (!java.util.Objects.equals(o.food(), first.food()) || !java.util.Objects.equals(o.maxStackSize(), first.maxStackSize())
                    || !java.util.Objects.equals(o.maxDamage(), first.maxDamage()) || !java.util.Objects.equals(o.rarity(), first.rarity())) {
                ctx.warn("shared-logic-properties", o.id(), "item properties differ from " + first.id() + " (shared logic class "
                        + logicFqn + "); the shared base uses " + first.id() + "'s; the logic class must override per registryName");
            }
        }
        ctx.info("shared-logic-class", logicFqn, items.size() + " items share it: " + items.stream().map(IrNode.Item::id).toList()
                + "; constructor (registryName, unlocalizedName, textureName)");
        TypeSpec.Builder t = baseType(first, base, true);
        t.addField(com.squareup.javapoet.FieldSpec.builder(ClassName.get(String.class), "registryName", Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Registry name (without mod id) of this item instance.\n").build());
        ctx.out.addJava(base.packageName(), t.build());
        if (ctx.options.stubs()) {
            ClassName logic = Plan.cls(logicFqn);
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic shared by items $L. Rewriter output goes here; keep the constructor.\n",
                            items.stream().map(IrNode.Item::id).toList())
                    .addMethod(sharedCtor().addStatement("super(registryName, unlocalizedName, textureName)").build()).build(),
                    "item(shared) " + logicFqn);
        }
    }

    private static MethodSpec.Builder sharedCtor() {
        return MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(String.class, "registryName")
                .addParameter(String.class, "unlocalizedName").addParameter(String.class, "textureName");
    }

    private void blockItem(IrNode.Item i) {
        if (i.maxStackSize() != null && i.maxStackSize() != 64 || i.rarity() != null && !"common".equals(i.rarity())
                || i.food() != null) {
            ctx.warn("block-item-properties", i.id(), "block item properties (stack size/rarity/food) are not generated; ItemBlock defaults");
        }
        if (i.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(i.logicClass());
            // one class may serve several block items (1.19.2 BlockItem subclasses take the block as an argument)
            List<String> users = ctx.ir.all(IrNode.Item.class).stream()
                    .filter(o -> o.placesBlock() != null && i.logicClass().equals(o.logicClass())).map(IrNode.Item::id).toList();
            if (!users.get(0).equals(i.id())) {
                return; // emitted with the first block item
            }
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(T.ITEM_BLOCK)
                    .addJavadoc("Block item logic of $L (placed block $L). 1.7.10 creates it reflectively with the block.\n",
                            String.join(", ", users), users.size() == 1 ? i.placesBlock() : "of each")
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.BLOCK, "block")
                            .addStatement("super(block)").build()).build(), "blockItem " + String.join(", ", users));
        }
    }

    private void emit(IrNode.Item i) {
        ClassName base = plan.itemBase(i.id());
        TypeSpec.Builder t = baseType(i, base, false);
        ctx.out.addJava(base.packageName(), t.build());
        if (i.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(i.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of item $L. Rewriter output goes here.\n", i.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build()).build(), "item " + i.id());
        }
    }

    /** The generated base class of an item (shared: names come from constructor parameters). */
    private TypeSpec.Builder baseType(IrNode.Item i, ClassName base, boolean shared) {
        IrNode.Item.Food food = i.food();
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC)
                .superclass(food != null ? T.ITEM_FOOD : T.ITEM).addJavadoc("Item $L.\n", shared ? i.logicClass() + " (shared)" : i.id());
        MethodSpec.Builder ctor = shared ? sharedCtor() : MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        if (food != null) {
            ctor.addStatement("super($L, $LF, false)", food.nutrition() == null ? 0 : food.nutrition(),
                    BlockGen.f(food.saturation() == null ? 0.0 : food.saturation()));
        }
        if (shared) {
            ctor.addStatement("this.registryName = registryName");
        }
        String icon = icon(i);
        if (!shared && (i.textures() == null || i.textures().get("layer0") == null)) {
            ctx.warn("item-no-texture", i.id(), "no layer0 texture; icon " + icon + " assumed");
        } else if (i.textures() != null && i.textures().size() > 1) {
            ctx.warn("item-layers", i.id(), "only layer0 is used (1.7.10 single-pass item icon)");
        }
        if (shared) {
            ctor.addStatement("setUnlocalizedName(unlocalizedName)").addStatement("setTextureName(textureName)");
        } else {
            ctor.addStatement("setUnlocalizedName($S)", plan.legacyName(i.id(), i.extensions()));
            ctor.addStatement("setTextureName($S)", icon);
        }
        if (i.maxStackSize() != null) {
            ctor.addStatement("setMaxStackSize($L)", i.maxStackSize());
        }
        if (i.maxDamage() != null && i.maxDamage() > 0) {
            ctor.addStatement("setMaxDamage($L)", i.maxDamage());
        }
        if (food != null && Boolean.TRUE.equals(food.alwaysEdible())) {
            ctor.addStatement("setAlwaysEdible()");
        }
        t.addMethod(ctor.build());
        if (i.rarity() != null && !"common".equals(i.rarity())) {
            if (List.of("uncommon", "rare", "epic").contains(i.rarity())) {
                t.addMethod(MethodSpec.methodBuilder("getRarity").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(T.ENUM_RARITY).addParameter(T.ITEM_STACK, "stack")
                        .addStatement("return $T.$L", T.ENUM_RARITY, i.rarity()).build());
            } else {
                ctx.warn("rarity-unmapped", i.id(), "rarity " + i.rarity() + " has no 1.7.10 equivalent");
            }
        }
        if (food != null) {
            if (Boolean.TRUE.equals(food.drink())) {
                t.addMethod(MethodSpec.methodBuilder("getItemUseAction").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(T.ENUM_ACTION).addParameter(T.ITEM_STACK, "stack")
                        .addStatement("return $T.drink", T.ENUM_ACTION).build());
            }
            if (food.effects() != null && !food.effects().isEmpty()) {
                MethodSpec.Builder eaten = MethodSpec.methodBuilder("onFoodEaten").addAnnotation(Override.class)
                        .addModifiers(Modifier.PROTECTED).addParameter(T.ITEM_STACK, "stack").addParameter(T.WORLD, "world")
                        .addParameter(T.PLAYER, "player")
                        .addStatement("super.onFoodEaten(stack, world, player)")
                        .beginControlFlow("if (world.isRemote)").addStatement("return").endControlFlow();
                for (IrNode.Item.FoodEffect fe : food.effects()) {
                    CodeBlock id = potionId(fe.effect(), i.id());
                    if (id == null) {
                        continue;
                    }
                    double p = fe.probability() == null ? 1.0 : fe.probability();
                    CodeBlock apply = CodeBlock.of("player.addPotionEffect(new $T($L, $L, $L))", T.POTION_EFFECT, id,
                            fe.duration() == null ? 0 : fe.duration(), fe.amplifier() == null ? 0 : fe.amplifier());
                    if (p >= 1.0) {
                        eaten.addStatement(apply);
                    } else {
                        eaten.beginControlFlow("if (world.rand.nextFloat() < $LF)", BlockGen.f(p)).addStatement(apply).endControlFlow();
                    }
                }
                t.addMethod(eaten.build());
            }
        }
        return t;
    }

    /** Potion id expression for an effect id, or null (reported) when it has no 1.7.10 counterpart. */
    CodeBlock potionId(String effect, String subject) {
        if (plan.potionIds.containsKey(effect)) {
            return CodeBlock.of("$T.$L.id", plan.content, PotionGen.field(effect));
        }
        Integer v = VANILLA_POTIONS.get(effect);
        if (v != null) {
            return CodeBlock.of("$L", v);
        }
        ctx.skip("effect-unmapped", subject, "effect " + effect + " has no 1.7.10 Potion; not applied");
        return null;
    }

    static TypeName type() {
        return T.ITEM;
    }
}
