package ic2cextras.book;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * The guide book's view of a multiblock (hand-port model, carried over with the book). Built by {@link Structures}
 * from the porter-generated {@code ic2cextras.ported.steam.gen.multiblock.MultiblockDef}s.
 * <p>
 * Local coordinates, seen by a player standing in front of the controller and looking at it:
 * <ul>
 * <li>x: 0 = left .. width-1 = right</li>
 * <li>y: 0 = bottom .. height-1 = top</li>
 * <li>z: 0 = front face (the controller's face) .. depth-1 = back</li>
 * </ul>
 * {@code layers[y][z]} is a string of {@code width} palette symbols, one per x.
 */
public final class MultiblockDef {

    /** What may stand at a position. */
    public static final class Part {

        public final char symbol;
        /** The block the book shows for this symbol; null for air. */
        public final Block block;
        /** Required metadata, or -1 for any. */
        public final int meta;
        /** Optional alternative (e.g. a valve on a non-edge shell position), null if none. */
        public final Block altBlock;
        public final int altMeta;
        public final boolean controller;

        Part(char symbol, Block block, int meta, Block altBlock, int altMeta, boolean controller) {
            this.symbol = symbol;
            this.block = block;
            this.meta = meta;
            this.altBlock = altBlock;
            this.altMeta = altMeta;
            this.controller = controller;
        }

        public boolean isAir() {
            return block == null;
        }

        public boolean matches(Block b, int m) {
            if (block == null) return false;
            if (b == block && (meta < 0 || m == meta)) return true;
            return altBlock != null && b == altBlock && (altMeta < 0 || m == altMeta);
        }

        public boolean isAlt(Block b, int m) {
            return altBlock != null && b == altBlock && (altMeta < 0 || m == altMeta);
        }

        /** Stack for the book legend / tooltips; null for air. */
        public ItemStack displayStack() {
            return block == null ? null : new ItemStack(block, 1, Math.max(0, meta));
        }

        public ItemStack altDisplayStack() {
            return altBlock == null ? null : new ItemStack(altBlock, 1, Math.max(0, altMeta));
        }
    }

    public final String id;
    public final int width, height, depth;
    public final int ctrlX, ctrlY, ctrlZ;
    private final String[][] layers;
    private final Map<Character, Part> palette;

    private MultiblockDef(String id, String[][] layers, Map<Character, Part> palette) {
        this.id = id;
        this.layers = layers;
        this.palette = Collections.unmodifiableMap(palette);
        height = layers.length;
        depth = layers[0].length;
        width = layers[0][0].length();
        int cx = -1, cy = -1, cz = -1;
        for (int y = 0; y < height; y++) {
            if (layers[y].length != depth) throw new IllegalArgumentException(id + ": ragged layer " + y);
            for (int z = 0; z < depth; z++) {
                String row = layers[y][z];
                if (row.length() != width) throw new IllegalArgumentException(id + ": ragged row " + y + "/" + z);
                for (int x = 0; x < width; x++) {
                    Part p = palette.get(row.charAt(x));
                    if (p == null) throw new IllegalArgumentException(id + ": unknown symbol " + row.charAt(x));
                    if (p.controller) {
                        if (cx >= 0) throw new IllegalArgumentException(id + ": two controllers");
                        cx = x;
                        cy = y;
                        cz = z;
                    }
                }
            }
        }
        if (cx < 0) throw new IllegalArgumentException(id + ": no controller");
        ctrlX = cx;
        ctrlY = cy;
        ctrlZ = cz;
    }

    public Part partAt(int x, int y, int z) {
        return palette.get(layers[y][z].charAt(x));
    }

    public Map<Character, Part> palette() {
        return palette;
    }

    /** Number of positions with this symbol. */
    public int count(char symbol) {
        int n = 0;
        for (String[] layer : layers)
            for (String row : layer) for (int i = 0; i < row.length(); i++) if (row.charAt(i) == symbol) n++;
        return n;
    }

    /** Copy of the raw layers, [y bottom..top][z front..back]. */
    public String[][] layers() {
        String[][] copy = new String[height][];
        for (int y = 0; y < height; y++) copy[y] = layers[y].clone();
        return copy;
    }

    /**
     * World position of local (x, y, z) for a controller at (cx, cy, cz) whose front faces {@code facing} (2..5).
     */
    public int[] toWorld(int cx, int cy, int cz, int facing, int x, int y, int z) {
        ForgeDirection front = ForgeDirection.getOrientation(facing);
        // into the structure = opposite of the front; right = clockwise of that, as seen from above
        int bx = -front.offsetX, bz = -front.offsetZ;
        int rx = -bz, rz = bx;
        int dx = x - ctrlX, dy = y - ctrlY, dz = z - ctrlZ;
        return new int[] { cx + rx * dx + bx * dz, cy + dy, cz + rz * dx + bz * dz };
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {

        private final String id;
        private final Map<Character, Part> palette = new LinkedHashMap<Character, Part>();
        private String[][] layers;

        private Builder(String id) {
            this.id = id;
        }

        public Builder controller(char symbol, Block block) {
            palette.put(symbol, new Part(symbol, block, -1, null, 0, true));
            return this;
        }

        public Builder block(char symbol, Block block, int meta) {
            palette.put(symbol, new Part(symbol, block, meta, null, 0, false));
            return this;
        }

        public Builder blockOr(char symbol, Block block, int meta, Block alt, int altMeta) {
            palette.put(symbol, new Part(symbol, block, meta, alt, altMeta, false));
            return this;
        }

        public Builder air(char symbol) {
            palette.put(symbol, new Part(symbol, null, -1, null, 0, false));
            return this;
        }

        /** Layers bottom to top; each layer lists rows front to back. */
        public Builder layers(String[]... layers) {
            this.layers = layers;
            return this;
        }

        public MultiblockDef build() {
            return new MultiblockDef(id, layers, palette);
        }
    }
}
