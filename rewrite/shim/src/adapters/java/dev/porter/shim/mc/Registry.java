// porter:adapter (generic) - 1.19.2 vanilla Registry: the static registries the ported code reads.
package dev.porter.shim.mc;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

public final class Registry<T> {
    public static final ResourceKey<Registry<World>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(new ResourceLocation("minecraft", "dimension"));
    public static final IForgeRegistry<Block> BLOCK = ForgeRegistries.BLOCKS;
    public static final IForgeRegistry<Item> ITEM = ForgeRegistries.ITEMS;

    private Registry() {}
}
