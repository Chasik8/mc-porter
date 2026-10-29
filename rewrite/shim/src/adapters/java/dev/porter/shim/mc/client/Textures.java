// porter:adapter (generic, client) - 1.19.2 TextureManager.getTexture(location) loads (registers) a SimpleTexture
// when the location is not loaded yet; the 1.7.10 method of the same name returns null then.
package dev.porter.shim.mc.client;

import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

public final class Textures {
    private Textures() {}

    /** The texture object of a location, loaded on first use (a missing file becomes the missing texture). */
    public static ITextureObject get(TextureManager tm, ResourceLocation loc) {
        if (loc == null) {
            loc = net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture; // a GUI without a texture draws nothing
        }
        ITextureObject t = tm.getTexture(loc);
        if (t == null) {
            t = new SimpleTexture(loc);
            tm.loadTexture(loc, t);
        }
        return t;
    }
}
