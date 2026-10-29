// porter:adapter (the mod-specific, PRIVATE) - Shapes.join(a, b, op) for the single-box VoxelShape of the 1.7.10 shim.
// A shape is one axis-aligned box, so a boolean subtraction cannot be represented: ONLY_SECOND (b minus a, the pipe
// anchor part) is approximated as b, ONLY_FIRST as a, the unions as the bounding box.
package dev.porter.shim.mc;



public final class PipeShapes {
    private PipeShapes() {}

    public static VoxelShape join(VoxelShape a, VoxelShape b, BooleanOp op) {
        switch (op) {
            case ONLY_SECOND:
            case SECOND:
                return b;
            case ONLY_FIRST:
            case FIRST:
                return a;
            case FALSE:
                return VoxelShape.empty();
            case AND:
                return a.isEmpty() || b.isEmpty() ? VoxelShape.empty() : a;
            default:
                return VoxelShape.or(a, b);
        }
    }
}
