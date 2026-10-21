// porter:adapter (generic) - 1.19.2 BlockHitResult.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import net.minecraft.util.Vec3;

public class BlockHitResult {
    private final Vec3 location;
    private final Direction direction;
    private final BlockPos pos;
    private final boolean inside;
    private Type type = Type.BLOCK; // HitResult.Type

    /** 1.19.2 HitResult.Type */
    public enum Type { MISS, BLOCK, ENTITY }

    public Type getType() { return type; }

    public static BlockHitResult miss(Vec3 location, Direction direction, BlockPos pos) {
        BlockHitResult r = new BlockHitResult(location, direction, pos, false);
        r.type = Type.MISS;
        return r;
    }

    public BlockHitResult(Vec3 location, Direction direction, BlockPos pos, boolean inside) {
        this.location = location;
        this.direction = direction;
        this.pos = pos;
        this.inside = inside;
    }

    public Vec3 getLocation() { return location; }

    public Direction getDirection() { return direction; }

    public BlockPos getBlockPos() { return pos; }

    public boolean isInside() { return inside; }

    public BlockHitResult withDirection(Direction d) { return new BlockHitResult(location, d, pos, inside); }

    public BlockHitResult withPosition(BlockPos p) { return new BlockHitResult(location, direction, p, inside); }
}
