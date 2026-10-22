package ic2cextras.book.client;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.book.MultiblockDef;

/**
 * A fake world holding one {@link MultiblockDef}, for RenderBlocks (so connected textures and controller facing look
 * as in the real world). Preview coordinates: x = local x, y = local y, z = depth-1-local z, i.e. the controller faces
 * south (+z, towards the viewer of the book preview). Tile entities are never created.
 */
@SideOnly(Side.CLIENT)
public class StructureAccess implements IBlockAccess {

    /** Controller metadata/facing in preview space. */
    public static final int FACING = 3;
    private static final int FULL_BRIGHT = 15 << 20 | 15 << 4;

    private final MultiblockDef def;
    /** -1 = all layers, else only this y. */
    public int layer = -1;
    /** Show the alternative block (valve) instead of the main one on positions that allow it. */
    public boolean showAlternatives;

    public StructureAccess(MultiblockDef def) {
        this.def = def;
    }

    public MultiblockDef def() {
        return def;
    }

    /** Part at a preview position, or null outside / hidden. */
    public MultiblockDef.Part partAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= def.width || y >= def.height || z >= def.depth) return null;
        if (layer >= 0 && y != layer) return null;
        return def.partAt(x, y, def.depth - 1 - z);
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        MultiblockDef.Part p = partAt(x, y, z);
        if (p == null || p.isAir()) return Blocks.air;
        if (showAlternatives && p.altBlock != null) return p.altBlock;
        return p.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        MultiblockDef.Part p = partAt(x, y, z);
        if (p == null || p.isAir()) return 0;
        if (p.controller) return FACING;
        if (showAlternatives && p.altBlock != null) return Math.max(0, p.altMeta);
        return Math.max(0, p.meta);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return null;
    }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) {
        return FULL_BRIGHT;
    }

    @Override
    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        return 0;
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default) {
        Block b = getBlock(x, y, z);
        return b != Blocks.air && b.isOpaqueCube();
    }
}
