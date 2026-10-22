package ic2cprofile.extract;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.concepts.MultiblockExtractor;
import dev.porter.extract.forge1192.concepts.MultiblockSupport;
import dev.porter.extract.forge1192.concepts.Vals;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;
import tools.jackson.databind.JsonNode;

/**
 * IC2C structures as data, from the 1.19.2 multiblock preview definitions ({@code IC2MultiBlocks}: suppliers that
 * build a {@code ic2.core.block.rendering.world.impl.MultiBlock} with addBlock/addUnformedBlock). The preview is run
 * by the interpreter; the structure is the <em>unformed</em> view (what the player places; build() fills it with
 * the default states of the formed blocks). The controller is the block of the wiki chapter that shows the preview.
 * Previews are what the in-game book shows, not the validator (testStructure): confidence 0.9, to be confirmed by
 * the runtime export.
 */
final class Ic2cMultiblocks {
    static final String ID = "multiblock/ic2c-preview";
    static final String HOLDER = "ic2.core.platform.registries.IC2MultiBlocks";
    static final String MB = "ic2.core.block.rendering.world.impl.MultiBlock";

    /** A preview use: expression building the MultiBlock and the chapter's controller expression. */
    record Use(CtExpression<?> preview, CtExpression<?> controller, String field) {
    }

    private Ic2cMultiblocks() {
    }

    /** MultiBlockObj(...) uses in wiki chapters, in source order; multiblock id per use. */
    static Map<CtExpression<?>, String> extract(ExtractContext ctx) {
        Map<CtExpression<?>, String> idsByPreview = new LinkedHashMap<>();
        CtType<?> holder = ctx.model.type(HOLDER);
        if (holder == null) {
            return idsByPreview;
        }
        List<Use> uses = new ArrayList<>();
        for (CtType<?> t : ctx.model.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (!inv.getExecutable().getSimpleName().equals("addChapter") || inv.getArguments().isEmpty()
                        || inv.getParent(CtType.class) != t) {
                    continue;
                }
                for (CtExpression<?> a : inv.getArguments()) {
                    CtConstructorCall<?> mbo = a instanceof CtConstructorCall<?> cc ? cc
                            : a instanceof CtInvocation<?> ai && ai.getTarget() instanceof CtConstructorCall<?> tc ? tc : null;
                    if (mbo == null || !mbo.getType().getSimpleName().equals("MultiBlockObj")
                            || mbo.getArguments().size() != 1) {
                        continue;
                    }
                    CtExpression<?> preview = mbo.getArguments().get(0);
                    String field = holderField(preview);
                    if (field != null) {
                        uses.add(new Use(preview, inv.getArguments().get(0), field));
                    }
                }
            }
        }
        java.util.Set<String> fieldsUsed = new java.util.HashSet<>();
        for (Use u : uses) {
            fieldsUsed.add(u.field());
            Object ctrlBlock = ctx.interp.evalIn(u.controller(), u.controller().getParent(CtType.class), null);
            String ctrlId = ctx.idOf(ctrlBlock);
            String id = ctrlId != null && ctrlId.startsWith(ctx.modId + ":") ? ctrlId
                    : ctx.modId + ":" + u.field().toLowerCase(Locale.ROOT);
            if (ctx.sink.has("multiblock", id)) {
                continue;
            }
            Object mb = ctx.interp.evalIn(u.preview(), u.preview().getParent(CtType.class), null);
            emit(ctx, id, mb, ctrlId, ctx.prov(u.preview(), ID), u.field());
            idsByPreview.put(u.preview(), id);
        }
        // previews no chapter shows
        for (CtField<?> f : holder.getFields()) {
            if (!f.isStatic() || fieldsUsed.contains(f.getSimpleName())) {
                continue;
            }
            String id = ctx.modId + ":" + f.getSimpleName().toLowerCase(Locale.ROOT);
            Object sup = ctx.interp.getStatic(HOLDER, f.getSimpleName());
            if (!(sup instanceof V.Fn fn)) {
                continue;
            }
            if (f.getType() != null && !f.getType().getSimpleName().equals("Supplier")) {
                ctx.sink.unknown("multiblock", id, "parameterised preview (" + f.getType().getSimpleName()
                        + ") not used by any wiki chapter", f.getSimpleName(), ctx.prov(f, ID));
                continue;
            }
            emit(ctx, id, ctx.interp.callFn(fn, List.of()), null, ctx.prov(f, ID), f.getSimpleName());
        }
        return idsByPreview;
    }

    static String holderField(CtExpression<?> e) {
        // IC2MultiBlocks.X.get() / IC2MultiBlocks.X.apply(arg)
        if (e instanceof CtInvocation<?> inv && inv.getTarget() instanceof CtFieldAccess<?> fa
                && fa.getVariable().getDeclaringType() != null
                && fa.getVariable().getDeclaringType().getQualifiedName().equals(HOLDER)) {
            return fa.getVariable().getSimpleName();
        }
        return null;
    }

    static void emit(ExtractContext ctx, String id, Object mb, String controllerBlock, Provenance prov, String field) {
        if (!(mb instanceof Obj o) || !ctx.interp.isSameOrSub(o.type, MB)) {
            ctx.sink.unknown("multiblock", id, "preview not computable: " + Vals.reason(mb), field, prov);
            return;
        }
        if (o.taint != null) {
            ctx.sink.unknown("multiblock", id, "preview incomplete: " + o.taint, field, prov);
            return;
        }
        Object unformed = MenuFields.field(ctx, o, "unformedStates");
        Object formed = MenuFields.field(ctx, o, "formedStates");
        V.MapV states = unformed instanceof V.MapV m && !m.map.isEmpty() ? m : formed instanceof V.MapV f ? f : null;
        if (states == null || states.map.isEmpty()) {
            ctx.sink.unknown("multiblock", id, "preview has no blocks (or they are not computable)", field, prov);
            return;
        }
        Map<V.Pos, MultiblockSupport.Ref> blocks = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<Object, Object> e : states.map.entrySet()) {
            if (!(e.getKey() instanceof Long l)) {
                ctx.sink.unknown("multiblock", id, "preview position not computable", field, prov);
                return;
            }
            V.Pos p = unpack(l);
            MultiblockSupport.Ref ref = MultiblockExtractor.ref(ctx, e.getValue());
            if (ref == null && e.getValue() instanceof V.State st && ctx.idOf(st.block()) != null) {
                ref = new MultiblockSupport.Ref(ctx.idOf(st.block()), null);
                dropped.add(p.x() + "," + p.y() + "," + p.z());
            }
            if (ref == null) {
                ctx.sink.unknown("multiblock", id, "preview block at " + p + " not computable: "
                        + Vals.reason(e.getValue()), field, prov);
                return;
            }
            blocks.put(p, ref);
        }
        V.Pos ctrl = null;
        String facing = null;
        if (controllerBlock != null) {
            for (Map.Entry<V.Pos, MultiblockSupport.Ref> e : blocks.entrySet()) {
                if (e.getValue().block().equals(controllerBlock)) {
                    if (ctrl != null) {
                        ctrl = null;
                        break; // ambiguous
                    }
                    ctrl = e.getKey();
                    facing = e.getValue().state() == null ? null : e.getValue().state().get("facing");
                }
            }
        }
        Map<String, JsonNode> ext = new LinkedHashMap<>();
        ext.put("ic2c:source", Vals.F.stringNode("preview " + HOLDER.substring(HOLDER.lastIndexOf('.') + 1) + "." + field));
        if (!dropped.isEmpty()) {
            ext.put("porter-extract:stateDropped", Vals.strings(dropped));
        }
        if (facing != null && !List.of("north", "south", "west", "east", "up", "down").contains(facing)) {
            facing = null;
        }
        IrNode.Multiblock node = MultiblockSupport.build(id, blocks, ctrl, facing, prov, 0.9, ext);
        if (node == null) {
            ctx.sink.unknown("multiblock", id, "too many distinct blocks for a palette", field, prov);
            return;
        }
        ctx.sink.add(node);
    }

    static V.Pos unpack(long l) {
        return new V.Pos((int) (l >> 38), (int) (l << 52 >> 52), (int) (l << 26 >> 38));
    }
}
