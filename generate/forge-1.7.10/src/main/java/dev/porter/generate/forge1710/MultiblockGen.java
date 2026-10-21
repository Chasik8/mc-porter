package dev.porter.generate.forge1710;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;

/**
 * IR {@code multiblock} → data definitions ({@code MultiblockDef} + {@code Structures} classes):
 * layers bottom→top, z rows front→back of x chars (x = left→right as seen facing the controller), palette, controller.
 * IR layers are in world orientation relative to the min corner; they are re-expressed in the controller's local frame.
 */
final class MultiblockGen {
    private static final String SPARE = "K@#$%&0123456789ABDEFGHIJLMNOQRSTUVWXYZ";
    private final Plan plan;
    private final GenContext ctx;

    MultiblockGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        List<IrNode.Multiblock> all = new ArrayList<>(ctx.ir.all(IrNode.Multiblock.class));
        if (all.isEmpty()) {
            return;
        }
        def();
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.multiblocks).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Every multiblock of the IR, as data. Call {@link #init()} after the blocks are registered.\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        TypeName list = ParameterizedTypeName.get(ClassName.get(List.class), plan.multiblockDef);
        t.addField(FieldSpec.builder(list, "all", Modifier.PRIVATE, Modifier.STATIC)
                .initializer("$T.emptyList()", java.util.Collections.class).build());
        MethodSpec.Builder init = MethodSpec.methodBuilder("init").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addStatement("$T list = new $T<$T>()", list, ArrayList.class, plan.multiblockDef);
        for (IrNode.Multiblock m : all) {
            String f = Names.constant(Names.path(m.id()));
            t.addField(FieldSpec.builder(plan.multiblockDef, f, Modifier.PUBLIC, Modifier.STATIC).addJavadoc("$L.\n", m.id()).build());
            init.addStatement("$L = $L", f, build(m)).addStatement("list.add($L)", f);
        }
        init.addStatement("all = $T.unmodifiableList(list)", java.util.Collections.class);
        t.addMethod(init.build());
        t.addMethod(MethodSpec.methodBuilder("all").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(list)
                .addStatement("return all").build());
        ctx.out.addJava(plan.multiblocks.packageName(), t.build());
    }

    private CodeBlock build(IrNode.Multiblock m) {
        int sx = m.size().get(0);
        int sy = m.size().get(1);
        int sz = m.size().get(2);
        String facing = m.controller() != null && m.controller().facing() != null ? m.controller().facing() : "north";
        int[] fv = switch (facing) {
            case "south" -> new int[] {0, 1};
            case "west" -> new int[] {-1, 0};
            case "east" -> new int[] {1, 0};
            case "north" -> new int[] {0, -1};
            default -> null;
        };
        if (fv == null) {
            ctx.warn("multiblock-facing", m.id(), "controller facing " + facing + " is not horizontal; north assumed");
            fv = new int[] {0, -1};
        }
        if (m.controller() == null || m.controller().facing() == null) {
            ctx.info("multiblock-facing-default", m.id(), "no controller facing; north assumed");
        }
        int cx = m.controller() != null ? m.controller().pos().get(0) : 0;
        int cy = m.controller() != null ? m.controller().pos().get(1) : 0;
        int cz = m.controller() != null ? m.controller().pos().get(2) : 0;
        if (m.controller() == null) {
            ctx.warn("multiblock-no-controller", m.id(), "no controller; position (0,0,0) used as reference");
        }
        int bx = -fv[0];
        int bz = -fv[1];
        int rx = -bz;
        int rz = bx;
        // local (dx, dz) of every IR cell relative to the controller
        int minDx = Integer.MAX_VALUE;
        int maxDx = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE;
        int maxDz = Integer.MIN_VALUE;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int ox = x - cx;
                int oz = z - cz;
                int dx = ox * rx + oz * rz;
                int dz = ox * bx + oz * bz;
                minDx = Math.min(minDx, dx);
                maxDx = Math.max(maxDx, dx);
                minDz = Math.min(minDz, dz);
                maxDz = Math.max(maxDz, dz);
            }
        }
        int w = maxDx - minDx + 1;
        int d = maxDz - minDz + 1;
        char[][][] local = new char[sy][d][w];
        for (int y = 0; y < sy; y++) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    int ox = x - cx;
                    int oz = z - cz;
                    local[y][(ox * bx + oz * bz) - minDz][(ox * rx + oz * rz) - minDx] = m.charAt(x, y, z);
                }
            }
        }
        int lcx = -minDx;
        int lcz = -minDz;
        if (lcz != 0) {
            ctx.warn("multiblock-controller-not-front", m.id(), "controller is not on the front face (local z=" + lcz + ")");
        }
        // controller symbol: unique char at the controller position
        char ctrl = local[cy][lcz][lcx];
        int count = 0;
        for (char[][] layer : local) {
            for (char[] row : layer) {
                for (char c : row) {
                    count += c == ctrl ? 1 : 0;
                }
            }
        }
        Map<Character, IrNode.BlockRef> palette = new LinkedHashMap<>();
        m.palette().forEach((k, v) -> palette.put(k.charAt(0), v));
        if (m.controller() != null && count > 1) {
            char spare = 0;
            for (char c : SPARE.toCharArray()) {
                if (!palette.containsKey(c)) {
                    spare = c;
                    break;
                }
            }
            IrNode.BlockRef ref = palette.get(ctrl);
            if (m.controller().block() != null && (ref == null || !m.controller().block().equals(ref.block()))) {
                ref = IrNode.BlockRef.of(m.controller().block());
            }
            palette.put(spare, ref);
            local[cy][lcz][lcx] = spare;
            ctx.info("multiblock-controller-symbol", m.id(), "palette char '" + ctrl + "' is used " + count
                    + " times; controller position gets its own symbol '" + spare + "'");
            ctrl = spare;
        }
        ctx.info("multiblock-local-frame", m.id(), "layers re-expressed in the controller frame (z=0 front, x left->right "
                + "seen from the front, facing " + facing + "); size " + w + "x" + sy + "x" + d);
        String defId = ctx.isOwn(m.id()) ? ctx.options.modId() + ":" + Names.path(m.id()) : m.id();
        CodeBlock.Builder c = CodeBlock.builder().add("$T.builder($S)", plan.multiblockDef, defId);
        c.indent();
        for (Map.Entry<Character, IrNode.BlockRef> e : palette.entrySet()) {
            char sym = e.getKey();
            boolean used = false;
            for (char[][] layer : local) {
                for (char[] row : layer) {
                    for (char ch : row) {
                        used |= ch == sym;
                    }
                }
            }
            if (!used) {
                continue;
            }
            c.add("\n").add(part(m, sym, e.getValue(), m.controller() != null && sym == ctrl));
        }
        List<CodeBlock> layers = new ArrayList<>();
        for (char[][] layer : local) {
            List<CodeBlock> rows = new ArrayList<>();
            for (char[] row : layer) {
                rows.add(CodeBlock.of("$S", new String(row)));
            }
            layers.add(CodeBlock.of("new String[] {$L}", CodeBlock.join(rows, ", ")));
        }
        c.add("\n.layers($L)", CodeBlock.join(layers, ", "));
        if (Boolean.TRUE.equals(m.mirrorable())) {
            ctx.info("multiblock-mirror", m.id(), "mirrorable flag stored; the validator checks the unmirrored layout only");
        }
        c.add("\n.rotatable($L)", !Boolean.FALSE.equals(m.rotatable()));
        c.add("\n.build()");
        c.unindent();
        return c.build();
    }

    private CodeBlock part(IrNode.Multiblock m, char sym, IrNode.BlockRef ref, boolean controller) {
        String s = charLit(sym);
        if (ref == null) {
            return CodeBlock.of(".any($L)", s);
        }
        if (Boolean.TRUE.equals(ref.any())) {
            return CodeBlock.of(".any($L)", s);
        }
        if ("minecraft:air".equals(ref.block()) || "minecraft:cave_air".equals(ref.block())) {
            return CodeBlock.of(".air($L)", s);
        }
        String[] t = plan.target(ref.block(), true);
        if (t == null) {
            ctx.warn("vanilla-id-unmapped", m.id(), "palette " + sym + "=" + ref.block() + " is not a 1.7.10 block; it never matches");
            t = new String[] {"minecraft", Names.path(ref.block())};
        }
        int meta = -1;
        if (ref.state() != null && !ref.state().isEmpty()) {
            MetaLayout l = plan.layouts.get(ref.block());
            if (l != null) {
                meta = l.metaFor(ref.state());
                if (meta < 0) {
                    ctx.warn("multiblock-state", m.id(), "state " + ref.state() + " of " + ref.block()
                            + " is not fully in metadata; any meta accepted");
                } else if (ref.state().keySet().size() < l.metaProps().size()) {
                    // partial state: only the named properties are fixed — cannot be expressed with one meta
                    meta = -1;
                    ctx.info("multiblock-state-partial", m.id(), "partial state " + ref.state() + " of " + ref.block()
                            + "; any meta accepted (rotation-dependent properties are checked by the logic)");
                }
            } else {
                ctx.warn("multiblock-state", m.id(), "state of external block " + ref.block() + " cannot be mapped; any meta accepted");
            }
        }
        return CodeBlock.of(controller ? ".controller($L, $S, $S, $L)" : ".block($L, $S, $S, $L)", s, t[0], t[1], meta);
    }

    private static String charLit(char c) {
        return c == '\'' ? "'\\''" : c == '\\' ? "'\\\\'" : "'" + c + "'";
    }

    /** The data class (self-contained; mirrors the addon's MultiblockDef API: partAt, palette, count, layers, toWorld). */
    private void def() {
        ClassName def = plan.multiblockDef;
        ClassName part = def.nestedClass("Part");
        ClassName builder = def.nestedClass("Builder");
        TypeName paletteMap = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(Character.class), part);
        TypeSpec partType = TypeSpec.classBuilder(part).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("What may stand at a position: a block (by registry name, resolved lazily), air, or anything.\n")
                .addField(TypeName.CHAR, "symbol", Modifier.PUBLIC, Modifier.FINAL)
                .addField(String.class, "modId", Modifier.PUBLIC, Modifier.FINAL)
                .addField(String.class, "name", Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(TypeName.INT, "meta", Modifier.PUBLIC, Modifier.FINAL).addJavadoc("Required metadata, or -1 for any.\n").build())
                .addField(TypeName.BOOLEAN, "any", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.BOOLEAN, "controller", Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(T.BLOCK, "block", Modifier.PRIVATE).build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(TypeName.CHAR, "symbol").addParameter(String.class, "modId").addParameter(String.class, "name")
                        .addParameter(TypeName.INT, "meta").addParameter(TypeName.BOOLEAN, "any").addParameter(TypeName.BOOLEAN, "controller")
                        .addStatement("this.symbol = symbol").addStatement("this.modId = modId").addStatement("this.name = name")
                        .addStatement("this.meta = meta").addStatement("this.any = any").addStatement("this.controller = controller").build())
                .addMethod(MethodSpec.methodBuilder("isAir").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
                        .addStatement("return name == null && !any").build())
                .addMethod(MethodSpec.methodBuilder("block").addModifiers(Modifier.PUBLIC).returns(T.BLOCK)
                        .addJavadoc("The block, or null for air/any or when it is not registered.\n")
                        .beginControlFlow("if (block == null && name != null)")
                        .addStatement("block = $T.findBlock(modId, name)", T.GAME_REGISTRY).endControlFlow()
                        .addStatement("return block").build())
                .addMethod(MethodSpec.methodBuilder("matches").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
                        .addParameter(T.BLOCK, "b").addParameter(TypeName.INT, "m")
                        .beginControlFlow("if (any)").addStatement("return true").endControlFlow()
                        .beginControlFlow("if (name == null)").addStatement("return b == null || b.getMaterial() == $T.air", T.MATERIAL).endControlFlow()
                        .addStatement("return b != null && b == block() && (meta < 0 || m == meta)").build())
                .addMethod(MethodSpec.methodBuilder("displayStack").addModifiers(Modifier.PUBLIC).returns(T.ITEM_STACK)
                        .addJavadoc("Stack for a guide/legend; null for air, any or unknown blocks.\n")
                        .addStatement("$T b = block()", T.BLOCK)
                        .addStatement("return b == null ? null : new $T(b, 1, Math.max(0, meta))", T.ITEM_STACK).build())
                .build();
        TypeSpec builderType = TypeSpec.classBuilder(builder).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addField(String.class, "id", Modifier.PRIVATE, Modifier.FINAL)
                .addField(FieldSpec.builder(paletteMap, "palette", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T<$T, $T>()", LinkedHashMap.class, Character.class, part).build())
                .addField(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers", Modifier.PRIVATE)
                .addField(FieldSpec.builder(TypeName.BOOLEAN, "rotatable", Modifier.PRIVATE).initializer("true").build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).addParameter(String.class, "id")
                        .addStatement("this.id = id").build())
                .addMethod(paletteMethod(builder, "controller", "symbol, modId, name, meta, false, true", true))
                .addMethod(paletteMethod(builder, "block", "symbol, modId, name, meta, false, false", true))
                .addMethod(paletteMethod(builder, "air", "symbol, null, null, -1, false, false", false))
                .addMethod(paletteMethod(builder, "any", "symbol, null, null, -1, true, false", false))
                .addMethod(MethodSpec.methodBuilder("layers").addModifiers(Modifier.PUBLIC).returns(builder).varargs()
                        .addJavadoc("Layers bottom to top; each layer lists rows front to back.\n")
                        .addParameter(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers")
                        .addStatement("this.layers = layers").addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("rotatable").addModifiers(Modifier.PUBLIC).returns(builder)
                        .addParameter(TypeName.BOOLEAN, "rotatable").addStatement("this.rotatable = rotatable").addStatement("return this").build())
                .addMethod(MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(def)
                        .addStatement("return new $T(id, layers, palette, rotatable)", def).build())
                .build();
        TypeSpec t = TypeSpec.classBuilder(def).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("A multiblock structure as plain data (layers, palette, controller).\n"
                        + "<p>Local coordinates, seen by a player standing in front of the controller and looking at it:\n"
                        + "x: 0 = left .. width-1 = right; y: 0 = bottom .. height-1 = top; z: 0 = front face .. depth-1 = back.\n"
                        + "{@code layers[y][z]} is a string of {@code width} palette symbols, one per x.\n")
                .addType(partType).addType(builderType)
                .addField(String.class, "id", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "width", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "height", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "depth", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "ctrlX", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "ctrlY", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.INT, "ctrlZ", Modifier.PUBLIC, Modifier.FINAL)
                .addField(TypeName.BOOLEAN, "rotatable", Modifier.PUBLIC, Modifier.FINAL)
                .addField(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers", Modifier.PRIVATE, Modifier.FINAL)
                .addField(paletteMap, "palette", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE)
                        .addParameter(String.class, "id").addParameter(ArrayTypeName.of(ArrayTypeName.of(String.class)), "layers")
                        .addParameter(paletteMap, "palette").addParameter(TypeName.BOOLEAN, "rotatable")
                        .addStatement("this.id = id").addStatement("this.layers = layers")
                        .addStatement("this.palette = $T.unmodifiableMap(palette)", java.util.Collections.class)
                        .addStatement("this.rotatable = rotatable")
                        .addStatement("height = layers.length").addStatement("depth = layers[0].length").addStatement("width = layers[0][0].length()")
                        .addStatement("int cx = -1, cy = -1, cz = -1")
                        .beginControlFlow("for (int y = 0; y < height; y++)")
                        .beginControlFlow("for (int z = 0; z < depth; z++)")
                        .beginControlFlow("for (int x = 0; x < width; x++)")
                        .addStatement("$T p = palette.get(layers[y][z].charAt(x))", part)
                        .beginControlFlow("if (p == null)")
                        .addStatement("throw new IllegalArgumentException(id + $S + layers[y][z].charAt(x))", ": unknown symbol ").endControlFlow()
                        .beginControlFlow("if (p.controller)").addStatement("cx = x").addStatement("cy = y").addStatement("cz = z").endControlFlow()
                        .endControlFlow().endControlFlow().endControlFlow()
                        .addStatement("ctrlX = Math.max(cx, 0)").addStatement("ctrlY = Math.max(cy, 0)").addStatement("ctrlZ = Math.max(cz, 0)")
                        .build())
                .addMethod(MethodSpec.methodBuilder("builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(builder)
                        .addParameter(String.class, "id").addStatement("return new $T(id)", builder).build())
                .addMethod(MethodSpec.methodBuilder("partAt").addModifiers(Modifier.PUBLIC).returns(part)
                        .addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z")
                        .addStatement("return palette.get(layers[y][z].charAt(x))").build())
                .addMethod(MethodSpec.methodBuilder("palette").addModifiers(Modifier.PUBLIC).returns(paletteMap)
                        .addStatement("return palette").build())
                .addMethod(MethodSpec.methodBuilder("count").addModifiers(Modifier.PUBLIC).returns(TypeName.INT)
                        .addParameter(TypeName.CHAR, "symbol").addStatement("int n = 0")
                        .beginControlFlow("for (String[] layer : layers)").beginControlFlow("for (String row : layer)")
                        .beginControlFlow("for (int i = 0; i < row.length(); i++)")
                        .beginControlFlow("if (row.charAt(i) == symbol)").addStatement("n++").endControlFlow()
                        .endControlFlow().endControlFlow().endControlFlow().addStatement("return n").build())
                .addMethod(MethodSpec.methodBuilder("layers").addModifiers(Modifier.PUBLIC).returns(ArrayTypeName.of(ArrayTypeName.of(String.class)))
                        .addJavadoc("Copy of the raw layers, [y bottom..top][z front..back].\n")
                        .addStatement("String[][] copy = new String[height][]")
                        .beginControlFlow("for (int y = 0; y < height; y++)").addStatement("copy[y] = layers[y].clone()").endControlFlow()
                        .addStatement("return copy").build())
                .addMethod(MethodSpec.methodBuilder("toWorld").addModifiers(Modifier.PUBLIC).returns(ArrayTypeName.of(TypeName.INT))
                        .addJavadoc("World position of local (x, y, z) for a controller at (cx, cy, cz) whose front faces {@code facing} (2..5).\n")
                        .addParameter(TypeName.INT, "cx").addParameter(TypeName.INT, "cy").addParameter(TypeName.INT, "cz")
                        .addParameter(TypeName.INT, "facing").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                        .addParameter(TypeName.INT, "z")
                        .addStatement("$T front = $T.getOrientation(facing)", T.FORGE_DIRECTION, T.FORGE_DIRECTION)
                        .addStatement("int bx = -front.offsetX, bz = -front.offsetZ")
                        .addStatement("int rx = -bz, rz = bx")
                        .addStatement("int dx = x - ctrlX, dy = y - ctrlY, dz = z - ctrlZ")
                        .addStatement("return new int[] {cx + rx * dx + bx * dz, cy + dy, cz + rz * dx + bz * dz}").build())
                .addMethod(MethodSpec.methodBuilder("matches").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
                        .addJavadoc("Checks the structure in the world for a controller at (cx, cy, cz) facing {@code facing}.\n")
                        .addParameter(T.WORLD, "world").addParameter(TypeName.INT, "cx").addParameter(TypeName.INT, "cy")
                        .addParameter(TypeName.INT, "cz").addParameter(TypeName.INT, "facing")
                        .beginControlFlow("for (int y = 0; y < height; y++)").beginControlFlow("for (int z = 0; z < depth; z++)")
                        .beginControlFlow("for (int x = 0; x < width; x++)")
                        .addStatement("int[] p = toWorld(cx, cy, cz, facing, x, y, z)")
                        .beginControlFlow("if (!partAt(x, y, z).matches(world.getBlock(p[0], p[1], p[2]), world.getBlockMetadata(p[0], p[1], p[2])))")
                        .addStatement("return false").endControlFlow()
                        .endControlFlow().endControlFlow().endControlFlow().addStatement("return true").build())
                .build();
        ctx.out.addJava(def.packageName(), t);
    }

    private static MethodSpec paletteMethod(ClassName builder, String name, String args, boolean withBlock) {
        MethodSpec.Builder b = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).returns(builder)
                .addParameter(TypeName.CHAR, "symbol");
        if (withBlock) {
            b.addParameter(String.class, "modId").addParameter(String.class, "name").addParameter(TypeName.INT, "meta");
        }
        return b.addStatement("palette.put(symbol, new Part($L))", args).addStatement("return this").build();
    }
}
