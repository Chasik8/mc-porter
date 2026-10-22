// porter:adapter (generic) - 1.19.2 EntityHitResult (entity plus hit location) for 1.7.10.
package dev.porter.shim.mc;

import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

public class EntityHitResult {
    private final Entity entity;
    private final Vec3 location;

    public EntityHitResult(Entity entity, Vec3 location) {
        this.entity = entity;
        this.location = location;
    }

    public EntityHitResult(Entity entity) {
        this(entity, Vec3.createVectorHelper(entity.posX, entity.posY, entity.posZ));
    }

    public Entity getEntity() { return entity; }

    public Vec3 getLocation() { return location; }
}
