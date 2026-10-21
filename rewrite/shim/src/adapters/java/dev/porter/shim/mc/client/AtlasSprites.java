// porter:adapter (generic, client) - 1.19.2 atlas sprites at any texture path on a 1.7.10 TextureMap.
// A 1.19.2 atlas stitches any texture ("ns:misc/gui/add" = assets/ns/textures/misc/gui/add.png, e.g. GUI icons that
// are blitted from the block atlas); a 1.7.10 TextureMap only loads textures/blocks|items/<name>.png. register()
// adds a sprite whose Forge custom loader reads the full path. Call it from TextureStitchEvent.Pre.
package dev.porter.shim.mc.client;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;

public final class AtlasSprites {
    private AtlasSprites() {}

    /**
     * Registers the texture {@code assets/<ns>/textures/<path>.png} on the atlas. Paths below the atlas' own folder
     * (blocks/ on the block atlas, items/ on the item atlas) are plain 1.7.10 icons; anything else gets a sprite
     * with a custom loader. Returns the sprite (filled when the atlas is stitched).
     */
    public static TextureAtlasSprite register(TextureMap map, String ns, String path) {
        String own = map.getTextureType() == 0 ? "blocks/" : "items/";
        if (path.startsWith(own)) return (TextureAtlasSprite) map.registerIcon(ns + ":" + path.substring(own.length()));
        String name = spriteName(ns, path);
        TextureAtlasSprite s = map.getTextureExtry(name);
        if (s == null) {
            s = new PathSprite(name, new ResourceLocation(ns, "textures/" + path + ".png"));
            map.setTextureEntry(name, s);
        }
        return s;
    }

    /** Atlas name of a sprite registered for a texture path outside the atlas' own folder. */
    public static String spriteName(String ns, String path) {
        return ns + ":porter_path/" + path;
    }

    static final class PathSprite extends TextureAtlasSprite {
        private final ResourceLocation file;

        PathSprite(String name, ResourceLocation file) {
            super(name);
            this.file = file;
        }

        @Override
        public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) { return true; }

        /** Forge 1.7.10 stitches the sprite when load() returns false (the javadoc says the opposite). */
        @Override
        public boolean load(IResourceManager manager, ResourceLocation location) {
            try {
                IResource res = manager.getResource(file);
                BufferedImage[] images = new BufferedImage[1 + Minecraft.getMinecraft().gameSettings.mipmapLevels];
                try (InputStream in = res.getInputStream()) {
                    images[0] = ImageIO.read(in);
                }
                AnimationMetadataSection anim = (AnimationMetadataSection) res.getMetadata("animation");
                loadSprite(images, anim, Minecraft.getMinecraft().gameSettings.anisotropicFiltering > 1);
                return false;
            } catch (Exception e) {
                cpw.mods.fml.client.FMLClientHandler.instance().trackMissingTexture(file);
                return true;
            }
        }
    }
}
