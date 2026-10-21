package dev.porter.assets;

import java.util.Arrays;
import java.util.Optional;

/**
 * Nodes of the asset conversion chain, newest first. One node per resource-pack {@code pack_format}; pack_format 1
 * is split into 1.8.9 and 1.7.10 because JSON block/item models and blockstates appeared inside it (1.8).
 */
public enum AssetVersion {
    V1_19_2("1.19.2", 9),
    V1_18_2("1.18.2", 8),
    V1_17_1("1.17.1", 7),
    V1_16_5("1.16.5", 6),
    V1_15_2("1.15.2", 5),
    V1_14_4("1.14.4", 4),
    V1_12_2("1.12.2", 3),
    V1_10_2("1.10.2", 2),
    V1_8_9("1.8.9", 1),
    V1_7_10("1.7.10", 1);

    public final String mc;
    public final int packFormat;

    AssetVersion(String mc, int packFormat) {
        this.mc = mc;
        this.packFormat = packFormat;
    }

    public static Optional<AssetVersion> of(String mc) {
        String v = mc.startsWith("forge-") ? mc.substring("forge-".length()) : mc;
        return Arrays.stream(values()).filter(x -> x.mc.equals(v)).findFirst();
    }

    /** The next older node, or null for the oldest. */
    public AssetVersion older() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    @Override
    public String toString() {
        return mc;
    }
}
