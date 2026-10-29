// porter:adapter (generic, client) - 1.19.2 items whose model picks a sprite per stack in code on 1.7.10.
// Such an item has no model json: it lists its "model stacks" (one per variant, e.g. a drink per age) and maps a stack
// to a sprite location ("ns:item/<folder>/<variant>"), looked up on the atlas (Minecraft.getTextureAtlas, adapted by
// ClientFluids.atlas). 1.7.10 asks an item for its icons instead: registerIcons(IIconRegister) once per stitch,
// getIconIndex(stack) / getIcon(stack, pass) when it renders. register() runs the item's sprite function over its
// model stacks with the atlas in "registering" mode (ClientFluids.atlas then registers the 1.19.2 locations on the
// 1.7.10 item atlas: "ns:item/a/b" = assets/ns/textures/items/a/b.png); icon() serves the stack's sprite afterwards.
// The item's own icon name (setTextureName, usually derived from the registry name) is only the fallback.
package dev.porter.shim.mc.client;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

public final class ItemSprites {

    /** The atlas an item registers its sprites on (client thread, inside register()), else null. */
    private static TextureMap registering;

    private ItemSprites() {}

    /** The atlas being registered on, or null outside register(). */
    public static TextureMap registering() {
        return registering;
    }

    /**
     * Item.registerIcons: registers the sprite of every model stack; returns the icon of the first one (the item's
     * default icon), or {@code fallbackName} registered the 1.7.10 way when the model yields none.
     */
    public static IIcon register(IIconRegister reg, Supplier<? extends List<ItemStack>> modelStacks,
        Function<ItemStack, ? extends IIcon> sprite, String fallbackName) {
        IIcon first = null;
        TextureMap prev = registering;
        registering = reg instanceof TextureMap ? (TextureMap) reg : null;
        try {
            List<ItemStack> stacks = modelStacks.get();
            if (stacks != null) for (ItemStack s : stacks) {
                IIcon i = safe(sprite, s);
                if (first == null) first = i;
            }
        } catch (RuntimeException e) {
            // a model list that needs more than the item (world, config): the fallback icon
        } finally {
            registering = prev;
        }
        return first != null ? first : reg.registerIcon(fallbackName == null ? "missingno" : fallbackName);
    }

    /** Item.getIconIndex / getIcon(stack, pass): the stack's sprite, or the item's default icon. */
    public static IIcon icon(ItemStack stack, Function<ItemStack, ? extends IIcon> sprite, IIcon fallback) {
        if (stack == null) return fallback;
        IIcon i = safe(sprite, stack);
        return i != null ? i : fallback;
    }

    private static IIcon safe(Function<ItemStack, ? extends IIcon> sprite, ItemStack s) {
        try {
            return sprite.apply(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
