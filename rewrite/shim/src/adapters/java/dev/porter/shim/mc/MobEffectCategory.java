// porter:adapter (generic) - 1.19.2 MobEffectCategory. 1.7.10 potions only know "bad" (isBadEffect).
package dev.porter.shim.mc;

import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;

public enum MobEffectCategory {
    BENEFICIAL(EnumChatFormatting.BLUE),
    HARMFUL(EnumChatFormatting.RED),
    NEUTRAL(EnumChatFormatting.BLUE);

    private final EnumChatFormatting tooltipFormatting;

    MobEffectCategory(EnumChatFormatting f) { this.tooltipFormatting = f; }

    public EnumChatFormatting getTooltipFormatting() { return tooltipFormatting; }

    /** MobEffect.getCategory() of a 1.7.10 potion. */
    public static MobEffectCategory of(Potion p) { return p != null && p.isBadEffect() ? HARMFUL : BENEFICIAL; }
}
