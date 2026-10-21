package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.PatternPack;
import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.declaration.CtMethod;
import tools.jackson.databind.JsonNode;

/**
 * {@code blockEntity}: entries of the block_entity_type registry. Blocks come from BlockEntityType.Builder.of(factory,
 * blocks...) or from blocks whose newBlockEntity creates this type; the logic class from the factory; ticking from
 * the blocks' getTicker on a client and a server level; persisted NBT keys from saveAdditional run on a recorder.
 */
public final class BlockEntityExtractor {
    public static final String ID = "blockEntity/registry";

    /** Extra hook for packs: inventory size, menu. */
    public interface Enricher {
        void enrich(ExtractContext ctx, Object typeValue, Obj instance, Map<String, Object> fields);
    }

    private BlockEntityExtractor() {
    }

    public static void extract(ExtractContext ctx, List<PatternPack> packs, List<Enricher> enrichers) {
        Map<String, List<String>> blocksByBe = new LinkedHashMap<>();
        Map<String, Object> blockValueByBe = new LinkedHashMap<>();
        for (IrNode n : ctx.sink.byConcept().get("block")) {
            if (n instanceof IrNode.Block b && b.blockEntity() != null) {
                blocksByBe.computeIfAbsent(b.blockEntity(), k -> new ArrayList<>()).add(b.id());
                Facts.Registration br = ctx.facts.lookup("block", b.id());
                if (br != null) {
                    blockValueByBe.putIfAbsent(b.blockEntity(), br.value());
                }
            }
        }
        for (Facts.Registration r : ctx.facts.in("block_entity_type")) {
            if (r.id() == null) {
                ctx.sink.unknown("blockEntity", null, "block entity type registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            Object v = r.value();
            Object factory = null;
            Set<String> blocks = new LinkedHashSet<>(blocksByBe.getOrDefault(r.id(), List.of()));
            if (v instanceof VObj vo && vo.type.endsWith("BlockEntityType") && !vo.args.isEmpty()
                    && vo.args.get(0) instanceof VObj b && b.type.endsWith("BlockEntityType$Builder")) {
                if (!b.args.isEmpty()) {
                    factory = b.args.get(0);
                }
                for (Object bl : Vals.flatten(b.args.subList(Math.min(1, b.args.size()), b.args.size()))) {
                    String bid = ctx.idOf(bl);
                    if (bid != null) {
                        blocks.add(bid);
                    }
                }
            } else if (v instanceof Obj o && !o.superArgs.isEmpty()) {
                factory = o.superArgs.get(0);
            } else if (v instanceof VObj vo && vo.type.endsWith("BlockEntityType") && !vo.args.isEmpty()) {
                factory = vo.args.get(0);
            }
            Obj instance = null;
            if (factory instanceof V.Fn fn) {
                Object inst = ctx.interp.callFn(fn, List.of(new V.Unknown("pos"), new V.Unknown("state")));
                if (inst instanceof Obj o) {
                    instance = o;
                }
            }
            if (instance == null || !Vals.isClassName(instance.typeName())) {
                ctx.sink.unknown("blockEntity", r.id(), "block entity factory not computable ("
                        + Interpreter.describe(factory) + ")", Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            if (blocks.isEmpty()) {
                ctx.sink.unknown("blockEntity", r.id(), "no block creates this block entity type",
                        instance.typeName(), ctx.prov(r.site(), ID));
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Enricher e : enrichers) {
                e.enrich(ctx, v, instance, fields);
            }
            String ticking = ticking(ctx, blockValueByBe.get(r.id()), v);
            List<IrNode.BlockEntity.NbtKey> nbt = nbt(ctx, instance);
            Map<String, JsonNode> ext = new LinkedHashMap<>();
            if (!r.conditions().isEmpty()) {
                ext.put("porter-extract:conditions", Vals.strings(r.conditions()));
            }
            ctx.sink.add(new IrNode.BlockEntity(r.id(), List.copyOf(blocks), instance.typeName(), ticking,
                    (String) fields.get("menu"), (Integer) fields.get("inventorySize"), nbt, ctx.prov(r.site(), ID), null,
                    ext.isEmpty() ? null : ext));
        }
    }

    /** server/client/both/none from getTicker on both sides; null if undecidable. */
    static String ticking(ExtractContext ctx, Object block, Object type) {
        if (!(block instanceof Obj b)) {
            return block == null ? null : "none";
        }
        CtMethod<?> m = ctx.interp.sourceMethod(b.type, "getTicker", 3);
        if (m == null) {
            return "none";
        }
        Object client = ctx.interp.call(m, b, List.of(DataBuiltins.level(true), new V.Unknown("state"), type));
        Object server = ctx.interp.call(m, b, List.of(DataBuiltins.level(false), new V.Unknown("state"), type));
        if (client instanceof V.Unknown || server instanceof V.Unknown) {
            return null;
        }
        boolean c = client != V.NULL;
        boolean s = server != V.NULL;
        return c && s ? "both" : s ? "server" : c ? "client" : "none";
    }

    static List<IrNode.BlockEntity.NbtKey> nbt(ExtractContext ctx, Obj instance) {
        CtMethod<?> m = ctx.interp.sourceMethod(instance.type, "saveAdditional", 1);
        if (m == null) {
            return null;
        }
        VObj tag = DataBuiltins.compound();
        // keys only: plain values (numbers, flags, strings) become unknown so that every conditional put is seen
        Obj probe = new Obj(instance.type);
        probe.vanillaSuper = instance.vanillaSuper;
        probe.superArgs = instance.superArgs;
        for (Map.Entry<String, Object> e : instance.fields.entrySet()) {
            Object v = e.getValue();
            boolean structured = v instanceof Obj || v instanceof VObj || v instanceof V.Seq || v instanceof V.MapV;
            probe.fields.put(e.getKey(), structured ? v : new V.Unknown("value of " + e.getKey()));
        }
        boolean saved = ctx.interp.bothBranches;
        ctx.interp.bothBranches = true;
        try {
            ctx.interp.call(m, probe, List.of(tag));
        } finally {
            ctx.interp.bothBranches = saved;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (V.Call c : tag.calls) {
            if (c.name().equals("$itemStacks")) {
                keys.putIfAbsent("Items", "itemStacks");
                continue;
            }
            if (c.args().size() != 2 || !(c.args().get(0) instanceof String key) || key.isEmpty()) {
                continue;
            }
            String type = DataBuiltinsTypes.of(c.name(), c.args().get(1));
            if (type != null) {
                keys.putIfAbsent(key, type);
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        List<IrNode.BlockEntity.NbtKey> out = new ArrayList<>();
        keys.forEach((k, t) -> out.add(new IrNode.BlockEntity.NbtKey(k, t)));
        return out;
    }

    /** Maps a recorded put call to the IR nbt type. */
    static final class DataBuiltinsTypes {
        static String of(String put, Object value) {
            switch (put) {
                case "putByte": return "byte";
                case "putShort": return "short";
                case "putInt": return "int";
                case "putLong": return "long";
                case "putFloat": return "float";
                case "putDouble": return "double";
                case "putString": return "string";
                case "putBoolean": return "boolean";
                case "putIntArray", "putUUID": return "intArray";
                case "putByteArray": return "byteArray";
                case "putLongArray": return "longArray";
                case "put":
                    if (value instanceof VObj vo && vo.type.equals(DataBuiltins.COMPOUND)) {
                        boolean fluid = vo.calls.stream().anyMatch(c -> c.name().equals("$fluidStack"));
                        return fluid ? "fluidStack" : "compound";
                    }
                    if (value instanceof VObj vo && vo.type.equals(DataBuiltins.LIST)) {
                        return "list";
                    }
                    return null;
                default:
                    return null;
            }
        }
    }
}
