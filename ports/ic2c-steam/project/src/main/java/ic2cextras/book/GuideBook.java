package ic2cextras.book;

import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import ic2cextras.book.client.BookClient;
import ic2cextras.ported.core.block.base.tiles.BaseTileEntity;

/**
 * The in-game guide (1.19.2 wiki backport). Registration lives here so the main mod class only needs one call per
 * phase. The pages themselves are data: {@code assets/ic2cextras/guide/guide.json} + .lang keys.
 */
public final class GuideBook {

    public static Item guideBook;

    private GuideBook() {}

    public static void preInit() {
        guideBook = new ItemGuideBook();
        GameRegistry.registerItem(guideBook, "guideBook");
    }

    public static void init(FMLInitializationEvent event) {
        if (event.getSide()
            .isClient()) BookClient.init();
    }

    /** Book + any copper ingot (IC2C registers its copper as "ingotCopper"). */
    public static void addRecipes() {
        GameRegistry.addRecipe(new ShapelessOreRecipe(new ItemStack(guideBook), Items.book, "ingotCopper"));
    }

    /** The structure whose controller is this block, or null. */
    public static MultiblockDef structureFor(Block block) {
        if (block == null) return null;
        for (MultiblockDef def : Structures.all()) {
            for (MultiblockDef.Part part : def.palette()
                .values()) {
                if (part.controller && part.block == block) return def;
            }
        }
        return null;
    }

    /** Facing (2..5) of the controller at a position: tile facing if it has one, else metadata, else south. */
    public static int facingAt(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof BaseTileEntity && ((BaseTileEntity) te).getFacing() != null) {
            int f = ((BaseTileEntity) te).getFacing()
                .get3DDataValue();
            if (f >= 2 && f <= 5) return f;
        }
        int meta = world.getBlockMetadata(x, y, z);
        return meta < 2 || meta > 5 ? 3 : meta;
    }
}
