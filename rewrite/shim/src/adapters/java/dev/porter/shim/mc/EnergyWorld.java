// porter:adapter (the mod-specific, PRIVATE) - world/biome queries of the solar panels and the thermal generator
// (Biome.Precipitation, DimensionType have no 1.7.10 shape; Level.getBiome is batch 06's MoreWorld).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

public final class EnergyWorld {
    private EnergyWorld() {}

    /** biome.getPrecipitation() == Biome.Precipitation.NONE (neither rain nor snow falls in the biome). */
    public static boolean noPrecipitation(BiomeGenBase biome) {
        return !biome.getEnableSnow() && !biome.canSpawnLightningBolt();
    }

    /** level.dimensionType().hasSkyLight() */
    public static boolean hasSkyLight(World world) {
        return !world.provider.hasNoSky;
    }

    /** level.dimensionType().coordinateScale() (8 in the Nether) */
    public static double coordinateScale(World world) {
        return world.provider.getMovementFactor();
    }

    /** level.canSeeSkyFromBelowWater(pos): open sky above the position, water does not count. */
    public static boolean canSeeSkyFromBelowWater(World world, BlockPos pos) {
        return world.canBlockSeeTheSky(pos.getX(), pos.getY(), pos.getZ());
    }
}
