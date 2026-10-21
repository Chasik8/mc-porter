package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * {@code fluid} (Forge 1.19.2): source fluids of the fluid registry (ForgeFlowingFluid.Source with
 * ForgeFlowingFluid.Properties(type, still, flowing).bucket(..).block(..)); FluidType.Properties gives the
 * description id, density, viscosity, temperature and light level; IClientFluidTypeExtensions the textures.
 * Flowing variants are folded into their source.
 */
public final class FluidExtractor {
    public static final String ID = "fluid/forge-fluid-type";
    static final String FLUID_PROPS = "net.minecraftforge.fluids.ForgeFlowingFluid$Properties";
    static final String TYPE_PROPS = "net.minecraftforge.fluids.FluidType$Properties";

    /** Draft so packs can contribute. */
    public static final class Draft {
        public String id;
        public String translationKey;
        public String logicClass;
        public String stillTexture;
        public String flowingTexture;
        public String color;
        public Integer density;
        public Integer viscosity;
        public Integer temperature;
        public Integer luminosity;
        public Boolean gaseous;
        public String block;
        public String bucket;
        public Double confidence;
        public final Map<String, JsonNode> extensions = new TreeMap<>();
    }

    /** Pack hook for fluids that do not use ForgeFlowingFluid. */
    public interface Enricher {
        /** @return true if the pack handled the value (the generic path is then skipped) */
        boolean enrich(ExtractContext ctx, Object value, Draft d);
    }

    private FluidExtractor() {
    }

    public static void extract(ExtractContext ctx, List<Enricher> enrichers) {
        for (Facts.Registration r : ctx.facts.in("fluid")) {
            Provenance prov = ctx.prov(r.site(), ID);
            if (r.id() == null) {
                ctx.sink.unknown("fluid", null, "fluid registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            Vals.Construction c = Vals.construction(r.value());
            if (c == null) {
                ctx.sink.unknown("fluid", r.id(), "fluid value not computable: " + Vals.reason(r.value()),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            if (c.vanillaType() != null && c.vanillaType().endsWith("ForgeFlowingFluid$Flowing")) {
                ctx.note(ID, r.id() + ": flowing variant, folded into its source");
                continue;
            }
            Draft d = new Draft();
            d.id = r.id();
            if (c.obj() != null && Vals.isClassName(c.logicClass())) {
                d.logicClass = c.logicClass();
            }
            boolean handled = false;
            for (Enricher e : enrichers) {
                handled |= e.enrich(ctx, r.value(), d);
            }
            if (!handled) {
                VObj props = Vals.find(c.args(), FLUID_PROPS);
                if (props == null) {
                    ctx.sink.unknown("fluid", r.id(), "fluid is not a ForgeFlowingFluid with Properties ("
                            + c.vanillaType() + ", args " + c.args() + ")", Interpreter.snippet(r.site()), prov);
                    continue;
                }
                for (V.Call call : props.calls) {
                    Object a0 = call.args().isEmpty() ? null : call.args().get(0);
                    Object v = supplied(ctx, a0);
                    if (call.name().equals("bucket")) {
                        d.bucket = ctx.idOf(v);
                    } else if (call.name().equals("block")) {
                        d.block = ctx.idOf(v);
                    }
                }
                Object type = props.args.isEmpty() ? null : supplied(ctx, props.args.get(0));
                applyType(ctx, type, d);
            }
            ctx.sink.add(build(d, prov));
        }
    }

    public static IrNode.Fluid build(Draft d, Provenance prov) {
        Legacy.put(d.extensions, d.id);
        return new IrNode.Fluid(d.id, d.translationKey, d.logicClass, d.stillTexture, d.flowingTexture, d.color, d.density,
                d.viscosity, d.temperature, d.luminosity, d.gaseous, d.block, d.bucket, prov, d.confidence,
                d.extensions.isEmpty() ? null : d.extensions);
    }

    /** Reads a FluidType value (VObj FluidType(props) or a subclass constructed with props). */
    public static void applyType(ExtractContext ctx, Object type, Draft d) {
        Vals.Construction tc = Vals.construction(type);
        if (tc == null) {
            return;
        }
        VObj tp = Vals.find(tc.args(), TYPE_PROPS);
        if (tp != null) {
            for (V.Call call : tp.calls) {
                Object a0 = call.args().isEmpty() ? null : call.args().get(0);
                switch (call.name()) {
                    case "descriptionId" -> d.translationKey = Vals.string(a0);
                    case "density" -> d.density = Vals.integer(a0);
                    case "viscosity" -> {
                        Integer n = Vals.integer(a0);
                        d.viscosity = n != null && n >= 0 ? n : null;
                    }
                    case "temperature" -> d.temperature = Vals.integer(a0);
                    case "lightLevel" -> {
                        Integer n = Vals.integer(a0);
                        d.luminosity = n != null && n >= 0 && n <= 15 ? n : null;
                    }
                    default -> {
                    }
                }
            }
            if (d.density != null) {
                d.gaseous = d.density <= 0; // FluidType#isLighterThanAir
            }
        }
        if (type instanceof Obj to) {
            VObj rec = DataBuiltins.recorder();
            if (ctx.interp.callIfDefined(to, "initializeClient", List.of(rec)) != null) {
                for (V.Call call : rec.calls) {
                    if (call.name().equals("accept") && call.args().size() == 1 && call.args().get(0) instanceof Obj ext) {
                        d.stillTexture = texture(ctx.interp.callIfDefined(ext, "getStillTexture", List.of()));
                        d.flowingTexture = texture(ctx.interp.callIfDefined(ext, "getFlowingTexture", List.of()));
                        Object tint = ctx.interp.callIfDefined(ext, "getTintColor", List.of());
                        Integer argb = Vals.integer(tint);
                        if (argb != null) {
                            d.color = String.format("#%06X", argb & 0xFFFFFF);
                        }
                    }
                }
            }
        }
    }

    /** Value of a Supplier argument: a lambda/method ref is called, a RegistryObject resolved. */
    public static Object supplied(ExtractContext ctx, Object v) {
        if (v instanceof V.Fn fn) {
            return ctx.interp.callFn(fn, List.of());
        }
        if (v instanceof V.Holder h) {
            Facts.Registration r = ctx.facts.lookup(h.registry(), h.id());
            return r == null ? null : ctx.facts.resolve(ctx.interp, r);
        }
        return v;
    }

    static String texture(Object v) {
        return v instanceof V.ResLoc rl && Vals.isResourceId(rl.toString()) ? rl.toString() : null;
    }
}
