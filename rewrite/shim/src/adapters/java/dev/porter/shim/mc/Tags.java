// porter:adapter (generic) - Forge 1.19.2 common Tags used by ported code (predicate tags on 1.7.10).
package dev.porter.shim.mc;

public final class Tags {
    private Tags() {}

    public static final class Fluids {
        public static final TagKey<net.minecraftforge.fluids.Fluid> GASEOUS = TagKey.<net.minecraftforge.fluids.Fluid>of("fluid", "forge:gaseous")
            .withPredicate(o -> o instanceof net.minecraftforge.fluids.Fluid && ((net.minecraftforge.fluids.Fluid) o).isGaseous());

        private Fluids() {}
    }

    public static final class Blocks {
        public static final TagKey<net.minecraft.block.Block> NEEDS_WOOD_TOOL = TagKey.of("block", "forge:needs_wood_tool");
        public static final TagKey<net.minecraft.block.Block> NEEDS_NETHERITE_TOOL = TagKey.of("block", "forge:needs_netherite_tool");

        private Blocks() {}
    }

    public static final class Items {
        public static final TagKey<net.minecraft.item.Item> DUSTS_REDSTONE = TagKey.of("item", "forge:dusts/redstone");
        public static final TagKey<net.minecraft.item.Item> DUSTS_GLOWSTONE = TagKey.of("item", "forge:dusts/glowstone");
        public static final TagKey<net.minecraft.item.Item> ORES_IRON = TagKey.of("item", "forge:ores/iron");
        public static final TagKey<net.minecraft.item.Item> ORES_GOLD = TagKey.of("item", "forge:ores/gold");
        public static final TagKey<net.minecraft.item.Item> ORES_COPPER = TagKey.of("item", "forge:ores/copper");
        public static final TagKey<net.minecraft.item.Item> INGOTS_IRON = TagKey.of("item", "forge:ingots/iron");
        public static final TagKey<net.minecraft.item.Item> INGOTS_GOLD = TagKey.of("item", "forge:ingots/gold");

        private Items() {}
    }
}
