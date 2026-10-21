// porter:adapter (generic) - 1.19.2 DirectionalBlock: a StateBlock with the six-way FACING property.
package dev.porter.shim.mc;

public abstract class DirectionalBlock extends StateBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    protected DirectionalBlock(BlockBehaviour.Properties props) { super(props); }
}
