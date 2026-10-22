package dev.porter.extract.forge1192.packs;

import dev.porter.extract.forge1192.PatternPack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Pattern packs on the classpath ({@code META-INF/services/dev.porter.extract.forge1192.PatternPack}), sorted by id.
 * Mod-specific packs can live in a separate (private) jar: put it on porter's classpath or into
 * {@code PORTER_PLUGINS} and it is picked up; a plugin pack with the id of a built-in one replaces it.
 */
public final class Packs {
    private Packs() {
    }

    public static List<PatternPack> all() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        java.util.Map<String, PatternPack> byId = new java.util.LinkedHashMap<>();
        // parent-first: built-in packs come first, a plugin with the same id replaces them
        ServiceLoader.load(PatternPack.class, cl != null ? cl : Packs.class.getClassLoader()).forEach(p -> byId.put(p.id(), p));
        List<PatternPack> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(PatternPack::id));
        return out;
    }
}
