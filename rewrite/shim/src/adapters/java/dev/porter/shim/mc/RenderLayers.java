// porter:adapter (generic) - 1.19.2 ItemBlockRenderTypes.setRenderLayer: no-op on 1.7.10 (non-opaque blocks render
// in the cutout pass by default; translucency is Block.getRenderBlockPass()).
package dev.porter.shim.mc;

public final class RenderLayers {
    private RenderLayers() {}

    public static void setRenderLayer(Object block, Object layer) {}
}
