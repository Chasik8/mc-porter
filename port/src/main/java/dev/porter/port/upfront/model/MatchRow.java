package dev.porter.port.upfront.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One row of the match table (spec section 2.6). The kind is {@code key.kind()}.
 *
 * <ul>
 *   <li>{@code resolvedBy}: {@code rule} (an existing rule matches), {@code graph} (GraphRuleGenerator emits it), {@code none}.</li>
 *   <li>{@code method}: {@code lineage}, {@code lineage-concept}, {@code lineage-sig}, {@code scored:<owner-source>},
 *       {@code same-fqn}, {@code simple-name}, {@code registry}, {@code mirror}, {@code none:<reason>}.</li>
 *   <li>{@code features} (sorted by name) and {@code alternatives} are JSON only; the TSV has no columns for them.</li>
 * </ul>
 */
public record MatchRow(SymbolKey key, int uses, String area, String resolvedBy, Plan plan, String method,
                       double confidence, Target target, String shim, String ruleId,
                       Map<String, Double> features, List<Alt> alternatives, String note) {

    public MatchRow {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("plan is null for " + key);
        }
        features = features == null ? Map.of() : Collections.unmodifiableSortedMap(new TreeMap<>(features));
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    public Kind kind() {
        return key.kind();
    }
}
