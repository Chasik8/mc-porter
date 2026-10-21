// porter:adapter (generic) - 1.19.2 BaseEntityBlock / EntityBlock on 1.7.10: newBlockEntity(pos, state) backs
// createNewTileEntity, getTicker(...) is called by ModernTileEntity.updateEntity().
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public abstract class StateEntityBlock extends StateBlock implements ITileEntityProvider {
    protected StateEntityBlock(BlockBehaviour.Properties props) {
        super(props);
        this.isBlockContainer = true;
    }

    public abstract TileEntity newBlockEntity(BlockPos pos, BlockState state);

    public <T extends TileEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) { return null; }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return newBlockEntity(new BlockPos(0, 0, 0), stateDefinition.fromMeta(meta, defaultBlockState()));
    }

    @Override
    public boolean hasTileEntity(int meta) { return true; }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    public boolean onBlockEventReceived(World w, int x, int y, int z, int id, int param) {
        super.onBlockEventReceived(w, x, y, z, id, param);
        TileEntity te = w.getTileEntity(x, y, z);
        return te != null && te.receiveClientEvent(id, param);
    }
}
