package dev.porter.generate.forge1710;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.Optional;
import javax.lang.model.element.Modifier;

/** IR {@code fluid} → {@code <Name>Fluid extends Fluid} (+ BlockFluidClassic for its block), registered in FluidRegistry. */
final class FluidGen {
    private final Plan plan;
    private final GenContext ctx;

    FluidGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    static Optional<IrNode.Fluid> fluidOfBlock(GenContext ctx, String blockId) {
        return ctx.ir.all(IrNode.Fluid.class).stream().filter(f -> blockId.equals(f.block())).findFirst();
    }

    static String field(String id) {
        return "fluid" + Names.pascal(Names.path(id));
    }

    static String blockField(String id) {
        return "fluidBlock" + Names.pascal(Names.path(id));
    }

    /** Fluid name in FluidRegistry (lower case, as Fluid's constructor stores it). */
    String fluidName(IrNode.Fluid f) {
        return plan.registryName(f.id()).toLowerCase(java.util.Locale.ROOT);
    }

    ClassName blockClass(IrNode.Fluid f) {
        return ClassName.get(plan.pkg + ".fluid", plan.pascal(f.id()) + "FluidBlock");
    }

    void run() {
        for (IrNode.Fluid f : ctx.ir.all(IrNode.Fluid.class)) {
            emit(f);
        }
    }

    private void emit(IrNode.Fluid f) {
        ClassName cls = plan.fluidClass(f.id());
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addStatement("super($S)", fluidName(f))
                .addStatement("setUnlocalizedName($S)", plan.legacyName(f.id(), f.extensions()));
        if (f.density() != null) {
            ctor.addStatement("setDensity($L)", f.density());
        }
        if (f.viscosity() != null) {
            ctor.addStatement("setViscosity($L)", f.viscosity());
        }
        if (f.temperature() != null) {
            ctor.addStatement("setTemperature($L)", f.temperature());
        }
        if (f.luminosity() != null) {
            ctor.addStatement("setLuminosity($L)", f.luminosity());
        }
        if (Boolean.TRUE.equals(f.gaseous())) {
            ctor.addStatement("setGaseous(true)");
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).superclass(T.FLUID)
                .addJavadoc("Fluid $L. Icons are set on TextureStitchEvent.Pre (client).\n", f.id())
                .addField(FieldSpec.builder(String.class, "STILL_ICON", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", stillIcon(f)).build())
                .addField(FieldSpec.builder(String.class, "FLOWING_ICON", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", flowingIcon(f)).build())
                .addMethod(ctor.build());
        if (f.color() != null) {
            if (f.color().length() > 7) {
                ctx.info("fluid-color-alpha", f.id(), "alpha of " + f.color() + " dropped (1.7.10 Fluid#getColor is RGB)");
            }
            t.addMethod(MethodSpec.methodBuilder("getColor").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.INT).addStatement("return 0x$L", String.format("%06X", PotionGen.color(f.color(), 0xFFFFFF))).build());
        }
        if (f.logicClass() != null) {
            ctx.warn("fluid-logic", f.id(), "fluid logic class " + f.logicClass() + " (ForgeFlowingFluid) has no 1.7.10 counterpart; behaviour is Fluid defaults");
        }
        ctx.out.addJava(cls.packageName(), t.build());
        if (f.stillTexture() == null) {
            ctx.warn("fluid-no-texture", f.id(), "no still texture; icon " + stillIcon(f) + " assumed");
        }
        if (f.block() != null) {
            fluidBlock(f);
        }
    }

    String stillIcon(IrNode.Fluid f) {
        return f.stillTexture() != null ? plan.icon(f.stillTexture()) : plan.iconNs(Names.namespace(f.id())) + ":" + plan.registryName(f.id()) + "_still";
    }

    String flowingIcon(IrNode.Fluid f) {
        return f.flowingTexture() != null ? plan.icon(f.flowingTexture()) : stillIcon(f);
    }

    private void fluidBlock(IrNode.Fluid f) {
        ClassName cls = blockClass(f);
        Optional<IrNode.Block> b = ctx.ir.get(IrNode.Block.class, f.block());
        String legacy = b.map(x -> plan.legacyName(x.id(), x.extensions())).orElse(plan.legacyName(f.block(), null));
        TypeSpec t = TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).superclass(T.BLOCK_FLUID_CLASSIC)
                .addJavadoc("Fluid block $L of fluid $L.\n", f.block(), f.id())
                .addField(FieldSpec.builder(ArrayTypeName.of(T.ICON), "icons", Modifier.PROTECTED).build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.FLUID, "fluid")
                        .addStatement("super(fluid, $T.$L)", T.MATERIAL, f.temperature() != null && f.temperature() > 1000 ? "lava" : "water")
                        .addStatement("setBlockName($S)", legacy).build())
                .addMethod(MethodSpec.methodBuilder("registerBlockIcons").addAnnotation(Override.class).addAnnotation(BlockGen.clientOnly())
                        .addModifiers(Modifier.PUBLIC).addParameter(T.ICON_REGISTER, "reg")
                        .addStatement("icons = new $T[] {reg.registerIcon($T.STILL_ICON), reg.registerIcon($T.FLOWING_ICON)}", T.ICON,
                                plan.fluidClass(f.id()), plan.fluidClass(f.id()))
                        .addStatement("blockIcon = icons[0]").build())
                .addMethod(MethodSpec.methodBuilder("getIcon").addAnnotation(Override.class).addAnnotation(BlockGen.clientOnly())
                        .addModifiers(Modifier.PUBLIC).returns(T.ICON).addParameter(TypeName.INT, "side").addParameter(TypeName.INT, "meta")
                        .addStatement("return side == 0 || side == 1 ? icons[0] : icons[1]").build())
                .build();
        ctx.out.addJava(cls.packageName(), t);
        if (b.isPresent() && b.get().logicClass() != null) {
            ctx.info("fluid-block-logic", f.block(), "LiquidBlock logic " + b.get().logicClass() + " replaced by BlockFluidClassic");
        }
    }
}
