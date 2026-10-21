package dev.porter.generate.forge1710;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.Map;
import javax.lang.model.element.Modifier;

/** IR {@code effect} → {@code <Name>PotionBase extends Potion} with an id from {@code --potion-ids}. */
final class PotionGen {
    static final Map<String, String> ATTRIBUTES = Map.of("minecraft:generic.max_health", "maxHealth",
            "minecraft:generic.follow_range", "followRange", "minecraft:generic.knockback_resistance", "knockbackResistance",
            "minecraft:generic.movement_speed", "movementSpeed", "minecraft:generic.attack_damage", "attackDamage");
    static final Map<String, Integer> OPERATIONS = Map.of("addition", 0, "multiply_base", 1, "multiply_total", 2);

    private final Plan plan;
    private final GenContext ctx;

    PotionGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    static String field(String id) {
        return "potion" + Names.pascal(Names.path(id));
    }

    static int color(String hex, int fallback) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) {
            return fallback;
        }
        return Integer.parseInt(hex.substring(1, 7), 16);
    }

    static final ClassName MINECRAFT = ClassName.get("net.minecraft.client", "Minecraft");

    /**
     * {@code [namespace, path below textures/]} of the effect's status icon: IR {@code icon}, else the 1.19.2
     * convention {@code <ns>:mob_effect/<path>}; with {@code --assets} only when the texture is there.
     */
    String[] icon(IrNode.Effect e) {
        String ref = e.icon() != null ? e.icon() : Names.namespace(e.id()) + ":mob_effect/" + Names.path(e.id());
        String ns = Names.namespace(ref);
        String path = Names.path(ref);
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        if (ctx.options.assetsDir() != null) {
            if (!java.nio.file.Files.isRegularFile(ctx.options.assetsDir().resolve("assets/" + ns + "/textures/" + path + ".png"))) {
                return null;
            }
        } else if (e.icon() == null) {
            return null;
        }
        return new String[] {plan.iconNs(ns), path};
    }

    void run() {
        for (IrNode.Effect e : ctx.ir.all(IrNode.Effect.class)) {
            emit(e);
        }
    }

    private void emit(IrNode.Effect e) {
        ClassName base = plan.potionBase(e.id());
        boolean bad = "harmful".equals(e.category());
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                .addStatement("super(id, $L, $L)", bad, color(e.color(), 0xFFFFFF))
                .addStatement("setPotionName($S)", "potion." + plan.legacyName(e.id(), e.extensions()));
        if ("neutral".equals(e.category())) {
            ctx.info("effect-neutral", e.id(), "neutral category has no 1.7.10 flag; generated as beneficial");
        }
        if (e.attributeModifiers() != null) {
            for (IrNode.Effect.AttributeModifier a : e.attributeModifiers()) {
                String attr = ATTRIBUTES.get(a.attribute());
                Integer op = OPERATIONS.get(a.operation());
                if (attr == null || op == null) {
                    ctx.skip("attribute-unmapped", e.id(), "attribute modifier " + a.attribute() + "/" + a.operation()
                            + " has no 1.7.10 equivalent");
                    continue;
                }
                ctor.addStatement("func_111184_a($T.$L, $S, $LD, $L)", T.MONSTER_ATTRIBUTES, attr, a.uuid(), a.amount(), op);
            }
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.POTION)
                .addJavadoc("Effect $L. The Potion constructor claims Potion.potionTypes[id].\n", e.id())
                .addMethod(ctor.build());
        if (Boolean.TRUE.equals(e.instant())) {
            t.addMethod(MethodSpec.methodBuilder("isInstant").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.BOOLEAN).addStatement("return true").build());
        }
        String[] icon = icon(e);
        if (icon != null) {
            // 1.19.2 draws textures/mob_effect/<path>.png (18x18); 1.7.10 only knows the vanilla icon sheet, Forge's
            // renderInventoryEffect hook draws our own texture at the same place (the sheet icon stays off: index -1)
            t.addField(FieldSpec.builder(T.RESOURCE_LOCATION, "ICON", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $S)", T.RESOURCE_LOCATION, icon[0], "textures/" + icon[1] + ".png").build());
            t.addMethod(MethodSpec.methodBuilder("renderInventoryEffect").addAnnotation(Override.class)
                    .addAnnotation(BlockGen.clientOnly()).addModifiers(Modifier.PUBLIC)
                    .addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(T.POTION_EFFECT, "effect")
                    .addParameter(MINECRAFT, "mc")
                    .addStatement("mc.getTextureManager().bindTexture(ICON)")
                    .addStatement("$T.func_146110_a(x + 6, y + 7, 0, 0, 18, 18, 18F, 18F)", T.GUI).build());
        } else if (e.icon() != null) {
            ctx.warn("effect-icon", e.id(), "custom status icon " + e.icon() + " not found in the assets; not rendered");
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (e.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(e.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of effect $L (performEffect/isReady). Rewriter output goes here.\n", e.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(TypeName.INT, "id")
                            .addStatement("super(id)").build()).build(), "effect " + e.id());
        }
    }
}
