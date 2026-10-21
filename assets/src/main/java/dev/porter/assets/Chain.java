package dev.porter.assets;

import dev.porter.assets.lang.SplitLangMerge;
import dev.porter.assets.steps.FlatteningStep;
import dev.porter.assets.steps.IdentityStep;
import dev.porter.assets.steps.LangCaseStep;
import dev.porter.assets.steps.LegacyRenderStep;
import dev.porter.assets.steps.SoundsCategoryStep;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The conversion chain: one {@link Step} per pair of neighbouring {@link AssetVersion} nodes, composed for any
 * {@code from → to} with {@code to} older than {@code from}. Upward conversion is not implemented.
 */
public final class Chain {
    private static final Map<AssetVersion, Step> STEPS = new EnumMap<>(AssetVersion.class);

    static {
        identity(AssetVersion.V1_19_2, "no change for lang/sounds/textures/models");
        identity(AssetVersion.V1_18_2, "no change for lang/sounds/textures/models");
        identity(AssetVersion.V1_17_1, "no change for lang/sounds/textures/models");
        identity(AssetVersion.V1_16_5, "no change for lang/sounds/textures/models");
        identity(AssetVersion.V1_15_2, "no change for lang/sounds/textures/models");
        register(new FlatteningStep());
        register(new LangCaseStep());
        register(new SoundsCategoryStep());
        register(new LegacyRenderStep());
        for (AssetVersion v : AssetVersion.values()) {
            if (v.older() != null && !STEPS.containsKey(v)) {
                throw new IllegalStateException("chain gap at " + v);
            }
        }
    }

    private static void identity(AssetVersion from, String why) {
        register(new IdentityStep(from, from.older(), why));
    }

    private static void register(Step s) {
        if (s.from().older() != s.to()) {
            throw new IllegalStateException("step " + s + " does not join neighbours");
        }
        STEPS.put(s.from(), s);
    }

    private Chain() {
    }

    /** Steps from {@code from} down to {@code to}, in order. */
    public static List<Step> steps(AssetVersion from, AssetVersion to) {
        if (to.ordinal() < from.ordinal()) {
            throw new IllegalArgumentException("upward conversion " + from + " -> " + to + " is not implemented");
        }
        List<Step> out = new ArrayList<>();
        for (AssetVersion v = from; v != to; v = v.older()) {
            out.add(STEPS.get(v));
        }
        return out;
    }

    /** Source normalisation (split lang files) for post-flattening sources. */
    public static void normalise(AssetTree tree, Context ctx, AssetVersion from) {
        ctx.source = from;
        if (ctx.mergeSplitLang && from.ordinal() <= AssetVersion.V1_14_4.ordinal()) {
            SplitLangMerge.apply(tree, ctx);
        }
    }

    /** Runs the steps on {@code tree} (already normalised). */
    public static void run(AssetTree tree, Context ctx, AssetVersion from, AssetVersion to) {
        ctx.source = from;
        for (Step s : steps(from, to)) {
            s.apply(tree, ctx);
            ctx.applied.add(s.from() + " -> " + s.to() + ": " + s.description());
        }
    }
}
