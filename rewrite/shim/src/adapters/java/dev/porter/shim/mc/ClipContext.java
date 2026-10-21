// porter:adapter (generic) - 1.19.2 ClipContext + Level.clip(ctx) on 1.7.10 World.rayTraceBlocks.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public class ClipContext {
    public enum Block { COLLIDER, OUTLINE, VISUAL, FALLDAMAGE_RESETTING }

    public enum Fluid { NONE, SOURCE_ONLY, ANY, WATER }

    private final Vec3 from, to;
    private final Block block;
    private final Fluid fluid;

    public ClipContext(Vec3 from, Vec3 to, Block block, Fluid fluid, Entity entity) {
        this.from = from;
        this.to = to;
        this.block = block;
        this.fluid = fluid;
    }

    public Vec3 getFrom() { return from; }

    public Vec3 getTo() { return to; }

    /** Level.clip(ctx) */
    public static BlockHitResult clip(World w, ClipContext c) {
        Vec3 a = Vec3.createVectorHelper(c.from.xCoord, c.from.yCoord, c.from.zCoord);
        Vec3 b = Vec3.createVectorHelper(c.to.xCoord, c.to.yCoord, c.to.zCoord);
        MovingObjectPosition m = w.func_147447_a(a, b, c.fluid != Fluid.NONE, c.block == Block.COLLIDER, false);
        if (m == null || m.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return BlockHitResult.miss(c.to, Direction.UP, new BlockPos(c.to.xCoord, c.to.yCoord, c.to.zCoord));
        }
        return new BlockHitResult(m.hitVec, Direction.from3DDataValue(m.sideHit), new BlockPos(m.blockX, m.blockY, m.blockZ), false);
    }
}
