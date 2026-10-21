// porter:adapter (generic) - 1.19.2 CollisionContext (no entity information on 1.7.10 bound queries).
package dev.porter.shim.mc;

public interface CollisionContext {
    CollisionContext EMPTY = new CollisionContext() {};

    static CollisionContext empty() { return EMPTY; }

    static CollisionContext of(net.minecraft.entity.Entity e) { return EMPTY; }

    default boolean isDescending() { return false; }
}
