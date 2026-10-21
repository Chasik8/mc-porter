// porter:adapter (generic) - 1.19.2 MobCategory (EntityType.getCategory() derives it from the 1.7.10 class).
package dev.porter.shim.mc;

public enum MobCategory {
    MONSTER("monster", false), CREATURE("creature", true), AMBIENT("ambient", true), AXOLOTLS("axolotls", true),
    UNDERGROUND_WATER_CREATURE("underground_water_creature", true), WATER_CREATURE("water_creature", true),
    WATER_AMBIENT("water_ambient", true), MISC("misc", true);

    private final String name;
    private final boolean friendly;

    MobCategory(String name, boolean friendly) {
        this.name = name;
        this.friendly = friendly;
    }

    public String getName() { return name; }

    public boolean isFriendly() { return friendly; }
}
