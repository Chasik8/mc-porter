// porter:adapter (generic) - texture atlas locations that server-side code (containers, slots) names without loading
// the client class TextureMap.
package dev.porter.shim.mc;

import net.minecraft.util.ResourceLocation;

public final class Atlases {
    private Atlases() {}

    /** InventoryMenu.BLOCK_ATLAS == TextureMap.locationBlocksTexture */
    public static final ResourceLocation BLOCKS = new ResourceLocation("textures/atlas/blocks.png");
}
