package dev.porter.shim;

/**
 * Block position for 1.7.10 code ported by porter (immutable; {@link MutableBlockPos} is the 1.19.2 mutable cursor). Mirrors the subset of the 1.19.2
 * {@code net.minecraft.core.BlockPos}/{@code Vec3i} API that rewrite rules keep as-is; 1.7.10 APIs take
 * {@code getX(), getY(), getZ()} (rules expand calls). The long packing is bit-identical to 1.19.2 so saved
 * {@code asLong()} values stay compatible.
 *
 * <p>Part of the porter shim: coordinates, directions and small world helpers only (no Level/BlockEntity/Menu).
 * Java 8, no dependencies.
 */
public class BlockPos implements Comparable<BlockPos> {
    public static final BlockPos ZERO = new BlockPos(0, 0, 0);

    private static final int PACKED_X_LENGTH = 26;
    private static final int PACKED_Z_LENGTH = 26;
    private static final int PACKED_Y_LENGTH = 64 - PACKED_X_LENGTH - PACKED_Z_LENGTH;
    private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
    private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L;
    private static final int Z_OFFSET = PACKED_Y_LENGTH;
    private static final int X_OFFSET = PACKED_Y_LENGTH + PACKED_Z_LENGTH;

    private int x;
    private int y;
    private int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 1.19.2 {@code new BlockPos(double, double, double)} floors each coordinate. */
    public BlockPos(double x, double y, double z) {
        this((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return dx == 0 && dy == 0 && dz == 0 ? this : new BlockPos(x + dx, y + dy, z + dz);
    }

    public BlockPos offset(BlockPos other) {
        return offset(other.x, other.y, other.z);
    }

    public BlockPos subtract(BlockPos other) {
        return offset(-other.x, -other.y, -other.z);
    }

    public BlockPos multiply(int factor) {
        return factor == 1 ? this : factor == 0 ? ZERO : new BlockPos(x * factor, y * factor, z * factor);
    }

    public BlockPos above() {
        return relative(Direction.UP);
    }

    public BlockPos above(int n) {
        return relative(Direction.UP, n);
    }

    public BlockPos below() {
        return relative(Direction.DOWN);
    }

    public BlockPos below(int n) {
        return relative(Direction.DOWN, n);
    }

    public BlockPos north() {
        return relative(Direction.NORTH);
    }

    public BlockPos north(int n) {
        return relative(Direction.NORTH, n);
    }

    public BlockPos south() {
        return relative(Direction.SOUTH);
    }

    public BlockPos south(int n) {
        return relative(Direction.SOUTH, n);
    }

    public BlockPos west() {
        return relative(Direction.WEST);
    }

    public BlockPos west(int n) {
        return relative(Direction.WEST, n);
    }

    public BlockPos east() {
        return relative(Direction.EAST);
    }

    public BlockPos east(int n) {
        return relative(Direction.EAST, n);
    }

    public BlockPos relative(Direction dir) {
        return new BlockPos(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
    }

    public BlockPos relative(Direction dir, int n) {
        return n == 0 ? this : new BlockPos(x + dir.getStepX() * n, y + dir.getStepY() * n, z + dir.getStepZ() * n);
    }

    public BlockPos relative(Direction.Axis axis, int n) {
        if (n == 0) {
            return this;
        }
        return new BlockPos(x + (axis == Direction.Axis.X ? n : 0), y + (axis == Direction.Axis.Y ? n : 0),
                z + (axis == Direction.Axis.Z ? n : 0));
    }

    public BlockPos atY(int newY) {
        return new BlockPos(x, newY, z);
    }

    public int get(Direction.Axis axis) {
        return axis.choose(x, y, z);
    }

    /** An immutable position (a {@link MutableBlockPos} returns a copy). */
    public BlockPos immutable() {
        return this;
    }

    public double distSqr(BlockPos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public int distManhattan(BlockPos other) {
        return Math.abs(other.x - x) + Math.abs(other.y - y) + Math.abs(other.z - z);
    }

    public boolean closerThan(BlockPos other, double distance) {
        return distSqr(other) < distance * distance;
    }

    public long asLong() {
        return asLong(x, y, z);
    }

    public static long asLong(int x, int y, int z) {
        long l = 0L;
        l |= ((long) x & PACKED_X_MASK) << X_OFFSET;
        l |= ((long) y & PACKED_Y_MASK);
        l |= ((long) z & PACKED_Z_MASK) << Z_OFFSET;
        return l;
    }

    public static int getX(long packed) {
        return (int) (packed << 64 - X_OFFSET - PACKED_X_LENGTH >> 64 - PACKED_X_LENGTH);
    }

    public static int getY(long packed) {
        return (int) (packed << 64 - PACKED_Y_LENGTH >> 64 - PACKED_Y_LENGTH);
    }

    public static int getZ(long packed) {
        return (int) (packed << 64 - Z_OFFSET - PACKED_Z_LENGTH >> 64 - PACKED_Z_LENGTH);
    }

    /** 1.19.2 static {@code BlockPos.offset(long, int, int, int)} on packed positions. */
    public static long offset(long packed, int dx, int dy, int dz) {
        return asLong(getX(packed) + dx, getY(packed) + dy, getZ(packed) + dz);
    }

    /** 1.19.2 static {@code BlockPos.offset(long, Direction)} on packed positions. */
    public static long offset(long packed, Direction dir) {
        return offset(packed, dir.getStepX(), dir.getStepY(), dir.getStepZ());
    }

    public static BlockPos of(long packed) {
        return new BlockPos(getX(packed), getY(packed), getZ(packed));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockPos)) {
            return false;
        }
        BlockPos p = (BlockPos) o;
        return x == p.x && y == p.y && z == p.z;
    }

    /** Same formula as 1.19.2 {@code Vec3i#hashCode}. */
    @Override
    public int hashCode() {
        return (y + z * 31) * 31 + x;
    }

    /** Same ordering as 1.19.2 {@code Vec3i#compareTo}: y, then z, then x. */
    @Override
    public int compareTo(BlockPos o) {
        if (y != o.y) {
            return y - o.y;
        }
        return z == o.z ? x - o.x : z - o.z;
    }

    public String toShortString() {
        return x + ", " + y + ", " + z;
    }

    @Override
    public String toString() {
        return "BlockPos{x=" + x + ", y=" + y + ", z=" + z + "}";
    }

    /**
     * 1.19.2 {@code BlockPos.MutableBlockPos}: a reusable cursor. Methods inherited from BlockPos that would return
     * {@code this} return an immutable copy instead (as in 1.19.2), so a mutable cursor never leaks into maps.
     */
    public static class MutableBlockPos extends BlockPos {
        public MutableBlockPos() {
            this(0, 0, 0);
        }

        public MutableBlockPos(int x, int y, int z) {
            super(x, y, z);
        }

        public MutableBlockPos(double x, double y, double z) {
            this((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        public MutableBlockPos set(int x, int y, int z) {
            ((BlockPos) this).x = x;
            ((BlockPos) this).y = y;
            ((BlockPos) this).z = z;
            return this;
        }

        public MutableBlockPos set(double x, double y, double z) {
            return set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        public MutableBlockPos set(BlockPos p) {
            return set(p.getX(), p.getY(), p.getZ());
        }

        public MutableBlockPos set(long packed) {
            return set(getX(packed), getY(packed), getZ(packed));
        }

        public MutableBlockPos setWithOffset(BlockPos p, Direction d) {
            return set(p.getX() + d.getStepX(), p.getY() + d.getStepY(), p.getZ() + d.getStepZ());
        }

        public MutableBlockPos setWithOffset(BlockPos p, int dx, int dy, int dz) {
            return set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
        }

        public MutableBlockPos setWithOffset(BlockPos p, BlockPos o) {
            return set(p.getX() + o.getX(), p.getY() + o.getY(), p.getZ() + o.getZ());
        }

        public MutableBlockPos move(Direction d) {
            return move(d, 1);
        }

        public MutableBlockPos move(Direction d, int n) {
            return set(getX() + d.getStepX() * n, getY() + d.getStepY() * n, getZ() + d.getStepZ() * n);
        }

        public MutableBlockPos move(int dx, int dy, int dz) {
            return set(getX() + dx, getY() + dy, getZ() + dz);
        }

        public MutableBlockPos move(BlockPos o) {
            return move(o.getX(), o.getY(), o.getZ());
        }

        public MutableBlockPos setX(int x) {
            return set(x, getY(), getZ());
        }

        public MutableBlockPos setY(int y) {
            return set(getX(), y, getZ());
        }

        public MutableBlockPos setZ(int z) {
            return set(getX(), getY(), z);
        }

        @Override
        public BlockPos immutable() {
            return new BlockPos(getX(), getY(), getZ());
        }

        @Override
        public BlockPos offset(int dx, int dy, int dz) {
            return super.offset(dx, dy, dz).immutable();
        }

        @Override
        public BlockPos multiply(int factor) {
            return super.multiply(factor).immutable();
        }

        @Override
        public BlockPos relative(Direction dir, int n) {
            return super.relative(dir, n).immutable();
        }

        @Override
        public BlockPos relative(Direction.Axis axis, int n) {
            return super.relative(axis, n).immutable();
        }
    }
}
