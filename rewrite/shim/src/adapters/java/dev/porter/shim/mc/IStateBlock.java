// porter:adapter (generic) - a 1.7.10 block that carries a 1.19.2-style StateDefinition.
package dev.porter.shim.mc;

public interface IStateBlock {
    StateDefinition<net.minecraft.block.Block, BlockState> getStateDefinition();

    BlockState defaultBlockState();
}
