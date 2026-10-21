package dev.porter.generate.forge1192;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;

/** IR {@code multiblock} → data definitions, kept in IR orientation (layers bottom→top, z rows, min corner origin). */
final class Multiblock19 {
    private static final ClassName PROPERTY = ClassName.get("net.minecraft.world.level.block.state.properties", "Property");
    private final Names19 n;
    private final GenContext ctx;

    Multiblock19(Names19 n) {
        this.n = n;
        this.ctx = n.ctx;
    }

    void run() {
        def();
        ClassName def = n.multiblockDef;
        TypeName list = ParameterizedTypeName.get(ClassName.get(List.class), def);
        TypeSpec.Builder t = TypeSpec.classBuilder(n.multiblocks).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        List<CodeBlock> all = new ArrayList<>();
        for (IrNode.Multiblock m : ctx.ir.all(IrNode.Multiblock.class)) {
            String f = Names.constant(Names.path(m.id()));
            CodeBlock.Builder c = CodeBlock.builder().add("$T.builder($S)", def, m.id()).indent();
            for (Map.Entry<String, IrNode.BlockRef> e : m.palette().entrySet()) {
                IrNode.BlockRef r = e.getValue();
                String sym = charLit(e.getKey().charAt(0));
                if (Boolean.TRUE.equals(r.any())) {
                    c.add("\n.any($L)", sym);
                } else if (r.state() == null || r.state().isEmpty()) {
                    c.add("\n.block($L, $S)", sym, r.block());
                } else {
                    List<String> kv = new ArrayList<>();
                    r.state().forEach((k, v) -> kv.add(k + "=" + v));
                    c.add("\n.block($L, $S, $S)", sym, r.block(), String.join(",", kv));
                }
            }
            List<CodeBlock> layers = new ArrayList<>();
            for (List<String> layer : m.layers()) {
                layers.add(CodeBlock.of("new String[] {$L}", CodeBlock.join(layer.stream().map(r -> CodeBlock.of("$S", r)).toList(), ", ")));
            }
            c.add("\n.layers($L)", CodeBlock.join(layers, ", "));
            if (m.controller() != null) {
                c.add("\n.controller($S, $L, $L, $L, $S)", m.controller().block(), m.controller().pos().get(0), m.controller().pos().get(1),
                        m.controller().pos().get(2), m.controller().facing() == null ? "north" : m.controller().facing());
            }
            c.add("\n.build()").unindent();
            t.addField(FieldSpec.builder(def, f, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", c.build()).build());
            all.add(CodeBlock.of("$L", f));
        }
        t.addField(FieldSpec.builder(list, "ALL", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.of($L)", List.class, CodeBlock.join(all, ", ")).build());
        ctx.out.addJava(n.multiblocks.packageName(), t.build());
    }

    private static String charLit(char c) {
        return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
    }

    private void def() {
        ClassName def = n.multiblockDef;
        ClassName part = def.nestedClass("Part");
        ClassName builder = def.nestedClass("Builder");
        TypeName strMap = ParameterizedTypeName.get(Map.class, String.class, String.class);
        TypeName palette = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(Character.class), part);
        TypeVariableName tv = TypeVariableName.get("V", ParameterizedTypeName.get(ClassName.get(Comparable.class), TypeVariableName.get("V")));
        TypeSpec partType = TypeSpec.classBuilder(part).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addField(TypeName.CHAR, "symbol", Modifier.PUBLIC, Modifier.FINAL)
                .addField(T.RL, "block", Modifier.PUBLIC, Modifier.FINAL)
                .addField(strMap, "state", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.BOOLEAN, "any", Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addParameter(TypeName.CHAR, "symbol").addParameter(T.RL, "block")
                        .addParameter(strMap, "state").addParameter(TypeName.BOOLEAN, "any")
                        .addStatement("this.symbol = symbol").addStatement("this.block = block").addStatement("this.state = state")
                        .addStatement("this.any = any").build())
                .addMethod(MethodSpec.methodBuilder("matches").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
                        .addParameter(T.BLOCK_STATE, "s")
                        .beginControlFlow("if (any)").addStatement("return true").endControlFlow()
                        .beginControlFlow("if (!block.equals($T.BLOCKS.getKey(s.getBlock())))", T.FORGE_REGISTRIES).addStatement("return false").endControlFlow()
                        .beginControlFlow("for ($T<?> p : s.getProperties())", PROPERTY)
                        .addStatement("String want = state.get(p.getName())")
                        .beginControlFlow("if (want != null && !want.equals(valueName(s, p)))").addStatement("return false").endControlFlow()
                        .endControlFlow().addStatement("return true").build())
                .addMethod(MethodSpec.methodBuilder("valueName").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addTypeVariable(tv)
                        .returns(String.class).addParameter(T.BLOCK_STATE, "s").addParameter(ParameterizedTypeName.get(PROPERTY, tv), "p")
                        .addStatement("return p.getName(s.getValue(p))").build())
                .build();
        TypeSpec builderType = TypeSpec.classBuilder(builder).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addField(String.class, "id", Modifier.PRIVATE, Modifier.FINAL)
                .addField(FieldSpec.builder(palette, "palette", Modifier.PRIVATE, Modifier.FINAL).initializer("new $T<>()", LinkedHashMap.class).build())
                .addField(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers", Modifier.PRIVATE)
                .addField(T.RL, "controller", Modifier.PRIVATE)
                .addField(FieldSpec.builder(T.BLOCK_POS, "controllerPos", Modifier.PRIVATE).initializer("$T.ZERO", T.BLOCK_POS).build())
                .addField(FieldSpec.builder(T.DIRECTION, "facing", Modifier.PRIVATE).initializer("$T.NORTH", T.DIRECTION).build())
                .addMethod(MethodSpec.constructorBuilder().addParameter(String.class, "id").addStatement("this.id = id").build())
                .addMethod(MethodSpec.methodBuilder("block").addModifiers(Modifier.PUBLIC).returns(builder).addParameter(TypeName.CHAR, "symbol")
                        .addParameter(String.class, "block").addStatement("return block(symbol, block, \"\")").build())
                .addMethod(MethodSpec.methodBuilder("block").addModifiers(Modifier.PUBLIC).returns(builder).addParameter(TypeName.CHAR, "symbol")
                        .addParameter(String.class, "block").addParameter(String.class, "state")
                        .addStatement("$T s = new $T<>()", strMap, LinkedHashMap.class)
                        .beginControlFlow("for (String kv : state.split($S))", ",")
                        .beginControlFlow("if (kv.contains($S))", "=").addStatement("s.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1))")
                        .endControlFlow().endControlFlow()
                        .addStatement("palette.put(symbol, new Part(symbol, new $T(block), s, false))", T.RL).addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("any").addModifiers(Modifier.PUBLIC).returns(builder).addParameter(TypeName.CHAR, "symbol")
                        .addStatement("palette.put(symbol, new Part(symbol, null, $T.of(), true))", Map.class).addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("layers").addModifiers(Modifier.PUBLIC).returns(builder).varargs()
                        .addParameter(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers").addStatement("this.layers = layers")
                        .addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("controller").addModifiers(Modifier.PUBLIC).returns(builder)
                        .addParameter(String.class, "block").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                        .addParameter(TypeName.INT, "z").addParameter(String.class, "facing")
                        .addStatement("this.controller = new $T(block)", T.RL).addStatement("this.controllerPos = new $T(x, y, z)", T.BLOCK_POS)
                        .addStatement("this.facing = $T.byName(facing)", T.DIRECTION).addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(def)
                        .addStatement("return new $T(id, layers, palette, controller, controllerPos, facing)", def).build())
                .build();
        TypeSpec t = TypeSpec.classBuilder(def).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Multiblock as data: {@code layers[y][z]} rows of x palette symbols, positions relative to the min corner.\n")
                .addType(partType).addType(builderType)
                .addField(String.class, "id", Modifier.PUBLIC, Modifier.FINAL)
                .addField(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers", Modifier.PRIVATE, Modifier.FINAL)
                .addField(palette, "palette", Modifier.PRIVATE, Modifier.FINAL)
                .addField(T.RL, "controller", Modifier.PUBLIC, Modifier.FINAL)
                .addField(T.BLOCK_POS, "controllerPos", Modifier.PUBLIC, Modifier.FINAL)
                .addField(T.DIRECTION, "facing", Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).addParameter(String.class, "id")
                        .addParameter(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers").addParameter(palette, "palette")
                        .addParameter(T.RL, "controller").addParameter(T.BLOCK_POS, "controllerPos").addParameter(T.DIRECTION, "facing")
                        .addStatement("this.id = id").addStatement("this.layers = layers")
                        .addStatement("this.palette = $T.unmodifiableMap(palette)", java.util.Collections.class)
                        .addStatement("this.controller = controller").addStatement("this.controllerPos = controllerPos")
                        .addStatement("this.facing = facing").build())
                .addMethod(MethodSpec.methodBuilder("builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(builder)
                        .addParameter(String.class, "id").addStatement("return new $T(id)", builder).build())
                .addMethod(MethodSpec.methodBuilder("partAt").addModifiers(Modifier.PUBLIC).returns(part).addParameter(TypeName.INT, "x")
                        .addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z").addStatement("return palette.get(layers[y][z].charAt(x))").build())
                .addMethod(MethodSpec.methodBuilder("palette").addModifiers(Modifier.PUBLIC).returns(palette).addStatement("return palette").build())
                .addMethod(MethodSpec.methodBuilder("matches").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
                        .addJavadoc("Checks the structure with its min corner at {@code origin} (IR orientation, no rotation).\n")
                        .addParameter(T.LEVEL, "level").addParameter(T.BLOCK_POS, "origin")
                        .beginControlFlow("for (int y = 0; y < layers.length; y++)")
                        .beginControlFlow("for (int z = 0; z < layers[y].length; z++)")
                        .beginControlFlow("for (int x = 0; x < layers[y][z].length(); x++)")
                        .beginControlFlow("if (!partAt(x, y, z).matches(level.getBlockState(origin.offset(x, y, z))))").addStatement("return false").endControlFlow()
                        .endControlFlow().endControlFlow().endControlFlow().addStatement("return true").build())
                .build();
        ctx.out.addJava(def.packageName(), t);
    }
}
