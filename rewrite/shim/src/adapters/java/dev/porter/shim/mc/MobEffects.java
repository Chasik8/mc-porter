// porter:adapter (generic) - vanilla MobEffects constants as 1.7.10 Potion instances (closure sources reference them by SRG name, which the rewrite cannot match).
package dev.porter.shim.mc;

import net.minecraft.potion.Potion;

public final class MobEffects {
    private MobEffects() {}

    public static final Potion MOVEMENT_SPEED = Potion.moveSpeed;
    public static final Potion MOVEMENT_SLOWDOWN = Potion.moveSlowdown;
    public static final Potion DIG_SPEED = Potion.digSpeed;
    public static final Potion DIG_SLOWDOWN = Potion.digSlowdown;
    public static final Potion DAMAGE_BOOST = Potion.damageBoost;
    public static final Potion HEAL = Potion.heal;
    public static final Potion HARM = Potion.harm;
    public static final Potion JUMP = Potion.jump;
    public static final Potion CONFUSION = Potion.confusion;
    public static final Potion REGENERATION = Potion.regeneration;
    public static final Potion DAMAGE_RESISTANCE = Potion.resistance;
    public static final Potion FIRE_RESISTANCE = Potion.fireResistance;
    public static final Potion WATER_BREATHING = Potion.waterBreathing;
    public static final Potion INVISIBILITY = Potion.invisibility;
    public static final Potion BLINDNESS = Potion.blindness;
    public static final Potion NIGHT_VISION = Potion.nightVision;
    public static final Potion HUNGER = Potion.hunger;
    public static final Potion WEAKNESS = Potion.weakness;
    public static final Potion POISON = Potion.poison;
    public static final Potion WITHER = Potion.wither;
    public static final Potion HEALTH_BOOST = Potion.field_76434_w;
    public static final Potion ABSORPTION = Potion.field_76444_x;
    public static final Potion SATURATION = Potion.field_76443_y;
}
