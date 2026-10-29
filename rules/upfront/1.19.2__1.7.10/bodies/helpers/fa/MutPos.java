// porter:helper (generic) - generated MutableBlockPos shell backed by the shim's mutable position.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.BlockPos;

public final class MutPos {
    private MutPos() {}

    public static BlockPos.MutableBlockPos real(Object gen) {
        Object v = GenAttach.get(gen, "mut");
        if (v instanceof BlockPos.MutableBlockPos) {
            return (BlockPos.MutableBlockPos) v;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        GenAttach.put(gen, "mut", p);
        return p;
    }

    public static void init(Object gen, int x, int y, int z) {
        GenAttach.put(gen, "mut", new BlockPos.MutableBlockPos(x, y, z));
    }
}
