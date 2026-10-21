// porter:adapter (generic) - 1.19.2 BlockEntityTicker; ModernTileEntity.updateEntity() calls the block's ticker.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public interface BlockEntityTicker<T extends TileEntity> {
    void tick(World level, BlockPos pos, BlockState state, T tile);
}
