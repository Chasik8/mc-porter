// porter:adapter (generic) - 1.19.2 BlockTags tool tags. 1.7.10 has no block tags; what they express is a block
// property there: the Forge harvest tool (mineable/<tool>) and harvest level (needs_<tier>_tool) of the block
// (Block.setHarvestLevel). These tags answer from those properties; members added by ported code also count.
package dev.porter.shim.mc;

import net.minecraft.block.Block;

public final class BlockTags {
    public static final TagKey<Block> MINEABLE_WITH_PICKAXE = tool("pickaxe");
    public static final TagKey<Block> MINEABLE_WITH_AXE = tool("axe");
    public static final TagKey<Block> MINEABLE_WITH_SHOVEL = tool("shovel");
    public static final TagKey<Block> MINEABLE_WITH_HOE = tool("hoe");
    public static final TagKey<Block> NEEDS_STONE_TOOL = level("stone", 1);
    public static final TagKey<Block> NEEDS_IRON_TOOL = level("iron", 2);
    public static final TagKey<Block> NEEDS_DIAMOND_TOOL = level("diamond", 3);

    private BlockTags() {}

    private static TagKey<Block> tool(String tool) {
        return TagKey.<Block>of("block", "minecraft:mineable/" + tool)
                .withPredicate(o -> o instanceof Block && tool.equals(((Block) o).getHarvestTool(0)));
    }

    private static TagKey<Block> level(String tier, int level) {
        return TagKey.<Block>of("block", "minecraft:needs_" + tier + "_tool")
                .withPredicate(o -> o instanceof Block && ((Block) o).getHarvestTool(0) != null && ((Block) o).getHarvestLevel(0) == level);
    }
}
