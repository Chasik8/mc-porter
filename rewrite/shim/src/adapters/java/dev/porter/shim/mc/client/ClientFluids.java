// porter:adapter (generic, client) - Forge 1.19.2 IClientFluidTypeExtensions + Minecraft.getTextureAtlas on 1.7.10:
// fluid textures are 1.7.10 fluid icons; "texture locations" of fluids are tokens that atlas(...) resolves.
package dev.porter.shim.mc.client;

import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

public final class ClientFluids {
    /** MissingTextureAtlasSprite.getLocation() */
    public static final ResourceLocation MISSING = new ResourceLocation("porter", "missingno");
    private final Fluid fluid;

    private ClientFluids(Fluid fluid) { this.fluid = fluid; }

    public static ClientFluids of(Fluid f) { return new ClientFluids(f); }

    public static ClientFluids of(FluidStack s) { return new ClientFluids(s == null ? null : s.getFluid()); }

    public ResourceLocation getStillTexture() { return fluid == null ? MISSING : new ResourceLocation("porter-fluid", fluid.getName() + "/still"); }

    public ResourceLocation getStillTexture(FluidStack s) { return getStillTexture(); }

    public ResourceLocation getFlowingTexture() { return fluid == null ? MISSING : new ResourceLocation("porter-fluid", fluid.getName() + "/flowing"); }

    public ResourceLocation getFlowingTexture(FluidStack s) { return getFlowingTexture(); }

    public int getTintColor() { return fluid == null ? -1 : 0xFF000000 | fluid.getColor(); }

    public int getTintColor(FluidStack s) { return s == null || s.getFluid() == null ? -1 : 0xFF000000 | s.getFluid().getColor(s); }

    public static ResourceLocation missingLocation() { return MISSING; }

    /**
     * Minecraft.getTextureAtlas(atlas): location -> sprite on the block atlas (fluid tokens -> fluid icons; "ns:item/x"
     * -> the item atlas; inside ItemSprites.register the location is registered instead).
     */
    public static Function<ResourceLocation, TextureAtlasSprite> atlas(Minecraft mc, ResourceLocation atlas) {
        return loc -> {
            TextureMap map = (TextureMap) mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture);
            if (loc != null && "porter-fluid".equals(loc.getResourceDomain())) {
                String p = loc.getResourcePath();
                Fluid f = FluidRegistry.getFluid(p.substring(0, p.lastIndexOf('/')));
                IIcon i = f == null ? null : p.endsWith("/flowing") ? f.getFlowingIcon() : f.getStillIcon();
                if (i instanceof TextureAtlasSprite) return (TextureAtlasSprite) i;
            }
            // an item registering its 1.19.2 model sprites (ItemSprites.register): register the location on that atlas
            TextureMap reg = ItemSprites.registering();
            if (reg != null && loc != null && !MISSING.equals(loc)) {
                String p = loc.getResourcePath();
                p = p.startsWith("item/") ? "items/" + p.substring(5) : p.startsWith("block/") ? "blocks/" + p.substring(6) : p;
                return AtlasSprites.register(reg, loc.getResourceDomain(), p);
            }
            // 1.19.2 item sprites ("ns:item/x") live on the 1.7.10 item atlas as "ns:x"
            if (loc != null && loc.getResourcePath().startsWith("item/")) {
                TextureMap items = (TextureMap) mc.getTextureManager().getTexture(TextureMap.locationItemsTexture);
                if (items != null) {
                    String n = loc.getResourceDomain() + ":" + loc.getResourcePath().substring(5);
                    TextureAtlasSprite s = items.getTextureExtry(n);
                    return s != null ? s : items.getAtlasSprite(n);
                }
            }
            if (map == null) return null;
            if (loc == null || MISSING.equals(loc)) return map.getAtlasSprite("missingno");
            // 1.19.2 sprite names are texture paths ("ns:block/x", "ns:misc/gui/x"); 1.7.10 block icons drop the
            // "block/" folder, sprites at other paths are registered through AtlasSprites
            String ns = loc.getResourceDomain(), path = loc.getResourcePath();
            for (String name : new String[] {loc.toString(), path.startsWith("block/") ? ns + ":" + path.substring(6) : null,
                AtlasSprites.spriteName(ns, path)}) {
                TextureAtlasSprite s = name == null ? null : map.getTextureExtry(name);
                if (s != null) return s;
            }
            return map.getAtlasSprite(loc.toString());
        };
    }
}
