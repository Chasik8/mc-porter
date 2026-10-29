// porter:adapter (generic) - placeholders for 1.19.2 vanilla items/blocks that 1.7.10 has no counterpart for
// (netherite, deepslate, tuff, soul soil, shield, barrier, chain, magma block). Unregistered and never obtainable,
// so a recipe or registry entry that names one matches nothing and is skipped.
package dev.porter.shim.mc;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;

public final class ModernItems {
    private ModernItems() {}

    public static final Item ABSENT = new Item().setUnlocalizedName("porter.absent");

    public static final Block ABSENT_BLOCK = new Block(Material.iron) {}.setBlockName("porter.absent");
}
