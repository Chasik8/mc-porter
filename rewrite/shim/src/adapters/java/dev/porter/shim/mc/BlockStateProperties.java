// porter:adapter (generic) - 1.19.2 BlockStateProperties constants the ported code checks. 1.7.10 vanilla blocks
// carry no properties (their states are legacy block + meta), so hasProperty(...) is false for them.
package dev.porter.shim.mc;

import dev.porter.shim.Direction;

public final class BlockStateProperties {
    public static final BooleanProperty WATERLOGGED = BooleanProperty.create("waterlogged");
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final DirectionProperty FACING = DirectionProperty.create("facing");
    public static final DirectionProperty HORIZONTAL_FACING = DirectionProperty.create("facing", Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, 15);

    private BlockStateProperties() {}
}
