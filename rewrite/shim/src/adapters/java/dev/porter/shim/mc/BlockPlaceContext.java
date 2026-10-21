// porter:adapter (generic) - 1.19.2 BlockPlaceContext (UseOnContext) for StateBlock.getStateForPlacement.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

public class BlockPlaceContext {
    private final World level;
    private final BlockPos pos;
    private final EntityLivingBase placer;
    private final ItemStack stack;

    public BlockPlaceContext(World level, BlockPos pos, EntityLivingBase placer, ItemStack stack) {
        this.level = level;
        this.pos = pos;
        this.placer = placer;
        this.stack = stack;
    }

    public World getLevel() { return level; }

    public BlockPos getClickedPos() { return pos; }

    public EntityPlayer getPlayer() { return placer instanceof EntityPlayer ? (EntityPlayer) placer : null; }

    public ItemStack getItemInHand() { return stack; }

    public InteractionHand getHand() { return InteractionHand.MAIN_HAND; }

    public boolean isSecondaryUseActive() { return placer != null && placer.isSneaking(); }

    /** horizontal direction the placer looks at (1.7.10 yaw quadrant: 0 = south, 1 = west, 2 = north, 3 = east). */
    public Direction getHorizontalDirection() {
        if (placer == null) return Direction.NORTH;
        int q = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        return Direction.from2DDataValue(q);
    }

    /** the direction the placer looks at most (1.19.2 getNearestLookingDirection). */
    public Direction getNearestLookingDirection() {
        if (placer == null) return Direction.NORTH;
        if (placer.rotationPitch < -45F) return Direction.UP;
        if (placer.rotationPitch > 45F) return Direction.DOWN;
        return getHorizontalDirection();
    }

    public Direction[] getNearestLookingDirections() {
        Direction f = getNearestLookingDirection();
        Direction[] out = new Direction[6];
        out[0] = f;
        int i = 1;
        for (Direction d : Direction.values()) if (d != f && d != f.getOpposite()) out[i++] = d;
        out[5] = f.getOpposite();
        return out;
    }

    public float getRotation() { return placer == null ? 0F : placer.rotationYaw; }

    public boolean canPlace() { return true; }

    public boolean replacingClickedOnBlock() { return false; }

    public Direction getClickedFace() { return Direction.UP; }
}
