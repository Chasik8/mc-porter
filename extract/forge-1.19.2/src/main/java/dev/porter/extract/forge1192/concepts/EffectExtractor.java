package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * {@code effect}: mob_effect registry entries; category and colour from the MobEffect constructor
 * (MobEffectCategory, int colour), instant from isInstantenous, translation key from getDescriptionId.
 */
public final class EffectExtractor {
    public static final String ID = "effect/registry";

    private EffectExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        for (Facts.Registration r : ctx.facts.in("mob_effect")) {
            Provenance prov = ctx.prov(r.site(), ID);
            if (r.id() == null) {
                ctx.sink.unknown("effect", null, "effect registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            Vals.Construction c = Vals.construction(r.value());
            if (c == null || c.args().size() < 2) {
                ctx.sink.unknown("effect", r.id(), "effect value not computable: " + Vals.reason(r.value()),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            String cat = Vals.symbolOf(c.args().get(0), "MobEffectCategory");
            Integer color = Vals.integer(c.args().get(1));
            if (cat == null || !List.of("beneficial", "harmful", "neutral").contains(cat)) {
                ctx.sink.unknown("effect", r.id(), "effect category not computable", Interpreter.snippet(r.site()), prov);
                continue;
            }
            String key = "effect." + r.id().replace(':', '.');
            Boolean instant = null;
            Map<String, JsonNode> ext = new TreeMap<>();
            if (r.value() instanceof Obj o) {
                Object k = ctx.interp.callIfDefined(o, "getDescriptionId", List.of());
                if (k != null) {
                    key = k instanceof String s ? s : null;
                }
                Object inst = ctx.interp.callIfDefined(o, "isInstantenous", List.of());
                instant = inst instanceof Boolean b ? b : inst == null ? Boolean.FALSE : null;
                long mods = o.vanillaCalls.stream().filter(x -> x.name().equals("addAttributeModifier")).count();
                if (mods > 0) {
                    ext.put("porter-extract:attributeModifiersNotDecoded", Vals.F.numberNode((int) mods));
                }
            }
            Legacy.put(ext, r.id());
            String logic = c.obj() != null && Vals.isClassName(c.logicClass()) ? c.logicClass() : null;
            ctx.sink.add(new IrNode.Effect(r.id(), key, logic, cat,
                    color == null ? null : String.format("#%06X", color & 0xFFFFFF), instant, null, null, prov, null,
                    ext.isEmpty() ? null : ext));
        }
    }

    @SuppressWarnings("unused")
    private static boolean unknown(Object v) {
        return v instanceof V.Unknown;
    }
}
