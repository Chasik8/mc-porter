// porter:adapter (generic) - 1.19.2 AirBlock (new AirBlock(props), e.g. invisible light sources): air to 1.7.10
// (isAir, replaceable, no collision, not rendered, no drops), light from the properties.
package dev.porter.shim.mc;

import java.util.ArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public class AirBlock extends StateBlock {
    public AirBlock(BlockBehaviour.Properties props) {
        super(props.noOcclusion());
        this.setBlockBounds(0, 0, 0, 0, 0, 0);
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockAccess w, dev.porter.shim.BlockPos pos, CollisionContext ctx) { return VoxelShape.empty(); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    public boolean isAir(IBlockAccess w, int x, int y, int z) { return true; }

    @Override
    public boolean isReplaceable(IBlockAccess w, int x, int y, int z) { return true; }

    @Override
    public int getRenderType() { return -1; }

    @Override
    public boolean isOpaqueCube() { return false; }

    @Override
    public boolean renderAsNormalBlock() { return false; }

    @Override
    public boolean isCollidable() { return false; }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) { return null; }

    @Override
    public ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) { return new ArrayList<>(); }

    @Override
    public boolean canDropFromExplosion(net.minecraft.world.Explosion e) { return false; }
}
