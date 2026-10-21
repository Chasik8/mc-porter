package dev.porter.extract.forge1192.packs;

import dev.porter.extract.forge1192.PatternPack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Pattern packs on the classpath ({@code META-INF/services/dev.porter.extract.forge1192.PatternPack}), sorted by id.
 * Mod-specific packs can live in a separate (private) jar: put it on porter's classpath and it is picked up.
 */
public final class Packs {
    private Packs() {
    }

    public static List<PatternPack> all() {
        List<PatternPack> out = new ArrayList<>();
        ServiceLoader.load(PatternPack.class, Packs.class.getClassLoader()).forEach(out::add);
        out.sort(Comparator.comparing(PatternPack::id));
        return out;
    }
}
