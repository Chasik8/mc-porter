// porter:adapter (the mod-specific, PRIVATE) - LevelEntityGetter (via LevelMixin#getEntityAccessor) on 1.7.10:
// entity lookup by id / uuid in a World.
package dev.porter.shim.mc;

import java.util.List;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

public final class EntityGetter<T> {
    private final World world;

    private EntityGetter(World world) {
        this.world = world;
    }

    /** LevelMixin#getEntityAccessor() */
    public static <T> EntityGetter<T> of(World world) {
        return new EntityGetter<T>(world);
    }

    @SuppressWarnings("unchecked")
    public T get(int id) {
        return (T) world.getEntityByID(id);
    }

    @SuppressWarnings("unchecked")
    public T get(UUID id) {
        if (id == null) return null;
        List<?> all = world.loadedEntityList;
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            if (id.equals(e.getUniqueID())) return (T) e;
        }
        return null;
    }
}
