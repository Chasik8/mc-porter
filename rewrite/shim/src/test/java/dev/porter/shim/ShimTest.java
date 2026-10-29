package dev.porter.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ShimTest {
    @Test
    void neighboursAndOffsets() {
        BlockPos p = new BlockPos(1, 64, -3);
        assertEquals(new BlockPos(1, 65, -3), p.above());
        assertEquals(new BlockPos(1, 62, -3), p.below(2));
        assertEquals(new BlockPos(1, 64, -4), p.north());
        assertEquals(new BlockPos(1, 64, -2), p.south());
        assertEquals(new BlockPos(0, 64, -3), p.west());
        assertEquals(new BlockPos(4, 64, -3), p.east(3));
        assertEquals(new BlockPos(2, 66, 0), p.offset(1, 2, 3));
        assertEquals(new BlockPos(1, 64, 2), p.relative(Direction.SOUTH, 5));
        assertEquals(new BlockPos(1, 64, 7), p.relative(Direction.Axis.Z, 10));
        assertSame(p, p.offset(0, 0, 0));
        assertEquals(BlockPos.ZERO, p.subtract(p));
        assertEquals(new BlockPos(2, 128, -6), p.multiply(2));
        assertEquals(64, p.get(Direction.Axis.Y));
        assertEquals(new BlockPos(1, 64, -3), new BlockPos(1.7, 64.2, -2.5));
    }

    @Test
    void packedOffsets() {
        long p = new BlockPos(-5, 70, 12).asLong();
        assertEquals(new BlockPos(-4, 69, 15).asLong(), BlockPos.offset(p, 1, -1, 3));
        assertEquals(new BlockPos(-5, 70, 11).asLong(), BlockPos.offset(p, Direction.NORTH));
    }

    @Test
    void mutableCursorNeverLeaks() {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(1, 2, 3);
        assertSame(m, m.move(Direction.UP).move(Direction.EAST, 2));
        assertEquals(new BlockPos(3, 3, 3), m);
        BlockPos frozen = m.immutable();
        m.set(0, 0, 0);
        assertEquals(new BlockPos(3, 3, 3), frozen);
        assertTrue(!(m.offset(0, 0, 0) instanceof BlockPos.MutableBlockPos));
        assertEquals(new BlockPos(1, 0, 0), m.setWithOffset(BlockPos.ZERO, Direction.EAST));
        assertEquals(BlockPos.ZERO.hashCode(), m.set(0, 0, 0).hashCode());
    }

    @Test
    void packingMatches1192Layout() {
        // 1.19.2: x in the top 26 bits, then z (26 bits), y in the low 12 bits.
        assertEquals(0L, BlockPos.ZERO.asLong());
        assertEquals(1L, new BlockPos(0, 1, 0).asLong());
        assertEquals(1L << 12, new BlockPos(0, 0, 1).asLong());
        assertEquals(1L << 38, new BlockPos(1, 0, 0).asLong());
        assertEquals(-1L, new BlockPos(-1, -1, -1).asLong());
        for (BlockPos p : new BlockPos[] {new BlockPos(29_999_999, 2047, -29_999_999), new BlockPos(-5, -64, 12),
                new BlockPos(123, 319, 456)}) {
            assertEquals(p, BlockPos.of(p.asLong()));
        }
    }

    @Test
    void equalityOrderingDistance() {
        assertEquals((64 + -3 * 31) * 31 + 1, new BlockPos(1, 64, -3).hashCode());
        assertTrue(new BlockPos(9, 1, 9).compareTo(new BlockPos(0, 2, 0)) < 0);
        assertTrue(new BlockPos(1, 1, 1).compareTo(new BlockPos(0, 1, 2)) < 0);
        assertEquals(3.0, new BlockPos(0, 0, 0).distSqr(new BlockPos(1, 1, 1)));
        assertEquals(3, new BlockPos(0, 0, 0).distManhattan(new BlockPos(1, -1, 1)));
        assertEquals("1, 2, 3", new BlockPos(1, 2, 3).toShortString());
    }

    @Test
    void directionsMatch1710Sides() {
        Direction[] bySide = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int[][] forgeOffsets = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        int[] forgeOpposites = {1, 0, 3, 2, 5, 4};
        for (int side = 0; side < 6; side++) {
            Direction d = bySide[side];
            assertEquals(side, d.get3DDataValue());
            assertEquals(side, d.ordinal());
            assertSame(d, Direction.from3DDataValue(side));
            assertEquals(forgeOffsets[side][0], d.getStepX());
            assertEquals(forgeOffsets[side][1], d.getStepY());
            assertEquals(forgeOffsets[side][2], d.getStepZ());
            assertEquals(forgeOpposites[side], d.getOpposite().get3DDataValue());
            assertEquals(d.getAxis().choose(d.getStepX(), d.getStepY(), d.getStepZ()), d.getAxisDirection().getStep());
            assertSame(d, Direction.fromNormal(d.getStepX(), d.getStepY(), d.getStepZ()));
            assertSame(d, Direction.byName(d.getName()));
        }
    }

    @Test
    void horizontalRotation() {
        assertSame(Direction.EAST, Direction.NORTH.getClockWise());
        assertSame(Direction.WEST, Direction.NORTH.getCounterClockWise());
        assertSame(Direction.SOUTH, Direction.from2DDataValue(0));
        assertSame(Direction.WEST, Direction.fromYRot(90));
        assertEquals(180f, Direction.NORTH.toYRot());
        assertThrows(IllegalStateException.class, Direction.UP::getClockWise);
        assertTrue(Direction.EAST.getAxis().isHorizontal());
        assertTrue(Direction.Plane.HORIZONTAL.test(Direction.WEST));
        assertSame(Direction.Plane.VERTICAL, Direction.Axis.Y.getPlane());
        assertNull(Direction.byName("sideways"));
        assertSame(Direction.DOWN, Direction.get(Direction.AxisDirection.NEGATIVE, Direction.Axis.Y));
    }

    @Test
    void compiledForJava8() throws Exception {
        for (Class<?> c : new Class<?>[] {BlockPos.class, Direction.class, Direction.Axis.class}) {
            try (InputStream in = c.getResourceAsStream(c.getSimpleName().replace('.', '$') + ".class")) {
                InputStream src = in != null ? in
                        : c.getResourceAsStream(c.getName().substring(c.getName().lastIndexOf('.') + 1) + ".class");
                DataInputStream data = new DataInputStream(src);
                assertEquals(0xCAFEBABE, data.readInt());
                data.readUnsignedShort();
                assertEquals(52, data.readUnsignedShort(), c + " must target Java 8 (class file 52)");
            }
        }
    }

    /** The adapter needs the 1.7.10 jar to run, so guard the source: 1.19.2 code compares ResourceKeys with ==, every factory must intern. */
    @Test
    void resourceKeyFactoriesIntern() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of("src/adapters/java/dev/porter/shim/mc/ResourceKey.java");
        String src = java.nio.file.Files.readString(p);
        assertTrue(src.contains("ConcurrentHashMap"));
        int news = src.split("new ResourceKey<", -1).length - 1;
        assertEquals(1, news, "only intern() may construct a ResourceKey");
    }
}
