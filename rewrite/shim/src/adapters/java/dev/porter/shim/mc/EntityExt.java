// porter:adapter (generic) - 1.19.2 Entity/LivingEntity helpers on 1.7.10 entities.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public final class EntityExt {
    private EntityExt() {}

    /** 1.7.10 has no spectators: every entity passes. */
    public static final Predicate<Entity> NO_SPECTATORS = e -> true;

    /** Eye position: EntityLivingBase.getPosition(partial) already includes the eye offset on the server and client. */
    public static Vec3 eyePosition(EntityLivingBase e, float partial) {
        return e.getPosition(partial);
    }

    public static Entity rootVehicle(Entity e) {
        Entity r = e;
        while (r.ridingEntity != null) {
            r = r.ridingEntity;
        }
        return r;
    }

    public static void broadcastBreak(EntityLivingBase e, InteractionHand hand) {
        breakEffect(e, e.getHeldItem());
    }

    public static void broadcastBreak(EntityLivingBase e, EquipmentSlot slot) {
        breakEffect(e, EquipmentSlot.get(e, slot));
    }

    private static void breakEffect(EntityLivingBase e, ItemStack stack) {
        if (stack != null && e instanceof EntityPlayer) {
            ((EntityPlayer) e).renderBrokenItemStack(stack);
        }
    }

    /** 1.19.2 Level#getEntities(Entity, AABB, Predicate). */
    public static List<Entity> getEntities(World w, Entity except, AxisAlignedBB box, Predicate<Entity> filter) {
        List<Entity> out = new ArrayList<Entity>();
        for (Object o : w.getEntitiesWithinAABBExcludingEntity(except, box)) {
            Entity e = (Entity) o;
            if (filter == null || filter.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** 1.19.2 AABB#clip(Vec3, Vec3): the hit point of the segment on the box, if any. */
    public static Optional<Vec3> clip(AxisAlignedBB box, Vec3 from, Vec3 to) {
        MovingObjectPosition hit = box.calculateIntercept(from, to);
        return hit == null ? Optional.<Vec3>empty() : Optional.of(hit.hitVec);
    }

    /** 1.19.2 Entity#getPickRadius(): 0 unless overridden. */
    public static float pickRadius(Entity e) {
        return 0.0f;
    }

    /** Untyped receiver (raw lambdas): break effect for the held item of a living entity. */
    public static void broadcastBreak(Object e, Object handOrSlot) {
        if (e instanceof EntityLivingBase) {
            if (handOrSlot instanceof EquipmentSlot) {
                broadcastBreak((EntityLivingBase) e, (EquipmentSlot) handOrSlot);
            } else {
                broadcastBreak((EntityLivingBase) e, (InteractionHand) handOrSlot);
            }
        }
    }

    /** AABB.inflate(d): grown by d on every side (the argument evaluated once). */
    public static AxisAlignedBB inflate(AxisAlignedBB box, double d) { return box.expand(d, d, d); }
}
