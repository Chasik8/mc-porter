// porter:adapter (IC2C-specific, client) - 1.7.10 icons of IC2C blocks from their 1.19.2 model sprites.
// IC2C bakes a block model from IBlockModel.getSpriteForState(state, side) with model-local sides (the default
// model faces south) and rotates it by IRotatableBlock.getRotation(state) (QuadBaker.getModelRotation: horizontal
// = y rotation by facing.get2DDataValue() * 90, up = x 90, down = x 270). The StateBlock adapter asks for the icon of
// a world side: the world side is turned back into the model side and the sprite looked up.
package ic2cextras.ported.compat;

import net.minecraft.block.Block;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.ported.core.platform.rendering.features.block.IBlockModel;
import ic2cextras.ported.core.platform.rendering.features.block.IRotatableBlock;
import ic2cextras.ported.shim.Direction;
import ic2cextras.ported.shim.mc.BlockState;

@SideOnly(Side.CLIENT)
public final class ModelIcons {

    /** horizontal directions by 2D data value (south, west, north, east). */
    private static final Direction[] H = { Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST };

    private ModelIcons() {}

    public static IIcon icon(Block block, BlockState state, Direction worldSide) {
        if (!(block instanceof IBlockModel) || state == null || worldSide == null) return null;
        Direction local = worldSide;
        if (block instanceof IRotatableBlock && ((IRotatableBlock) block).hasRotation(state)) {
            local = toModel(((IRotatableBlock) block).getRotation(state), worldSide);
        }
        try {
            return ((IBlockModel) block).getSpriteForState(state, local);
        } catch (RuntimeException e) {
            return null; // a model that needs more than the state (tile data, camouflage): 1.7.10 default icon
        }
    }

    /** Inverse of the model rotation for this facing: the model side that ends up on worldSide. */
    static Direction toModel(Direction facing, Direction worldSide) {
        if (facing == null) return worldSide;
        int fh = index(facing);
        if (fh >= 0) {
            int wh = index(worldSide);
            return wh < 0 ? worldSide : H[Math.floorMod(wh - fh, 4)];
        }
        // x rotation: UP = 90 (model south -> up, up -> north, north -> down, down -> south), DOWN = 270 (inverse)
        boolean up = facing == Direction.UP;
        switch (worldSide) {
            case UP:
                return up ? Direction.SOUTH : Direction.NORTH;
            case NORTH:
                return up ? Direction.UP : Direction.DOWN;
            case DOWN:
                return up ? Direction.NORTH : Direction.SOUTH;
            case SOUTH:
                return up ? Direction.DOWN : Direction.UP;
            default:
                return worldSide;
        }
    }

    private static int index(Direction d) {
        for (int i = 0; i < 4; i++) if (H[i] == d) return i;
        return -1;
    }
}
