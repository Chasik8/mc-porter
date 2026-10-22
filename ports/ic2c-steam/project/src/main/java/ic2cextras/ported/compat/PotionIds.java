package ic2cextras.ported.compat;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.potion.Potion;

import ic2cextras.ported.gen.Ic2cextrasContent;

/**
 * porter pilot (agent): 1.19.2 MobEffect registry ids <-> 1.7.10 Potion. Replaces
 * ForgeRegistries.MOB_EFFECTS / ForgeRegistryWrapper.getMobEffectKey (1.7.10 potions have no registry names).
 * Vanilla ids from porter generate's table (ItemGen.VANILLA_POTIONS, Potion.java ids 1..23).
 */
public final class PotionIds {

    private static final String[] VANILLA = { null, "minecraft:speed", "minecraft:slowness", "minecraft:haste",
        "minecraft:mining_fatigue", "minecraft:strength", "minecraft:instant_health", "minecraft:instant_damage",
        "minecraft:jump_boost", "minecraft:nausea", "minecraft:regeneration", "minecraft:resistance",
        "minecraft:fire_resistance", "minecraft:water_breathing", "minecraft:invisibility", "minecraft:blindness",
        "minecraft:night_vision", "minecraft:hunger", "minecraft:weakness", "minecraft:poison", "minecraft:wither",
        "minecraft:health_boost", "minecraft:absorption", "minecraft:saturation" };
    private static final Map<String, Integer> BY_KEY = new HashMap<>();

    static {
        for (int i = 1; i < VANILLA.length; i++) BY_KEY.put(VANILLA[i], i);
    }

    private PotionIds() {}

    /** Potion for a 1.19.2 effect id ("minecraft:speed", "ic2:shaky"), or null if it has no 1.7.10 counterpart. */
    public static Potion byKey(String key) {
        if (key == null || key.isEmpty()) return null;
        if (!key.contains(":")) key = "minecraft:" + key;
        if ("ic2:shaky".equals(key)) return Ic2cextrasContent.potionShaky;
        Integer id = BY_KEY.get(key);
        return id == null ? null : Potion.potionTypes[id];
    }

    /**
     * 1.7.10 drinkable potion damage whose vanilla name/colour shows this effect (8192 | brewing liquid id), or 0
     * (water bottle look) when vanilla 1.7.10 has no potion of it. The effect itself comes from CustomPotionEffects.
     */
    public static int potionDamage(Potion potion) {
        if (potion == null) return 0;
        int liquid;
        if (potion == Potion.regeneration) liquid = 1;
        else if (potion == Potion.moveSpeed) liquid = 2;
        else if (potion == Potion.fireResistance) liquid = 3;
        else if (potion == Potion.poison) liquid = 4;
        else if (potion == Potion.heal) liquid = 5;
        else if (potion == Potion.nightVision) liquid = 6;
        else if (potion == Potion.weakness) liquid = 8;
        else if (potion == Potion.damageBoost) liquid = 9;
        else if (potion == Potion.moveSlowdown) liquid = 10;
        else if (potion == Potion.harm) liquid = 12;
        else if (potion == Potion.waterBreathing) liquid = 13;
        else if (potion == Potion.invisibility) liquid = 14;
        else return 0;
        return 8192 | liquid;
    }

    /** 1.19.2 effect id of a Potion (vanilla ids for 1..23, ic2:shaky, else "porter:potion_<id>"). */
    public static String key(Potion potion) {
        if (potion == null) return null;
        if (potion == Ic2cextrasContent.potionShaky) return "ic2:shaky";
        if (potion.id > 0 && potion.id < VANILLA.length) return VANILLA[potion.id];
        return "porter:potion_" + potion.id;
    }
}
