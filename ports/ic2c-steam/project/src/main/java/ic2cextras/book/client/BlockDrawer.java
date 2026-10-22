package ic2cextras.book.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.IC2CExtras;

/**
 * Draws blocks of an {@link IBlockAccess} with RenderBlocks at their coordinates. Blocks that only have a TESR
 * (render type -1) or that throw while rendering get a fallback: the inventory cube, or the item icon as a flat quad.
 * Tile entity renderers are never called.
 */
@SideOnly(Side.CLIENT)
public final class BlockDrawer {

    private static final Set<Block> BROKEN = Collections.synchronizedSet(new HashSet<Block>());

    private BlockDrawer() {}

    /** Blocks atlas must be bound. */
    public static void draw(IBlockAccess access, List<int[]> positions) {
        RenderBlocks rb = new RenderBlocks(access);
        Tessellator t = Tessellator.instance;
        List<int[]> fallback = new ArrayList<int[]>();
        t.startDrawingQuads();
        for (int[] p : positions) {
            Block b = access.getBlock(p[0], p[1], p[2]);
            if (b == null || b == Blocks.air) continue;
            if (b.getRenderType() == -1 || BROKEN.contains(b)) {
                fallback.add(p);
                continue;
            }
            try {
                rb.renderBlockByRenderType(b, p[0], p[1], p[2]);
            } catch (Throwable e) {
                if (BROKEN.add(b)) IC2CExtras.LOG.warn("[guide] cannot render {} in a preview, using its icon", b, e);
                fallback.add(p);
            }
        }
        t.draw();
        for (int[] p : fallback)
            drawFallback(rb, access.getBlock(p[0], p[1], p[2]), access.getBlockMetadata(p[0], p[1], p[2]), p);
    }

    private static void drawFallback(RenderBlocks rb, Block b, int meta, int[] p) {
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        GL11.glTranslatef(p[0] + 0.5F, p[1] + 0.5F, p[2] + 0.5F);
        try {
            if (RenderBlocks.renderItemIn3d(b.getRenderType()) && !BROKEN.contains(b)) {
                rb.renderBlockAsItem(b, meta, 1.0F);
            } else {
                drawIcon(mc, b, meta);
            }
        } catch (Throwable e) {
            BROKEN.add(b);
        }
        GL11.glPopMatrix();
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
    }

    /** The item icon on a vertical quad through the block centre, facing +z (and visible from behind). */
    private static void drawIcon(Minecraft mc, Block b, int meta) {
        Item item = Item.getItemFromBlock(b);
        if (item == null) return;
        ItemStack stack = new ItemStack(item, 1, meta);
        IIcon icon = stack.getIconIndex();
        if (icon == null) return;
        mc.getTextureManager()
            .bindTexture(
                mc.getTextureManager()
                    .getResourceLocation(stack.getItemSpriteNumber()));
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorOpaque_F(1, 1, 1);
        t.addVertexWithUV(-0.5, -0.5, 0, icon.getMinU(), icon.getMaxV());
        t.addVertexWithUV(0.5, -0.5, 0, icon.getMaxU(), icon.getMaxV());
        t.addVertexWithUV(0.5, 0.5, 0, icon.getMaxU(), icon.getMinV());
        t.addVertexWithUV(-0.5, 0.5, 0, icon.getMinU(), icon.getMinV());
        t.draw();
    }
}
