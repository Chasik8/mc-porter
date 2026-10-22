package ic2cextras.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;

import ic2cextras.ported.steam.gen.multiblock.Ic2cextrasMultiblocks;

/**
 * The guide book's structures, converted from the porter-generated multiblock definitions
 * ({@link Ic2cextrasMultiblocks}, from the 1.19.2 IC2C wiki previews). The book needs the controller on the front face
 * (z = 0); a definition whose controller sits on the left/right face is turned a quarter so it does. "Any" positions
 * (the tunnel's inside) are shown as air.
 */
public final class Structures {

    private static List<MultiblockDef> all;

    private Structures() {}

    public static synchronized List<MultiblockDef> all() {
        if (all == null) {
            List<MultiblockDef> list = new ArrayList<MultiblockDef>();
            for (ic2cextras.ported.steam.gen.multiblock.MultiblockDef d : Ic2cextrasMultiblocks.all()) {
                MultiblockDef c = convert(d);
                if (c != null) list.add(c);
            }
            if (list.isEmpty()) return Collections.emptyList(); // before init: retry later
            all = Collections.unmodifiableList(list);
        }
        return all;
    }

    private static MultiblockDef convert(ic2cextras.ported.steam.gen.multiblock.MultiblockDef d) {
        String[][] layers = d.layers();
        if (d.ctrlZ != 0 && (d.ctrlX == 0 || d.ctrlX == d.width - 1)) layers = turn(layers, d.ctrlX == 0);
        MultiblockDef.Builder b = MultiblockDef.builder(d.id);
        for (ic2cextras.ported.steam.gen.multiblock.MultiblockDef.Part p : d.palette()
            .values()) {
            Block block = p.block();
            if (p.controller) {
                if (block == null) return null;
                b.controller(p.symbol, block);
            } else if (block == null) {
                b.air(p.symbol);
            } else {
                b.block(p.symbol, block, p.meta);
            }
        }
        return b.layers(layers)
            .build();
    }

    /** Quarter turn about y: the left (x = 0) or right (x = width-1) face becomes the front (z = 0). */
    private static String[][] turn(String[][] layers, boolean leftToFront) {
        int h = layers.length, depth = layers[0].length, width = layers[0][0].length();
        String[][] out = new String[h][width];
        for (int y = 0; y < h; y++) {
            for (int nz = 0; nz < width; nz++) {
                StringBuilder row = new StringBuilder();
                for (int nx = 0; nx < depth; nx++) {
                    int x = leftToFront ? nz : width - 1 - nz;
                    int z = leftToFront ? depth - 1 - nx : nx;
                    row.append(layers[y][z].charAt(x));
                }
                out[y][nz] = row.toString();
            }
        }
        return out;
    }
}
