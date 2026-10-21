package dev.porter.extract.forge1192;

import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrIo;
import dev.porter.core.ir.IrNode;
import dev.porter.core.json.Json;
import dev.porter.extract.forge1192.concepts.BlockEntityExtractor;
import dev.porter.extract.forge1192.concepts.BlockExtractor;
import dev.porter.extract.forge1192.concepts.EffectExtractor;
import dev.porter.extract.forge1192.concepts.FluidExtractor;
import dev.porter.extract.forge1192.concepts.ItemExtractor;
import dev.porter.extract.forge1192.concepts.LangExtractor;
import dev.porter.extract.forge1192.concepts.MenuExtractor;
import dev.porter.extract.forge1192.concepts.ModExtractor;
import dev.porter.extract.forge1192.concepts.MultiblockExtractor;
import dev.porter.extract.forge1192.concepts.PacketExtractor;
import dev.porter.extract.forge1192.concepts.RecipeExtractor;
import dev.porter.extract.forge1192.concepts.SoundExtractor;
import dev.porter.extract.forge1192.concepts.TagExtractor;
import dev.porter.extract.forge1192.interp.Builtins;
import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.JdkBuiltins;
import dev.porter.extract.forge1192.interp.McBuiltins;
import dev.porter.extract.forge1192.interp.PatchouliBuiltins;
import dev.porter.extract.forge1192.interp.RecipeBuiltins;
import dev.porter.extract.forge1192.interp.Registrar;
import dev.porter.extract.forge1192.model.ModLayout;
import dev.porter.extract.forge1192.model.SourceModel;
import dev.porter.extract.forge1192.packs.Packs;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import spoon.reflect.code.CtInvocation;

/** Runs the whole extraction for one mod source tree. */
public final class Extractor {
    public static final String TOOL = "porter-extract 0.1.0";

    /** Options of one run. */
    public record Options(Path src, Path out, String modId, VanillaIndex vanilla, List<String> packs, boolean autoPacks,
            List<Path> classpath) {
    }

    /** Result: per-concept stats and the written files. */
    public record Result(Map<String, Object> report, List<Path> files, boolean valid) {
    }

    private Extractor() {
    }

    public static Result run(Options o) {
        ExtractContext ctx = analyse(o);
        return write(ctx, o.out());
    }

    /** Builds the model, runs registration code and all extractors (no output written). */
    public static ExtractContext analyse(Options o) {
        ModLayout layout = ModLayout.detect(o.src());
        List<ModExtractor.ModEntry> mods = ModExtractor.readToml(layout);
        SourceModel model = SourceModel.build(layout.javaRoots(), o.classpath());
        String modId = o.modId() != null ? o.modId() : !mods.isEmpty() && mods.get(0).modId() != null
                ? mods.get(0).modId() : guessFromAnnotation(model);
        if (modId == null) {
            modId = "unknownmod";
        }
        VanillaIndex vi = o.vanilla() == null ? VanillaIndex.empty() : o.vanilla();
        Interpreter in = new Interpreter(model, vi);
        Facts facts = new Facts();
        List<PatternPack> packs = new ArrayList<>();
        for (PatternPack p : Packs.all()) {
            if (o.packs().contains(p.id()) || o.autoPacks() && p.appliesTo(modId, model)) {
                packs.add(p);
            }
        }
        for (PatternPack p : packs) {
            for (Builtins b : p.builtins(facts, modId)) {
                in.addBuiltins(b);
            }
        }
        in.addBuiltins(new McBuiltins(facts, modId));
        in.addBuiltins(new RecipeBuiltins(facts));
        in.addBuiltins(new PatchouliBuiltins(facts));
        in.addBuiltins(new DataBuiltins());
        in.addBuiltins(new JdkBuiltins());
        Registrar reg = new Registrar(in);
        reg.analyse();
        ExtractContext ctx = new ExtractContext(layout, model, vi, in, facts, reg, modId);
        for (PatternPack p : packs) {
            p.configure(ctx);
        }
        reg.runAll(40_000_000L);
        facts.resolveAll(in);
        ctx.metrics.put("packs", packs.stream().map(PatternPack::id).toList());
        ctx.metrics.put("javaTypes", model.types().size());
        ctx.metrics.put("interceptSites", reg.sites().size());
        ctx.metrics.put("registrationRoots", reg.roots().size());
        ctx.metrics.put("registrations", facts.registrations.size());

        ModExtractor.extract(ctx, mods);
        pack(ctx, packs, "mod");
        LangExtractor.extract(ctx);
        pack(ctx, packs, "lang");
        ItemExtractor.extract(ctx, packs);
        pack(ctx, packs, "item");
        BlockExtractor.extract(ctx, packs);
        pack(ctx, packs, "block");
        List<BlockEntityExtractor.Enricher> beEnrichers = new ArrayList<>();
        List<PacketExtractor.EncoderOverride> encoders = new ArrayList<>();
        List<FluidExtractor.Enricher> fluids = new ArrayList<>();
        for (PatternPack p : packs) {
            if (p instanceof BlockEntityExtractor.Enricher e) {
                beEnrichers.add(e);
            }
            if (p instanceof PacketExtractor.EncoderOverride e) {
                encoders.add(e);
            }
            if (p instanceof FluidExtractor.Enricher e) {
                fluids.add(e);
            }
        }
        BlockEntityExtractor.extract(ctx, packs, beEnrichers);
        pack(ctx, packs, "blockEntity");
        RecipeExtractor.extract(ctx);
        pack(ctx, packs, "recipe");
        MenuExtractor.extract(ctx);
        pack(ctx, packs, "menu");
        PacketExtractor.extract(ctx, encoders);
        pack(ctx, packs, "packet");
        FluidExtractor.extract(ctx, fluids);
        pack(ctx, packs, "fluid");
        SoundExtractor.extract(ctx);
        pack(ctx, packs, "sound");
        EffectExtractor.extract(ctx);
        pack(ctx, packs, "effect");
        TagExtractor.extract(ctx);
        pack(ctx, packs, "tag");
        MultiblockExtractor.extract(ctx);
        pack(ctx, packs, "multiblock");
        uncovered(ctx);
        pack(ctx, packs, "final");
        return ctx;
    }

    private static void pack(ExtractContext ctx, List<PatternPack> packs, String concept) {
        for (PatternPack p : packs) {
            ctx.interp.resetBudget(5_000_000L);
            p.extract(ctx, concept);
        }
    }

    private static String guessFromAnnotation(SourceModel model) {
        for (var t : model.types()) {
            for (var a : t.getAnnotations()) {
                if (a.getAnnotationType().getQualifiedName().equals("net.minecraftforge.fml.common.Mod")
                        && a.getValues().get("value") instanceof spoon.reflect.code.CtLiteral<?> lit
                        && lit.getValue() instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Registration sites the interpreter never reached become unknown nodes. */
    private static void uncovered(ExtractContext ctx) {
        int n = 0;
        for (CtInvocation<?> site : ctx.registrar.sites()) {
            if (ctx.interp.wasReached(site)) {
                continue;
            }
            n++;
            String why = ctx.interp.skipReason(site);
            String snippet = Interpreter.snippet(site);
            String call = ctx.vanilla.official(site.getExecutable().getSimpleName());
            String hint = call.contains("Recipe") || call.equals("registerUUShape") || call.equals("registerBrew")
                    || call.equals("save") ? "recipe"
                    : call.equals("registerMessage") || call.equals("add") && snippet.contains("messageBuilder") ? "packet"
                    : call.equals("registerMultiblock") ? "multiblock"
                    : call.equals("registerTags") ? "tag" : hint(snippet);
            ctx.sink.unknown(hint, null, "registration call never executed by the analysis"
                    + (why == null ? " (no root reaches it)" : ": " + why), snippet, ctx.prov(site, "registrar"));
        }
        ctx.metrics.put("uncoveredSites", n);
    }

    /** Suspected concept of an unreached registration call, from the registry named in it. */
    static String hint(String snippet) {
        String s = snippet.toUpperCase(Locale.ROOT);
        if (s.contains("BLOCK_ENTITY")) {
            return "blockEntity";
        }
        if (s.contains("BLOCKS") || s.contains("BLOCK_REGISTRY")) {
            return "block";
        }
        if (s.contains("ITEMS") || s.contains("ITEM_REGISTRY")) {
            return "item";
        }
        if (s.contains("MENU")) {
            return "menu";
        }
        if (s.contains("FLUID")) {
            return "fluid";
        }
        if (s.contains("SOUND")) {
            return "sound";
        }
        if (s.contains("MOB_EFFECT")) {
            return "effect";
        }
        if (s.contains("REGISTERMESSAGE") || s.contains("MESSAGEBUILDER")) {
            return "packet";
        }
        if (s.contains("RECIPE")) {
            return "recipe";
        }
        return null;
    }

    /** Writes one IR file per concept (and one for unhinted unknowns) plus extract-report.json. */
    public static Result write(ExtractContext ctx, Path out) {
        List<Path> files = new ArrayList<>();
        IrDocument.Source source = new IrDocument.Source(ExtractContext.VERSION,
                ctx.modId.matches("^[a-z][a-z0-9_]{1,63}$") ? ctx.modId : null, TOOL);
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Map.Entry<String, List<IrNode>> e : ctx.sink.byConcept().entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            Path f = out.resolve(ctx.modId + "." + e.getKey() + ".ir.json");
            IrIo.write(f, new IrDocument(source, e.getValue()));
            files.add(f);
        }
        if (!ctx.sink.unhinted().isEmpty()) {
            Path f = out.resolve(ctx.modId + ".unknown.ir.json");
            IrIo.write(f, new IrDocument(source, ctx.sink.unhinted()));
            files.add(f);
        }
        Map<String, Object> report = report(ctx);
        Path rf = out.resolve("extract-report.json");
        try {
            Files.writeString(rf, Json.json().writeValueAsString(report).replace("\r\n", "\n") + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.add(rf);
        return new Result(report, files, true);
    }

    public static Map<String, Object> report(ExtractContext ctx) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("modId", ctx.modId);
        r.put("version", ExtractContext.VERSION);
        Map<String, Object> concepts = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : ctx.sink.stats().entrySet()) {
            int rec = e.getValue()[0];
            int unk = e.getValue()[1];
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("recognised", rec);
            c.put("unknown", unk);
            c.put("unknownShare", rec + unk == 0 ? 0.0 : Math.round(10000.0 * unk / (rec + unk)) / 10000.0);
            concepts.put(e.getKey(), c);
        }
        r.put("concepts", concepts);
        r.put("unhintedUnknown", ctx.sink.unhinted().size());
        Map<String, Integer> reasons = new TreeMap<>();
        for (IrNode n : ctx.sink.all()) {
            if (n instanceof IrNode.Unknown u) {
                String key = (u.conceptHint() == null ? "-" : u.conceptHint()) + " | " + normalise(u.reason());
                reasons.merge(key, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(reasons.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue() != 0 ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        Map<String, Integer> topMap = new LinkedHashMap<>();
        for (var e : top.subList(0, Math.min(60, top.size()))) {
            topMap.put(e.getKey(), e.getValue());
        }
        r.put("topUnknownReasons", topMap);
        r.put("metrics", ctx.metrics);
        r.put("duplicates", ctx.sink.duplicates());
        r.put("registrarLog", ctx.registrar.log());
        r.put("notes", ctx.notes);
        return r;
    }

    /** Reason without ids/numbers, for grouping. */
    static String normalise(String reason) {
        String s = reason.replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+", "<id>").replaceAll("\\b\\d+\\b", "<n>");
        return s.length() > 140 ? s.substring(0, 140) : s;
    }
}
