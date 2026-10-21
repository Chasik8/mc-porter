// porter:adapter (generic) - 1.19.2 position conversions without a 1.7.10 member: new BlockPos(Position) (floor of
// the coordinates, like Mth.floor in the 1.19.2 constructor).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.util.Vec3;

public final class Vecs {
    private Vecs() {}

    public static BlockPos blockPos(Vec3 v) {
        return new BlockPos((int) Math.floor(v.xCoord), (int) Math.floor(v.yCoord), (int) Math.floor(v.zCoord));
    }
}
