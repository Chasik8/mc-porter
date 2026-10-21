// porter:adapter (generic) - 1.19.2 Rotation over the shim Direction.
package dev.porter.shim.mc;

import dev.porter.shim.Direction;

public enum Rotation {
    NONE,
    CLOCKWISE_90,
    CLOCKWISE_180,
    COUNTERCLOCKWISE_90;

    public Direction rotate(Direction d) {
        if (d.getAxis() == Direction.Axis.Y) return d;
        switch (this) {
            case CLOCKWISE_90: return d.getClockWise();
            case CLOCKWISE_180: return d.getOpposite();
            case COUNTERCLOCKWISE_90: return d.getCounterClockWise();
            default: return d;
        }
    }

    public Rotation getRotated(Rotation r) { return values()[(ordinal() + r.ordinal()) % 4]; }
}
