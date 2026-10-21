// porter:adapter (generic) - 1.19.2 LivingSpawnEvent accessors that 1.7.10 does not have. 1.7.10 Forge posts
// CheckSpawn / SpecialSpawn only from natural spawning (SpawnerAnimals.findChunksForSpawning; mob spawners and
// chunk-generation spawns post nothing), so the spawn reason is NATURAL and isSpawner() is false.
package dev.porter.shim.mc;

import net.minecraftforge.event.entity.living.LivingSpawnEvent;

public final class SpawnEvents {
    private SpawnEvents() {}

    public static ForgeEvents.MobSpawnType reason(LivingSpawnEvent e) { return ForgeEvents.MobSpawnType.NATURAL; }

    public static boolean isSpawner(LivingSpawnEvent e) { return false; }
}
