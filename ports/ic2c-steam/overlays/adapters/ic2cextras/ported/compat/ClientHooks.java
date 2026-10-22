// porter:adapter (IC2C-specific, client) - client-only calls of the ported platform (loaded on the client only).
package ic2cextras.ported.compat;

import ic2cextras.ported.shim.BlockPos;
import net.minecraft.client.Minecraft;

public final class ClientHooks {
    private ClientHooks() {}

    public static void markForRenderUpdate(BlockPos p) {
        if (Minecraft.getMinecraft().theWorld != null) {
            Minecraft.getMinecraft().theWorld.markBlockRangeForRenderUpdate(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ());
        }
    }

    /** IC2C registered IC2Textures on the Forge bus from its client proxy: sprite maps for models and GUIs. */
    public static void registerTextures() {
        IC2Textures.register();
    }
}
