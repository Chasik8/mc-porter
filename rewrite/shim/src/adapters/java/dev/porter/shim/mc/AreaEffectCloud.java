// porter:adapter (generic) - 1.19.2 AreaEffectCloud (lingering potion cloud) on 1.7.10: a server-side entity that,
// after its wait time, applies its effects to living entities in its radius every 5 ticks until its duration ends.
// It is not registered (never saved, never sent to clients): the cloud is not drawn.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

public class AreaEffectCloud extends Entity {
    private final List<PotionEffect> effects = new ArrayList<>();
    private float radius = 3.0F;
    private float radiusOnUse;
    private float radiusPerTick;
    private int waitTime = 10;
    private int duration = 600;
    private Entity owner;

    public AreaEffectCloud(World world, double x, double y, double z) {
        super(world);
        setSize(radius * 2.0F, 0.5F);
        setPosition(x, y, z);
    }

    @Override
    protected void entityInit() {}

    public void setRadius(float r) { this.radius = r; }

    public float getRadius() { return radius; }

    public void setRadiusOnUse(float r) { this.radiusOnUse = r; }

    public void setRadiusPerTick(float r) { this.radiusPerTick = r; }

    public void setWaitTime(int t) { this.waitTime = t; }

    public void setDuration(int t) { this.duration = t; }

    public int getDuration() { return duration; }

    public void setOwner(Entity e) { this.owner = e; }

    public void addEffect(PotionEffect e) { if (e != null) effects.add(e); }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (worldObj.isRemote) return;
        if (ticksExisted >= waitTime + duration || radius <= 0) {
            setDead();
            return;
        }
        if (ticksExisted < waitTime) return;
        radius += radiusPerTick;
        if (ticksExisted % 5 != 0) return;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(posX - radius, posY - 0.5, posZ - radius, posX + radius, posY + 1.5, posZ + radius);
        for (Object o : worldObj.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            EntityLivingBase e = (EntityLivingBase) o;
            double dx = e.posX - posX;
            double dz = e.posZ - posZ;
            if (dx * dx + dz * dz > radius * radius) continue;
            for (PotionEffect p : effects) {
                Potion potion = Potion.potionTypes[p.getPotionID()];
                if (potion == null) continue;
                if (potion.isInstant()) {
                    potion.affectEntity(owner instanceof EntityLivingBase ? (EntityLivingBase) owner : null, e, p.getAmplifier(), 0.5);
                } else {
                    e.addPotionEffect(new PotionEffect(p.getPotionID(), p.getDuration() / 4, p.getAmplifier()));
                }
            }
            radius += radiusOnUse;
        }
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound t) {}

    @Override
    protected void writeEntityToNBT(NBTTagCompound t) {}
}
