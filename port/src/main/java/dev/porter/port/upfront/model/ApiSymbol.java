package dev.porter.port.upfront.model;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One 1.19.2 platform symbol the mod references (api-inventory.json, spec section 2.2).
 * {@code origin} is kept sorted so the JSON is deterministic.
 */
public record ApiSymbol(SymbolKey key, int uses, Set<String> origin, int access, String declaredIn,
                        List<String> refClasses, String signature) {

    public ApiSymbol {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        origin = origin == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(origin));
        refClasses = refClasses == null ? List.of() : List.copyOf(refClasses);
    }
}
