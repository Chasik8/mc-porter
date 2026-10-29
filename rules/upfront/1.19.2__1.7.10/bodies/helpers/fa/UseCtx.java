// porter:helper (generic) - generated UseOnContext shell filled from a 1.7.10 onItemUse call.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import dev.porter.shim.gen.world.item.context.UseOnContext;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public final class UseCtx {
    private UseCtx() {}

    public static UseOnContext of(World world, BlockPos pos, Direction face, EntityPlayer player, ItemStack stack, Vec3 hit) {
        UseOnContext c = new UseOnContext();
        GenAttach.put(c, "level", world);
        GenAttach.put(c, "pos", pos);
        GenAttach.put(c, "face", face);
        GenAttach.put(c, "player", player);
        GenAttach.put(c, "stack", stack);
        GenAttach.put(c, "hit", hit);
        return c;
    }

    /** horizontal facing of a player (1.7.10 yaw quadrant: 0 south, 1 west, 2 north, 3 east). */
    public static Direction horizontal(EntityPlayer p) {
        if (p == null) {
            return Direction.NORTH;
        }
        return Direction.from2DDataValue(MathHelper.floor_double(p.rotationYaw * 4.0F / 360.0F + 0.5D) & 3);
    }
}
