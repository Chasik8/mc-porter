// porter:adapter (IC2C-specific, client) - IC2C's ITextureProvider type on 1.7.10 (the hub type is redirected
// here by a rule): a block's sprite per state and side, looked up in the port's texture packages (compat.IC2Textures).
// The two kinds the ported blocks use: sprites named by side, and "active_"/"inactive_" + side by the ACTIVE property.
package ic2cextras.ported.compat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.ported.core.platform.registries.IC2Properties;
import ic2cextras.ported.shim.Direction;
import ic2cextras.ported.shim.mc.BlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public interface ITextureProvider {

    @SideOnly(Side.CLIENT)
    TextureAtlasSprite getTexture(BlockState state, Direction side);

    /** sprites "active_<side>" / "inactive_<side>" of the block package {@code path}, by the state's ACTIVE flag */
    static ITextureProvider toggleIC2(String path) {
        // anonymous classes, not lambdas: on a dedicated server @SideOnly strips the interface method
        return new ITextureProvider() {
            @Override
            @SideOnly(Side.CLIENT)
            public TextureAtlasSprite getTexture(BlockState state, Direction side) {
                boolean on = state != null && state.hasProperty(IC2Properties.ACTIVE) && state.getValue(IC2Properties.ACTIVE);
                return sprite(path, (on ? "active_" : "inactive_") + side.getName());
            }
        };
    }

    /** sprite "<side>" of the block package {@code path} */
    static ITextureProvider noStateIC2(String path) {
        return new ITextureProvider() {
            @Override
            @SideOnly(Side.CLIENT)
            public TextureAtlasSprite getTexture(BlockState state, Direction side) {
                return sprite(path, side.getName());
            }
        };
    }

    @SideOnly(Side.CLIENT)
    static TextureAtlasSprite sprite(String blockPackage, String name) {
        return IC2Textures.getMappedEntriesBlockIC2(blockPackage).get(name);
    }
}
