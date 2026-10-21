package dev.porter.assets;

import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Options and collected results of one {@code porter assets} run. Steps read options from here and record
 * warnings, metrics and side outputs (meta files) instead of printing.
 */
public final class Context {
    /** How 1.7.10 unlocalized names are derived from a registry id when IR gives none. */
    public enum LegacyNames {
        /** {@code examplemod:steam_turbine} → {@code steam_turbine} (default). */
        PATH,
        /** → {@code examplemod.steam_turbine}. */
        NS_PATH,
        /** → {@code examplemod.steamTurbine}. */
        NS_CAMEL;

        public String apply(String namespace, String path) {
            String p = path.replace('/', '.');
            return switch (this) {
                case PATH -> p;
                case NS_PATH -> namespace + "." + p;
                case NS_CAMEL -> namespace + "." + camel(p);
            };
        }

        static String camel(String s) {
            StringBuilder b = new StringBuilder();
            boolean up = false;
            for (char c : s.toCharArray()) {
                if (c == '_') {
                    up = true;
                } else {
                    b.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            return b.toString();
        }
    }

    /** A warning (also used for losses: {@link #loss}). */
    public record Warning(String code, String subject, String message) {
    }

    // ---- options ----
    public final List<IrDocument> ir = new ArrayList<>();
    public final Map<String, String> keyMapOverrides = new LinkedHashMap<>();
    public LegacyNames legacyNames = LegacyNames.PATH;
    public boolean keepSourceKeys;
    /** Merge non-standard split lang files ({@code <prefix>_<locale>.json}) into the locale file. */
    public boolean mergeSplitLang = true;

    /** Extra sheet packs: name → {@code ns:glob} (glob relative to {@code <ns>/textures/} of the converted tree). */
    public final Map<String, String> packSpecs = new LinkedHashMap<>();
    /** Pack every non-block/item texture package into a sheet (1.7.10 step). */
    public boolean autoPack = true;
    public int sheetSize = 256;
    public boolean trimSprites;
    /** Drop models/, blockstates/ and texture packages from the 1.7.10 output (their content goes to meta/). */
    public boolean dropModelFiles = true;

    // ---- results ----
    public final List<Warning> warnings = new ArrayList<>();
    public final List<Warning> losses = new ArrayList<>();
    public final Map<String, Object> metrics = new LinkedHashMap<>();
    /** Files for {@code <out>/meta/} (key map, IR, manual-render list, sheet maps, OBJ exports). */
    public final Map<String, byte[]> meta = new TreeMap<>();
    /** Lang key decisions made at the flattening boundary (source key → mapping), sorted. */
    public final Map<String, dev.porter.assets.lang.KeyTable.Mapping> langKeyMap = new TreeMap<>();
    /** Block ids known from IR or from source lang keys {@code block.<ns>.<path>} (used to match texture packages). */
    public final java.util.SortedMap<String, String> knownBlocks = new TreeMap<>();
    /** Source version of the run (set by {@link Chain}). */
    public AssetVersion source;
    /** Steps applied, in order. */
    public final List<String> applied = new ArrayList<>();

    public void warn(String code, String subject, String message) {
        warnings.add(new Warning(code, subject, message));
    }

    /** Something could not be carried over; any loss makes the run fail unless it is a declared field drop. */
    public void loss(String code, String subject, String message) {
        losses.add(new Warning(code, subject, message));
    }

    public void metric(String key, Object value) {
        metrics.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public void count(String key, int delta) {
        metrics.merge(key, delta, (a, b) -> (Integer) a + (Integer) b);
    }

    public void putMeta(String name, String text) {
        meta.put(name, text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** All IR nodes of a type across the given IR documents. */
    public <T extends IrNode> List<T> irNodes(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (IrDocument d : ir) {
            d.nodes(type).forEach(out::add);
        }
        return out;
    }
}
