package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.V;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Turns positioned block references into an IR multiblock (layers bottom-to-top, rows along z, chars along x). */
public final class MultiblockSupport {
    static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static final char ANY = '.';

    private MultiblockSupport() {
    }

    /** A palette entry before char assignment. */
    public record Ref(String block, Map<String, String> state) {
        String key() {
            return block + (state == null || state.isEmpty() ? "" : new TreeMap<>(state).toString());
        }

        IrNode.BlockRef toIr() {
            return state == null || state.isEmpty() ? IrNode.BlockRef.of(block)
                    : new IrNode.BlockRef(block, new TreeMap<>(state), null);
        }
    }

    /**
     * @param blocks     position → block; positions inside the bounding box that are absent become wildcards
     * @param controller controller position (absolute, same frame as blocks), may be null
     */
    public static IrNode.Multiblock build(String id, Map<V.Pos, Ref> blocks, V.Pos controller, String controllerFacing,
            Provenance prov, Double confidence, Map<String, JsonNode> ext) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (V.Pos p : blocks.keySet()) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        Map<String, Ref> distinct = new TreeMap<>();
        for (Ref r : blocks.values()) {
            distinct.putIfAbsent(r.key(), r);
        }
        if (distinct.size() > CHARS.length()) {
            return null;
        }
        Map<String, Character> charOf = new LinkedHashMap<>();
        Map<String, IrNode.BlockRef> palette = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Ref> e : distinct.entrySet()) {
            char c = CHARS.charAt(i++);
            charOf.put(e.getKey(), c);
            palette.put(String.valueOf(c), e.getValue().toIr());
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        boolean anyUsed = false;
        List<List<String>> layers = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            List<String> rows = new ArrayList<>();
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = minX; x <= maxX; x++) {
                    Ref r = blocks.get(new V.Pos(x, y, z));
                    if (r == null) {
                        row.append(ANY);
                        anyUsed = true;
                    } else {
                        row.append(charOf.get(r.key()));
                    }
                }
                rows.add(row.toString());
            }
            layers.add(rows);
        }
        if (anyUsed) {
            palette.put(String.valueOf(ANY), new IrNode.BlockRef(null, null, true));
        }
        IrNode.Multiblock.Controller ctrl = null;
        if (controller != null && blocks.containsKey(controller)) {
            ctrl = new IrNode.Multiblock.Controller(blocks.get(controller).block(),
                    List.of(controller.x() - minX, controller.y() - minY, controller.z() - minZ), controllerFacing);
        }
        return new IrNode.Multiblock(id, List.of(sx, sy, sz), palette, layers, ctrl, null, null, prov, confidence,
                ext == null || ext.isEmpty() ? null : ext);
    }
}
