// porter:adapter (generic) - 1.19.2 Mirror over the shim Direction.
package dev.porter.shim.mc;

import dev.porter.shim.Direction;

public enum Mirror {
    NONE,
    LEFT_RIGHT,
    FRONT_BACK;

    public Direction mirror(Direction d) {
        if (this == FRONT_BACK && d.getAxis() == Direction.Axis.X) return d.getOpposite();
        if (this == LEFT_RIGHT && d.getAxis() == Direction.Axis.Z) return d.getOpposite();
        return d;
    }

    public Rotation getRotation(Direction d) { return mirror(d) != d ? Rotation.CLOCKWISE_180 : Rotation.NONE; }
}
