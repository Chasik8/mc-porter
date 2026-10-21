package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code multiblock} (standard): Patchouli multiblocks, {@code PatchouliAPI.get().makeMultiblock(String[][] pattern,
 * Object... targets)} registered with {@code registerMultiblock(id, mb)}. Patchouli layers are top-to-bottom, rows
 * along z, chars along x; ' ' is air, '_' any block, '0' the centre (must be mapped). Forge itself has no
 * multiblock API: mod frameworks are handled by pattern packs.
 */
public final class MultiblockExtractor {
    public static final String ID = "multiblock/patchouli";

    private MultiblockExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        for (Facts.Event e : ctx.facts.events) {
            if (!e.kind().equals("patchouli-multiblock")) {
                continue;
            }
            Provenance prov = ctx.prov(e.site(), ID);
            Object idv = e.args().get(0);
            String id = idv instanceof V.ResLoc rl ? rl.toString() : null;
            if (id == null || !(e.args().get(1) instanceof VObj mb) || mb.args.isEmpty()) {
                ctx.sink.unknown("multiblock", id, "Patchouli multiblock id or definition not computable",
                        Interpreter.snippet(e.site()), prov);
                continue;
            }
            IrNode node = fromPattern(ctx, id, mb.args.get(0), mb.args.subList(1, mb.args.size()), prov);
            if (node instanceof IrNode.Unknown u) {
                ctx.sink.add("multiblock", u);
            } else {
                ctx.sink.add(node);
            }
        }
    }

    static IrNode fromPattern(ExtractContext ctx, String id, Object pattern, List<Object> targets, Provenance prov) {
        List<Object> flat = Vals.flatten(targets);
        if (!(pattern instanceof V.Seq layers) || flat.size() % 2 != 0) {
            return unknown(id, "pattern/targets not computable", prov);
        }
        Map<Character, MultiblockSupport.Ref> mapping = new LinkedHashMap<>();
        boolean anyChar = false;
        for (int i = 0; i < flat.size(); i += 2) {
            if (!(flat.get(i) instanceof Character c)) {
                return unknown(id, "target key is not a char", prov);
            }
            MultiblockSupport.Ref ref = ref(ctx, flat.get(i + 1));
            if (ref == null) {
                return unknown(id, "target '" + c + "' is not a block or block state", prov);
            }
            mapping.put(c, ref);
        }
        Map<V.Pos, MultiblockSupport.Ref> blocks = new LinkedHashMap<>();
        int ny = layers.items.size();
        V.Pos center = null;
        for (int li = 0; li < ny; li++) {
            if (!(layers.items.get(li) instanceof V.Seq rows)) {
                return unknown(id, "pattern layer not computable", prov);
            }
            int y = ny - 1 - li; // first layer is the top
            for (int z = 0; z < rows.items.size(); z++) {
                if (!(rows.items.get(z) instanceof String row)) {
                    return unknown(id, "pattern row not computable", prov);
                }
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    V.Pos p = new V.Pos(x, y, z);
                    if (c == '0') {
                        center = p;
                    }
                    if (c == '_') {
                        anyChar = true;
                        continue; // wildcard
                    }
                    if (c == ' ') {
                        blocks.put(p, new MultiblockSupport.Ref("minecraft:air", null));
                        continue;
                    }
                    MultiblockSupport.Ref r = mapping.get(c);
                    if (r == null) {
                        return unknown(id, "pattern char '" + c + "' has no target", prov);
                    }
                    blocks.put(p, r);
                }
            }
        }
        if (blocks.isEmpty()) {
            return unknown(id, "empty pattern", prov);
        }
        IrNode.Multiblock mb = MultiblockSupport.build(id, blocks, center, null, prov, null, null);
        if (mb == null) {
            return unknown(id, "too many distinct blocks", prov);
        }
        if (anyChar) {
            ctx.note(ID, id + ": '_' wildcards");
        }
        return mb;
    }

    /** Block or BlockState value → palette ref. */
    public static MultiblockSupport.Ref ref(ExtractContext ctx, Object v) {
        if (v instanceof V.State st) {
            String b = ctx.idOf(st.block());
            if (b == null) {
                return null;
            }
            if (st.props().containsKey(dev.porter.extract.forge1192.interp.McBuiltins.DEFAULT)) {
                return new MultiblockSupport.Ref(b, null); // the block's default state
            }
            Map<String, String> state = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : st.props().entrySet()) {
                String val = stateValue(ctx, e.getValue());
                if (val == null || e.getKey().startsWith("?")) {
                    return null;
                }
                state.put(e.getKey(), val);
            }
            return new MultiblockSupport.Ref(b, state);
        }
        String b = ctx.idOf(v);
        return b == null || !"block".equals(ctx.registryOf(v)) ? null : new MultiblockSupport.Ref(b, null);
    }

    public static String stateValue(ExtractContext ctx, Object v) {
        if (v instanceof Boolean || v instanceof Integer) {
            return String.valueOf(v);
        }
        if (v instanceof V.Sym s) {
            return s.name().toLowerCase(java.util.Locale.ROOT);
        }
        if (v instanceof V.Obj o && o.enumName != null) {
            Object sn = ctx.interp.callIfDefined(o, "getSerializedName", List.of());
            return sn instanceof String s ? s : null;
        }
        return null;
    }

    static IrNode.Unknown unknown(String id, String reason, Provenance prov) {
        return new IrNode.Unknown(id != null && Vals.isResourceId(id) ? id : null, reason, null, "multiblock", prov, null);
    }

    @SuppressWarnings("unused")
    private static List<Object> list() {
        return new ArrayList<>();
    }
}
