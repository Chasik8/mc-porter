// porter:adapter (generic) - 1.19.2 ResourceKey (registry + location). Dimension keys carry the 1.7.10 dimension id
// ("minecraft:overworld" = 0, "minecraft:the_nether" = -1, "minecraft:the_end" = 1, else "porter:dim_<id>").
package dev.porter.shim.mc;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

public final class ResourceKey<T> {
    private final ResourceLocation registry;
    private final ResourceLocation location;

    private ResourceKey(ResourceLocation registry, ResourceLocation location) {
        this.registry = registry;
        this.location = location;
    }

    public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, ResourceLocation location) {
        return new ResourceKey<>(registry.location, location);
    }

    public static <T> ResourceKey<Registry<T>> createRegistryKey(ResourceLocation location) {
        return new ResourceKey<>(new ResourceLocation("minecraft", "root"), location);
    }

    public ResourceLocation location() { return location; }

    public ResourceLocation registry() { return registry; }

    /** 1.19.2 Level.dimension(). */
    public static ResourceKey<World> dimension(World w) { return dimension(w.provider.dimensionId); }

    public static ResourceKey<World> dimension(int id) {
        String n = id == 0 ? "overworld" : id == -1 ? "the_nether" : id == 1 ? "the_end" : null;
        return new ResourceKey<>(Registry.DIMENSION_REGISTRY.location, n != null ? new ResourceLocation("minecraft", n) : new ResourceLocation("porter", "dim_" + id));
    }

    /** the 1.7.10 dimension id of a dimension key. */
    public int dimensionId() {
        String p = location.getResourcePath();
        if (p.equals("overworld")) return 0;
        if (p.equals("the_nether")) return -1;
        if (p.equals("the_end")) return 1;
        return p.startsWith("dim_") ? Integer.parseInt(p.substring(4)) : 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceKey && ((ResourceKey<?>) o).registry.equals(registry) && ((ResourceKey<?>) o).location.equals(location);
    }

    @Override
    public int hashCode() { return Objects.hash(registry.toString(), location.toString()); }

    @Override
    public String toString() { return "ResourceKey[" + registry + " / " + location + "]"; }
}
