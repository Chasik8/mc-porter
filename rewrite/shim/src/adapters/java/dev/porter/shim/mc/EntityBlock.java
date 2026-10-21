// porter:adapter (generic) - 1.19.2 EntityBlock (interface form of BaseEntityBlock): newBlockEntity backs the 1.7.10
// createNewTileEntity; getTicker is asked by ModernTileEntity.updateEntity().
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public interface EntityBlock extends ITileEntityProvider {
    TileEntity newBlockEntity(BlockPos pos, BlockState state);

    default <T extends TileEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) { return null; }

    @Override
    default TileEntity createNewTileEntity(World w, int meta) {
        BlockState s = this instanceof StateBlock ? ((StateBlock) this).getStateDefinition().fromMeta(meta, ((StateBlock) this).defaultBlockState())
            : BlockState.of((Block) this, meta);
        return newBlockEntity(new BlockPos(0, 0, 0), s);
    }
}
