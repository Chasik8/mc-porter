// porter:adapter (the mod teleporters) - 1.19.2 Forge PortalInfo (target position, speed, rotation) for 1.7.10.
package dev.porter.shim.mc;

import net.minecraft.util.Vec3;

public final class PortalInfo {
    public final Vec3 pos;
    public final Vec3 speed;
    public final float yRot;
    public final float xRot;

    public PortalInfo(Vec3 pos, Vec3 speed, float yRot, float xRot) {
        this.pos = pos;
        this.speed = speed;
        this.yRot = yRot;
        this.xRot = xRot;
    }
}
