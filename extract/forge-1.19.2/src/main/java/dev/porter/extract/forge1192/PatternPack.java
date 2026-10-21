package dev.porter.extract.forge1192;

import dev.porter.extract.forge1192.concepts.Drafts;
import dev.porter.extract.forge1192.interp.Builtins;
import dev.porter.extract.forge1192.interp.Facts;
import java.util.List;

/**
 * Mod-specific recognisers on top of the standard Forge 1.19.2 patterns. A pack contains patterns only (names of
 * helper methods, how a mod's own GUI/structure framework maps to IR), never code or data of the mod.
 */
public interface PatternPack {
    String id();

    /** Whether the pack applies to this mod (e.g. by mod id or by the presence of marker classes). */
    boolean appliesTo(String modId, dev.porter.extract.forge1192.model.SourceModel model);

    /** Extra builtins (intercepts, recorders) installed before registration code runs. */
    default List<Builtins> builtins(Facts facts, String modId) {
        return List.of();
    }

    /** Called once the context exists, before any concept extractor (lang prefixes etc.). */
    default void configure(ExtractContext ctx) {
    }

    default void enrichBlock(ExtractContext ctx, Object value, Drafts.Block draft) {
    }

    default void enrichItem(ExtractContext ctx, Object value, Drafts.Item draft) {
    }

    /** Pack extraction for a concept, run after the generic extractor of that concept. */
    default void extract(ExtractContext ctx, String concept) {
    }
}
