package dev.porter.assets.steps;

import dev.porter.assets.AssetTree;
import dev.porter.assets.AssetVersion;
import dev.porter.assets.Context;
import dev.porter.assets.Step;

/**
 * A chain hop with no change to the asset kinds porter converts (lang, sounds.json, textures, .mcmeta, block models).
 * Kept as an explicit node so a future change at that boundary has an obvious home.
 */
public record IdentityStep(AssetVersion from, AssetVersion to, String description) implements Step {
    @Override
    public void apply(AssetTree tree, Context ctx) {
        // intentionally empty
    }
}
