package dev.porter.shim;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * The six block faces, mirroring the 1.19.2 {@code net.minecraft.core.Direction} API that rewrite rules keep
 * as-is. {@link #get3DDataValue()} equals the 1.7.10 side number (0 down, 1 up, 2 north, 3 south, 4 west, 5 east)
 * and the {@code ForgeDirection} ordinal; {@code Worlds} (world helpers) converts to/from {@code ForgeDirection}.
 *
 * <p>Part of the porter shim (coordinates, directions, small world helpers only). Java 8, no dependencies.
 */
public enum Direction {
    DOWN(0, 1, -1, "down", AxisDirection.NEGATIVE, Axis.Y, 0, -1, 0),
    UP(1, 0, -1, "up", AxisDirection.POSITIVE, Axis.Y, 0, 1, 0),
    NORTH(2, 3, 2, "north", AxisDirection.NEGATIVE, Axis.Z, 0, 0, -1),
    SOUTH(3, 2, 0, "south", AxisDirection.POSITIVE, Axis.Z, 0, 0, 1),
    WEST(4, 5, 1, "west", AxisDirection.NEGATIVE, Axis.X, -1, 0, 0),
    EAST(5, 4, 3, "east", AxisDirection.POSITIVE, Axis.X, 1, 0, 0);

    private static final Direction[] BY_3D_DATA = {DOWN, UP, NORTH, SOUTH, WEST, EAST};
    private static final Direction[] BY_2D_DATA = {SOUTH, WEST, NORTH, EAST};

    private final int data3d;
    private final int oppositeIndex;
    private final int data2d;
    private final String name;
    private final AxisDirection axisDirection;
    private final Axis axis;
    private final int stepX;
    private final int stepY;
    private final int stepZ;

    Direction(int data3d, int oppositeIndex, int data2d, String name, AxisDirection axisDirection, Axis axis,
            int stepX, int stepY, int stepZ) {
        this.data3d = data3d;
        this.oppositeIndex = oppositeIndex;
        this.data2d = data2d;
        this.name = name;
        this.axisDirection = axisDirection;
        this.axis = axis;
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    /** Index 0..5; equals the 1.7.10 side number and the ForgeDirection ordinal. */
    public int get3DDataValue() {
        return data3d;
    }

    /** Horizontal index (south 0, west 1, north 2, east 3); -1 for up/down. */
    public int get2DDataValue() {
        return data2d;
    }

    public static Direction from3DDataValue(int index) {
        return BY_3D_DATA[Math.abs(index % BY_3D_DATA.length)];
    }

    public static Direction from2DDataValue(int index) {
        return BY_2D_DATA[Math.abs(index % BY_2D_DATA.length)];
    }

    public Direction getOpposite() {
        return from3DDataValue(oppositeIndex);
    }

    public int getStepX() {
        return stepX;
    }

    public int getStepY() {
        return stepY;
    }

    public int getStepZ() {
        return stepZ;
    }

    /** The unit vector of this face (1.19.2 returns a Vec3i). */
    public BlockPos getNormal() {
        return new BlockPos(stepX, stepY, stepZ);
    }

    public Axis getAxis() {
        return axis;
    }

    public AxisDirection getAxisDirection() {
        return axisDirection;
    }

    /** Rotates clockwise around the Y axis (seen from above). Vertical directions throw, as in 1.19.2. */
    public Direction getClockWise() {
        switch (this) {
            case NORTH: return EAST;
            case EAST: return SOUTH;
            case SOUTH: return WEST;
            case WEST: return NORTH;
            default: throw new IllegalStateException("Unable to get Y-rotated facing of " + this);
        }
    }

    public Direction getCounterClockWise() {
        switch (this) {
            case NORTH: return WEST;
            case WEST: return SOUTH;
            case SOUTH: return EAST;
            case EAST: return NORTH;
            default: throw new IllegalStateException("Unable to get CCW facing of " + this);
        }
    }

    public float toYRot() {
        return (float) ((data2d & 3) * 90);
    }

    public static Direction fromYRot(double yRot) {
        return from2DDataValue((int) Math.floor(yRot / 90.0 + 0.5) & 3);
    }

    public static Direction fromNormal(int x, int y, int z) {
        for (Direction d : BY_3D_DATA) {
            if (d.stepX == x && d.stepY == y && d.stepZ == z) {
                return d;
            }
        }
        return null;
    }

    public static Direction get(AxisDirection axisDirection, Axis axis) {
        for (Direction d : BY_3D_DATA) {
            if (d.axisDirection == axisDirection && d.axis == axis) {
                return d;
            }
        }
        throw new IllegalArgumentException("No such direction: " + axisDirection + " " + axis);
    }

    public static Direction fromAxisAndDirection(Axis axis, AxisDirection axisDirection) {
        return get(axisDirection, axis);
    }

    /** By lower-case name ("north"), null if unknown; as in 1.19.2. */
    public static Direction byName(String name) {
        if (name == null) {
            return null;
        }
        for (Direction d : BY_3D_DATA) {
            if (d.name.equals(name.toLowerCase(Locale.ROOT))) {
                return d;
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public String getSerializedName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    public enum Axis {
        X("x"), Y("y"), Z("z");

        private final String name;

        Axis(String name) {
            this.name = name;
        }

        public boolean isVertical() {
            return this == Y;
        }

        public boolean isHorizontal() {
            return this != Y;
        }

        public int choose(int x, int y, int z) {
            return this == X ? x : this == Y ? y : z;
        }

        public double choose(double x, double y, double z) {
            return this == X ? x : this == Y ? y : z;
        }

        public boolean test(Direction d) {
            return d != null && d.getAxis() == this;
        }

        public Plane getPlane() {
            return this == Y ? Plane.VERTICAL : Plane.HORIZONTAL;
        }

        public String getName() {
            return name;
        }

        public String getSerializedName() {
            return name;
        }

        public static Axis byName(String name) {
            for (Axis a : values()) {
                if (a.name.equals(name)) {
                    return a;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum AxisDirection {
        POSITIVE(1, "Towards positive"), NEGATIVE(-1, "Towards negative");

        private final int step;
        private final String name;

        AxisDirection(int step, String name) {
            this.step = step;
            this.name = name;
        }

        public int getStep() {
            return step;
        }

        public String getName() {
            return name;
        }

        public AxisDirection opposite() {
            return this == POSITIVE ? NEGATIVE : POSITIVE;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum Plane implements Iterable<Direction> {
        HORIZONTAL(new Direction[] {NORTH, EAST, SOUTH, WEST}),
        VERTICAL(new Direction[] {UP, DOWN});

        private final Direction[] faces;

        Plane(Direction[] faces) {
            this.faces = faces;
        }

        public boolean test(Direction d) {
            return d != null && Arrays.asList(faces).contains(d);
        }

        @Override
        public Iterator<Direction> iterator() {
            return Arrays.asList(faces).iterator();
        }

        public java.util.stream.Stream<Direction> stream() {
            return Arrays.stream(faces);
        }
    }
}
