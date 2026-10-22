// porter:adapter (generic) - 1.19.2 FoodProperties (+ Builder): the food values of an item. 1.7.10 keeps them on
// ItemFood; ported code that only declares or reads them gets this value class.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.potion.PotionEffect;

public final class FoodProperties {
    private final int nutrition;
    private final float saturationModifier;
    private final boolean meat;
    private final boolean canAlwaysEat;
    private final boolean fastFood;
    private final List<Effect> effects;

    /** An effect of eating: supplied lazily (1.19.2 registers effects late), applied with a probability. */
    public static final class Effect {
        public final Supplier<PotionEffect> effect;
        public final float probability;

        Effect(Supplier<PotionEffect> effect, float probability) {
            this.effect = effect;
            this.probability = probability;
        }
    }

    FoodProperties(int nutrition, float saturationModifier, boolean meat, boolean canAlwaysEat, boolean fastFood, List<Effect> effects) {
        this.nutrition = nutrition;
        this.saturationModifier = saturationModifier;
        this.meat = meat;
        this.canAlwaysEat = canAlwaysEat;
        this.fastFood = fastFood;
        this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
    }

    public int getNutrition() { return nutrition; }

    public float getSaturationModifier() { return saturationModifier; }

    public boolean isMeat() { return meat; }

    public boolean canAlwaysEat() { return canAlwaysEat; }

    public boolean isFastFood() { return fastFood; }

    public List<Effect> getEffects() { return effects; }

    public static final class Builder {
        private int nutrition;
        private float saturationModifier;
        private boolean meat;
        private boolean canAlwaysEat;
        private boolean fastFood;
        private final List<Effect> effects = new ArrayList<>();

        public Builder nutrition(int n) { this.nutrition = n; return this; }

        public Builder saturationMod(float s) { this.saturationModifier = s; return this; }

        public Builder meat() { this.meat = true; return this; }

        public Builder alwaysEat() { this.canAlwaysEat = true; return this; }

        public Builder fast() { this.fastFood = true; return this; }

        public Builder effect(Supplier<PotionEffect> e, float probability) { effects.add(new Effect(e, probability)); return this; }

        public FoodProperties build() { return new FoodProperties(nutrition, saturationModifier, meat, canAlwaysEat, fastFood, effects); }
    }
}
