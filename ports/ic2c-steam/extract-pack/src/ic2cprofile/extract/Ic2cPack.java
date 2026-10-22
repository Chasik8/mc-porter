package ic2cprofile.extract;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.PatternPack;
import dev.porter.extract.forge1192.concepts.BlockEntityExtractor;
import dev.porter.extract.forge1192.concepts.Drafts;
import dev.porter.extract.forge1192.concepts.FluidExtractor;
import dev.porter.extract.forge1192.concepts.PacketExtractor;
import dev.porter.extract.forge1192.concepts.TagExtractor;
import dev.porter.extract.forge1192.concepts.Vals;
import dev.porter.extract.forge1192.interp.Builtins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.model.SourceModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Pattern pack for IC2 Classic 1.19.2 (mod id {@code ic2}). Contains recognisers only (names of IC2C helper
 * methods and how its GUI/structure/recipe frameworks map to IR); no IC2C code or data.
 *
 * <p>The registry helpers (IC2Blocks#registerBlock, IC2Items#registerItem, IC2Tiles#createTile...) need nothing
 * special: they end in ForgeRegistries.X.register and are run by the interpreter like any registration code.
 */
public final class Ic2cPack implements PatternPack, BlockEntityExtractor.Enricher, PacketExtractor.EncoderOverride,
        FluidExtractor.Enricher {
    private final Map<String, Obj> beInstances = new LinkedHashMap<>();
    private Map<CtExpression<?>, String> multiblockIds = Map.of();

    @Override
    public String id() {
        return "ic2c";
    }

    @Override
    public boolean appliesTo(String modId, SourceModel model) {
        return "ic2".equals(modId) && model.type("ic2.core.IC2") != null;
    }

    @Override
    public List<Builtins> builtins(Facts facts, String modId) {
        return List.of(new Ic2cBuiltins(facts));
    }

    @Override
    public void configure(ExtractContext ctx) {
        // assets/<ns>/lang/<prefix>_<locale>.json for every Platform#addLangFile(new ResourceLocation(ns, prefix))
        for (CtType<?> t : ctx.model.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (!inv.getExecutable().getSimpleName().equals("addLangFile") || inv.getArguments().size() != 1
                        || inv.getParent(CtType.class) != t) {
                    continue;
                }
                Object rl = ctx.interp.evalIn(inv.getArguments().get(0), t, null);
                if (rl instanceof V.ResLoc r) {
                    ctx.langPrefixes.add(r.namespace() + ":" + r.path());
                } else {
                    ctx.note("ic2c/lang", "addLangFile argument not computable at " + ctx.prov(inv, "ic2c/lang").file());
                }
            }
        }
    }

    // ---- blocks / items ------------------------------------------------------------------------------------

    @Override
    public void enrichBlock(ExtractContext ctx, Object value, Drafts.Block d) {
        if (!(value instanceof Obj o)) {
            return;
        }
        Object tools = o.fields.containsKey("harvestLevels") ? o.fields.get("harvestLevels") : o.fields.get("harvestMap");
        if (tools instanceof V.MapV m && !m.map.isEmpty()) {
            List<IrNode.Block.Harvest> hs = new ArrayList<>();
            for (Object t : m.map.values()) {
                if (t instanceof Obj tool && tool.fields.get("type") instanceof Obj type && type.enumName != null
                        && tool.fields.get("level") instanceof Integer lvl) {
                    String name = type.enumName.toLowerCase(Locale.ROOT);
                    if (List.of("pickaxe", "axe", "shovel", "hoe", "sword", "shears").contains(name)) {
                        hs.add(new IrNode.Block.Harvest(name, lvl));
                    }
                }
            }
            if (!hs.isEmpty()) {
                d.harvest = hs.get(0);
            }
            if (hs.size() > 1) {
                d.extensions.put("ic2c:harvestTools", Vals.F.numberNode(hs.size()));
            }
        }
        Object rarity = o.fields.get("rarityOverride");
        if (rarity instanceof V.Sym s) {
            d.extensions.put("ic2c:rarity", Vals.F.stringNode(s.name().toLowerCase(Locale.ROOT)));
        }
        String sprite = Ic2cTextures.sprite(ctx, o);
        if (sprite != null && d.textures == null) {
            d.textures = new java.util.LinkedHashMap<>(Map.of("all", sprite));
        }
        Set<String> packages = Ic2cTextures.packages(ctx, o);
        if (packages.size() == 1) {
            d.extensions.put("porter:texturePackage", Vals.F.stringNode(packages.iterator().next()));
        } else if (packages.size() > 1) {
            d.extensions.put("porter-extract:texturePackages", Vals.strings(List.copyOf(packages)));
            String primary = Ic2cTextures.primary(ctx, o);
            if (primary != null && sprite == null) {
                d.extensions.put("porter:texturePackage", Vals.F.stringNode(primary)); // the model's own provider
            }
        }
    }

    @Override
    public void enrichItem(ExtractContext ctx, Object value, Drafts.Item d) {
        if (value instanceof Obj o && d.textures == null && d.id != null && d.id.contains(":")) {
            String sprite = Ic2cTextures.itemSprite(ctx, o, d.id.substring(0, d.id.indexOf(':')));
            if (sprite != null) {
                d.textures = new java.util.LinkedHashMap<>(Map.of("layer0", sprite));
            }
        }
        if (value instanceof Obj o && o.fields.get("rarityOverride") instanceof V.Sym s && d.rarity == null) {
            String r = s.name().toLowerCase(Locale.ROOT);
            if (List.of("common", "uncommon", "rare", "epic").contains(r)) {
                d.rarity = r;
            }
        }
    }

    // ---- block entities ------------------------------------------------------------------------------------

    @Override
    public void enrich(ExtractContext ctx, Object typeValue, Obj instance, Map<String, Object> fields) {
        String id = ctx.idOf(typeValue);
        if (id == null) {
            return;
        }
        beInstances.putIfAbsent(id, instance);
        if (ctx.interp.isSameOrSub(instance.type, "ic2.core.inventory.base.IHasInventory")) {
            Integer size = Vals.integer(ctx.interp.callIfDefined(instance, "getSlotCount", List.of()));
            if (size != null && size >= 0) {
                fields.put("inventorySize", size);
            }
        }
        if (ctx.interp.sourceMethod(instance.type, "createContainer", 4) != null) {
            fields.put("menu", id);
        }
    }

    // ---- packets, fluids -----------------------------------------------------------------------------------

    @Override
    public String encodeMethodFor(ExtractContext ctx, CtType<?> messageClass) {
        return ctx.interp.isSameOrSub(messageClass, "ic2.core.networking.packets.IC2Packet") ? "write" : null;
    }

    @Override
    public boolean enrich(ExtractContext ctx, Object value, FluidExtractor.Draft d) {
        if (!(value instanceof Obj o) || !ctx.interp.isSameOrSub(o.type, "ic2.core.fluid.IC2Fluid")) {
            return false;
        }
        FluidExtractor.applyType(ctx, o.fields.get("fluidType"), d);
        Object bucket = ctx.interp.callIfDefined(o, "getBucket", List.of());
        String b = ctx.itemIdOf(bucket);
        if (b != null && !b.equals("minecraft:air")) {
            d.bucket = b;
        }
        return true;
    }

    // ---- concept extraction --------------------------------------------------------------------------------

    @Override
    public void extract(ExtractContext ctx, String concept) {
        switch (concept) {
            case "recipe" -> Ic2cRecipes.extract(ctx);
            case "menu" -> Ic2cMenus.extract(ctx, beInstances);
            case "sound" -> sounds(ctx);
            case "tag" -> tags(ctx);
            case "multiblock" -> multiblockIds = Ic2cMultiblocks.extract(ctx);
            case "final" -> ctx.metrics.put("ic2cWikiChapters", Ic2cWiki.extract(ctx, multiblockIds));
            default -> {
            }
        }
    }

    /**
     * IC2C plays sound files directly (IC2Sounds: ResourceLocations of .ogg files, no SoundEvent): one sound node
     * per file, id {@code ns:<path with dots>}, extension ic2c:direct.
     */
    static void sounds(ExtractContext ctx) {
        CtType<?> t = ctx.model.type("ic2.core.platform.registries.IC2Sounds");
        if (t == null) {
            return;
        }
        for (CtField<?> f : t.getFields()) {
            if (!f.isStatic()) {
                continue;
            }
            Object v = ctx.interp.getStatic(t.getQualifiedName(), f.getSimpleName());
            Provenance prov = ctx.prov(f, "sound/ic2c-direct");
            if (!(v instanceof V.ResLoc rl) || !rl.path().startsWith("sounds/") || !rl.path().endsWith(".ogg")) {
                ctx.sink.unknown("sound", null, "IC2Sounds field is not a sounds/*.ogg location", f.getSimpleName(), prov);
                continue;
            }
            String file = rl.path().substring("sounds/".length(), rl.path().length() - ".ogg".length());
            String id = rl.namespace() + ":" + file.replace('/', '.');
            if (!Vals.isResourceId(id) || ctx.sink.has("sound", id)) {
                continue;
            }
            ctx.sink.add(new IrNode.Sound(id, null, null, null,
                    List.of(new IrNode.Sound.SoundFile(rl.namespace() + ":" + file, null, null, null, null)), prov, null,
                    Map.of("ic2c:direct", Vals.F.stringNode(f.getSimpleName()))));
        }
    }

    /** IC2Tags#registerTags(new ResourceLocation(ns, "tags/<items|blocks|fluids>/<path>"), registry, entries...). */
    static void tags(ExtractContext ctx) {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        Map<String, String> registryOf = new LinkedHashMap<>();
        Map<String, Provenance> provOf = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (Facts.Event e : ctx.facts.events) {
            if (!e.kind().equals("ic2c-tag") || e.args().size() < 2) {
                continue;
            }
            Provenance prov = ctx.prov(e.site(), "tag/ic2c-runtime-pack");
            if (!(e.args().get(0) instanceof V.ResLoc key) || !key.path().startsWith("tags/")) {
                ctx.sink.unknown("tag", null, "IC2C tag key not computable: " + Vals.reason(e.args().get(0)),
                        Interpreter.snippet(e.site()), prov);
                continue;
            }
            String rest = key.path().substring("tags/".length());
            int slash = rest.indexOf('/');
            String folder = slash < 0 ? rest : rest.substring(0, slash);
            String registry = switch (folder) {
                case "items" -> "item";
                case "blocks" -> "block";
                case "fluids" -> "fluid";
                case "entity_types" -> "entity_type";
                default -> null;
            };
            String id = key.namespace() + ":" + (slash < 0 ? "" : rest.substring(slash + 1));
            if (registry == null || slash < 0 || !Vals.isResourceId(id)) {
                ctx.sink.unknown("tag", null, "IC2C tag folder '" + folder + "' has no IR registry", key.toString(), prov);
                continue;
            }
            String k = registry + "|" + id;
            registryOf.put(k, registry);
            provOf.putIfAbsent(k, prov);
            Set<String> set = values.computeIfAbsent(k, x -> new LinkedHashSet<>());
            for (Object entry : Vals.flatten(e.args().subList(2, e.args().size()))) {
                String vid = registry.equals("item") ? ctx.itemIdOf(entry) : ctx.idOf(entry);
                if (vid == null && registry.equals("item") && "block".equals(ctx.registryOf(entry))) {
                    // IC2C writes the block's registry name into item tags: it names the block item
                    vid = ctx.idOf(entry);
                }
                if (vid == null) {
                    bad.putIfAbsent(k, "tag entry not computable: " + Vals.reason(entry));
                    continue;
                }
                set.add(vid);
            }
        }
        for (Map.Entry<String, Set<String>> e : values.entrySet()) {
            String k = e.getKey();
            String id = k.substring(k.indexOf('|') + 1);
            if (bad.containsKey(k)) {
                ctx.sink.unknown("tag", id, bad.get(k), null, provOf.get(k));
                continue;
            }
            if (ctx.sink.has("tag", id)) {
                ctx.note("tag/ic2c-runtime-pack", id + ": defined twice (data file or another registry); first kept");
                continue;
            }
            List<IrNode.TagValue> vals = new ArrayList<>();
            e.getValue().forEach(v -> vals.add(IrNode.TagValue.of(v)));
            ctx.sink.add(new IrNode.Tag(id, registryOf.get(k), null, vals, TagExtractor.oreDict(id), provOf.get(k), null,
                    Map.of("ic2c:runtimePack", Vals.F.booleanNode(true))));
        }
    }
}
