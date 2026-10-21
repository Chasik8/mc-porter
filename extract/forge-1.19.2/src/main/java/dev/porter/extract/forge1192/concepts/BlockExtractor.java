package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.PatternPack;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import spoon.reflect.declaration.CtMethod;

/**
 * {@code block}: every entry of the block registry. Fields come from the BlockBehaviour.Properties recorder
 * (of(Material), strength, sound, lightLevel, requiresCorrectToolForDrops), the state definition
 * (createBlockStateDefinition + registerDefaultState) and newBlockEntity (→ blockEntity).
 */
public final class BlockExtractor {
    public static final String ID = "block/registry";
    static final String BLOCK_PROPS = "net.minecraft.world.level.block.state.BlockBehaviour$Properties";
    static final String STATE_BUILDER = "net.minecraft.world.level.block.state.StateDefinition$Builder";

    private BlockExtractor() {
    }

    public static void extract(ExtractContext ctx, List<PatternPack> packs) {
        for (Facts.Registration r : ctx.facts.in("block")) {
            if (r.id() == null) {
                ctx.sink.unknown("block", null, "block registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            Object v = r.value();
            Vals.Construction c = Vals.construction(v);
            if (c == null || c.vanillaType() == null && c.obj() == null) {
                ctx.sink.unknown("block", r.id(), "block value not computable: " + Vals.reason(v),
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            Drafts.Block d = new Drafts.Block();
            d.id = r.id();
            d.provenance = ctx.prov(r.site(), ID);
            if (c.obj() != null && Vals.isClassName(c.logicClass())) {
                d.logicClass = c.logicClass();
            }
            if (!r.conditions().isEmpty()) {
                d.extensions.put("porter-extract:conditions", Vals.strings(r.conditions()));
            }
            if (c.obj() != null && c.obj().taint != null) {
                d.confidence = 0.8;
                d.extensions.put("porter-extract:partial", Vals.F.stringNode(c.obj().taint));
            }
            VObj props = Vals.find(c.args(), BLOCK_PROPS);
            if (props != null) {
                applyProperties(ctx, props, d);
            }
            if (c.vanillaType() != null && !c.vanillaType().equals("net.minecraft.world.level.block.Block")) {
                d.extensions.put("porter-extract:vanillaClass", Vals.F.stringNode(c.vanillaType()));
            }
            d.translationKey = translationKey(ctx, v, d.id);
            if (c.obj() != null) {
                stateProperties(ctx, c.obj(), d);
                d.blockEntity = blockEntityOf(ctx, c.obj());
            }
            if (ctx.blockItemsReady()) {
                d.item = ctx.blockItems().get(d.id);
            }
            for (PatternPack p : packs) {
                p.enrichBlock(ctx, v, d);
            }
            ctx.sink.add(d.build());
        }
    }

    static void applyProperties(ExtractContext ctx, VObj props, Drafts.Block d) {
        if ("of".equals(props.factory) && !props.args.isEmpty()) {
            d.material = Vals.symbolOf(props.args.get(0), "Material");
        } else if ("copy".equals(props.factory) && !props.args.isEmpty()) {
            String from = ctx.idOf(props.args.get(0));
            if (from != null) {
                d.extensions.put("porter-extract:copiedProperties", Vals.F.stringNode(from));
            }
            d.confidence = d.confidence == null ? 0.9 : Math.min(d.confidence, 0.9);
        }
        for (V.Call call : props.calls) {
            List<Object> a = call.args();
            Object a0 = a.isEmpty() ? null : a.get(0);
            switch (call.name()) {
                case "strength" -> {
                    Double h = Vals.number(a0);
                    Double res = a.size() > 1 ? Vals.number(a.get(1)) : h;
                    if (h != null && res != null) {
                        d.hardness = h;
                        d.resistance = Math.max(0, res);
                    }
                }
                case "destroyTime" -> d.hardness = Vals.number(a0);
                case "explosionResistance" -> {
                    Double res = Vals.number(a0);
                    d.resistance = res == null ? null : Math.max(0, res);
                }
                case "instabreak" -> {
                    d.hardness = 0.0;
                    d.resistance = 0.0;
                }
                case "sound" -> d.soundType = Vals.symbolOf(a0, "SoundType");
                case "requiresCorrectToolForDrops" -> d.requiresTool = true;
                case "lightLevel" -> {
                    if (a0 instanceof V.Fn fn) {
                        Object lv = ctx.interp.callFn(fn, List.of(new V.Unknown("block state")));
                        Integer n = Vals.integer(lv);
                        if (n != null && n >= 0 && n <= 15) {
                            d.lightLevel = n;
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (d.hardness != null && d.hardness < -1) {
            d.hardness = null;
        }
        if (!props.calls.isEmpty() || !props.args.isEmpty()) {
            d.extensions.put("porter-extract:properties", Vals.calls(withFactory(props)));
        }
    }

    private static List<V.Call> withFactory(VObj props) {
        List<V.Call> l = new ArrayList<>();
        l.add(new V.Call(props.factory == null ? "new" : props.factory, props.args));
        l.addAll(props.calls);
        return l;
    }

    /** Block#getDescriptionId → "block.ns.path" unless the class overrides it. Null if not computable. */
    public static String translationKey(ExtractContext ctx, Object v, String id) {
        if (v instanceof Obj o) {
            Object r = ctx.interp.callIfDefined(o, "getDescriptionId", List.of());
            if (r != null) {
                return r instanceof String s && !s.isEmpty() ? s : null;
            }
        }
        return "block." + id.replace(':', '.');
    }

    static void stateProperties(ExtractContext ctx, Obj o, Drafts.Block d) {
        CtMethod<?> m = ctx.interp.sourceMethod(o.type, "createBlockStateDefinition", 1);
        if (m == null) {
            return;
        }
        VObj builder = new VObj(STATE_BUILDER, null, List.of());
        Object r = ctx.interp.call(m, o, List.of(builder));
        boolean partial = r instanceof V.Unknown;
        Map<String, V.Prop> props = new LinkedHashMap<>();
        for (V.Call c : builder.calls) {
            if (!c.name().equals("add")) {
                continue;
            }
            for (Object p : Vals.flatten(c.args())) {
                if (p instanceof V.Prop prop) {
                    props.putIfAbsent(prop.name(), prop);
                } else {
                    partial = true;
                }
            }
        }
        // a vanilla parent other than Block/BaseEntityBlock may add properties we cannot see
        String vsup = o.vanillaSuper;
        boolean superCalled = o.vanillaCalls.stream().anyMatch(c -> c.name().equals("createBlockStateDefinition"));
        if (superCalled && vsup != null && !vsup.equals("net.minecraft.world.level.block.Block")
                && !vsup.equals("net.minecraft.world.level.block.BaseEntityBlock")) {
            partial = true;
        }
        Object def = defaultState(o);
        Map<String, Object> defaults = def instanceof V.State st ? st.props() : Map.of();
        List<IrNode.Block.StateProperty> list = new ArrayList<>();
        for (V.Prop p : props.values()) {
            if (p.values() == null || p.values().isEmpty() || !p.name().matches("^[a-z0-9_]+$")) {
                partial = true;
                continue;
            }
            String dv = valueName(defaults.get(p.name()));
            if (dv != null && !p.values().contains(dv)) {
                dv = null;
            }
            list.add(new IrNode.Block.StateProperty(p.name(), p.type(), List.copyOf(new java.util.LinkedHashSet<>(p.values())), dv));
        }
        if (!list.isEmpty()) {
            d.stateProperties = list;
        }
        if (partial) {
            d.extensions.put("porter-extract:stateIncomplete", Vals.F.booleanNode(true));
            d.confidence = d.confidence == null ? 0.9 : Math.min(d.confidence, 0.9);
        }
    }

    static Object defaultState(Obj o) {
        for (int i = o.vanillaCalls.size() - 1; i >= 0; i--) {
            V.Call c = o.vanillaCalls.get(i);
            if (c.name().equals("registerDefaultState") && c.args().size() == 1) {
                return c.args().get(0);
            }
        }
        return null;
    }

    /** Serialized form of a state value (true, 3, north, enum serialized name). */
    static String valueName(Object v) {
        if (v instanceof Boolean || v instanceof Integer) {
            return String.valueOf(v);
        }
        if (v instanceof V.Sym s && s.owner().equals("net.minecraft.core.Direction")) {
            return s.name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static String blockEntityOf(ExtractContext ctx, Obj o) {
        CtMethod<?> m = ctx.interp.sourceMethod(o.type, "newBlockEntity", 2);
        if (m == null) {
            return null;
        }
        Object be = ctx.interp.call(m, o, List.of(new V.Unknown("pos"), new V.Unknown("state")));
        if (be instanceof VObj vo && vo.type.equals("net.minecraft.world.level.block.entity.BlockEntity")) {
            Object type = vo.fields.get("type");
            String id = ctx.idOf(type);
            if (id != null && "block_entity_type".equals(ctx.registryOf(type))) {
                return id;
            }
        }
        if (be instanceof Obj beo) {
            // constructed directly: new MyBlockEntity(pos, state) → the type passed to super(...)
            for (Object a : beo.superArgs) {
                String id = ctx.idOf(a);
                if (id != null && "block_entity_type".equals(ctx.registryOf(a))) {
                    return id;
                }
            }
        }
        return null;
    }
}
