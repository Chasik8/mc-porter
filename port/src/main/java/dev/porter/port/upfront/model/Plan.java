package dev.porter.port.upfront.model;

/**
 * What a 1.19.2 symbol becomes on the 1.7.10 side (contracts/rfc/011, match table).
 *
 * <ul>
 *   <li>{@code MAP}: rename or move to a 1.7.10 member.</li>
 *   <li>{@code EXPAND}: a MAP whose signature differs only by concept arguments (BlockPos, Direction, ...).</li>
 *   <li>{@code MIRROR}: a member of a type that lives in a shim (mirror) class.</li>
 *   <li>{@code REGISTRY}: a registry constant (Items.X, Blocks.X, ...), filled from the flattening table.</li>
 *   <li>{@code HINT}: the lineage reaches 1.7.10 but the signature changed unexplained; the target is known,
 *       a body is needed.</li>
 *   <li>{@code TODO}: no target found; a body or a plan is needed.</li>
 * </ul>
 */
public enum Plan {
    MAP,
    EXPAND,
    MIRROR,
    REGISTRY,
    HINT,
    TODO
}
