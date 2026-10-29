// porter:adapter (the mod-specific, PRIVATE) - net.minecraft.world.entity.npc.VillagerProfession on 1.7.10: the numeric
// EntityVillager profession id (0 farmer, 1 librarian, 2 priest, 3 smith, 4 butcher, others from VillagerRegistry).
package dev.porter.shim.mc;

import net.minecraft.entity.passive.EntityVillager;

public final class VillagerProfession {
    private static final String[] VANILLA = {"farmer", "librarian", "priest", "smith", "butcher"};

    private final int id;

    private VillagerProfession(int id) {
        this.id = id;
    }

    public static VillagerProfession of(int id) {
        return new VillagerProfession(id);
    }

    /** Villager#getVillagerData().getProfession() */
    public static VillagerProfession of(EntityVillager villager) {
        return new VillagerProfession(villager.getProfession());
    }

    public int getId() {
        return id;
    }

    /** the lang suffix of entity.Villager.&lt;name&gt; */
    public String getName() {
        return id >= 0 && id < VANILLA.length ? VANILLA[id] : "profession_" + id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VillagerProfession && ((VillagerProfession) o).id == id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return getName();
    }
}
