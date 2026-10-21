package dev.porter.extract.forge1192;

import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.Registrar;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.model.ModLayout;
import dev.porter.extract.forge1192.model.SourceModel;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.declaration.CtElement;

/** Everything a concept extractor needs. */
public final class ExtractContext {
    public static final String VERSION = "forge-1.19.2";

    public final ModLayout layout;
    public final SourceModel model;
    public final VanillaIndex vanilla;
    public final Interpreter interp;
    public final Facts facts;
    public final Registrar registrar;
    public final NodeSink sink = new NodeSink();
    public final String modId;
    /** Free-form diagnostics per extractor (written to the run report). */
    public final Map<String, List<String>> notes = new LinkedHashMap<>();
    /** Extra lang file prefixes loaded by the mod ("ns:prefix" → assets/ns/lang/prefix_locale.json), from packs. */
    public final java.util.Set<String> langPrefixes = new java.util.TreeSet<>();
    /** Extra per-concept metrics (e.g. subsystem coverage). */
    public final Map<String, Object> metrics = new LinkedHashMap<>();

    public ExtractContext(ModLayout layout, SourceModel model, VanillaIndex vanilla, Interpreter interp, Facts facts,
            Registrar registrar, String modId) {
        this.layout = layout;
        this.model = model;
        this.vanilla = vanilla;
        this.interp = interp;
        this.facts = facts;
        this.registrar = registrar;
        this.modId = modId;
    }

    public void note(String extractor, String message) {
        notes.computeIfAbsent(extractor, k -> new ArrayList<>()).add(message);
    }

    public Provenance prov(CtElement e, String extractor) {
        if (e == null || e.getPosition() == null || !e.getPosition().isValidPosition()) {
            return new Provenance("<unknown>", 0, VERSION, "source", extractor);
        }
        Path file = e.getPosition().getFile().toPath();
        return new Provenance(layout.relative(file), e.getPosition().getLine(), VERSION, "source", extractor);
    }

    public Provenance provResource(Path file, String extractor) {
        return new Provenance(layout.relative(file), 0, VERSION, "resource", extractor);
    }

    /**
     * Registry id of a value: a registered mod object (identity), a vanilla reference, a RegistryObject holder.
     * Returns null if unknown.
     */
    public String idOf(Object value) {
        if (value instanceof V.RegRef r) {
            return r.id();
        }
        if (value instanceof V.Holder h) {
            return h.id();
        }
        Facts.Registration r = facts.of(value);
        return r == null ? null : r.id();
    }

    /** Registry of a registered value, or null. */
    public String registryOf(Object value) {
        if (value instanceof V.RegRef r) {
            return r.registry();
        }
        if (value instanceof V.Holder h) {
            return h.registry();
        }
        Facts.Registration r = facts.of(value);
        return r == null ? null : r.registry();
    }

    /**
     * Item id for an item-like value (item object, block → its block item, vanilla refs). Null if unknown.
     */
    public String itemIdOf(Object value) {
        if (value instanceof V.AsItem ai) {
            return blockItemId(ai.block());
        }
        String reg = registryOf(value);
        if ("item".equals(reg)) {
            return idOf(value);
        }
        if ("block".equals(reg)) {
            return blockItemId(value);
        }
        if (value instanceof V.Holder h && h.registry().equals("item")) {
            return h.id();
        }
        return null;
    }

    /** Id of the item that places the given block. */
    public String blockItemId(Object block) {
        String blockId = idOf(block);
        if (blockId == null) {
            return null;
        }
        if (block instanceof V.RegRef r && r.id().startsWith("minecraft:")) {
            // vanilla: the block item shares the block id when the index knows such an item
            for (Map.Entry<String, String> e : vanilla.staticIds().entrySet()) {
                if (e.getKey().startsWith("net.minecraft.world.item.Items#") && e.getValue().equals(blockId)) {
                    return blockId;
                }
            }
            return null;
        }
        String found = blockItems().get(blockId);
        return found;
    }

    private Map<String, String> blockItemsCache;

    /** block id → id of the registered item whose value places it (BlockItem(block) or auto-registered items). */
    public Map<String, String> blockItems() {
        if (blockItemsCache == null) {
            throw new IllegalStateException("block items are computed by the item extractor");
        }
        return blockItemsCache;
    }

    public void setBlockItems(Map<String, String> m) {
        blockItemsCache = m;
    }

    public boolean blockItemsReady() {
        return blockItemsCache != null;
    }
}
