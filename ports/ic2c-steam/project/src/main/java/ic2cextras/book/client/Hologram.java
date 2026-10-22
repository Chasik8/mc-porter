package ic2cextras.book.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.book.MultiblockDef;

/**
 * Client-only projection of a multiblock around its controller (1.19.2 MultiBlockOverlay, simplified): missing blocks
 * are drawn translucent, wrong blocks get a red frame. Nothing is placed or sent to the server. One projection at a
 * time; it switches itself off when the controller is gone or the player changes dimension.
 */
@SideOnly(Side.CLIENT)
public final class Hologram {

    public static final Hologram INSTANCE = new Hologram();

    private MultiblockDef def;
    private int cx, cy, cz, facing, dim;

    private Hologram() {}

    public boolean active() {
        return def != null;
    }

    public void toggle(MultiblockDef d, int x, int y, int z, int f) {
        Minecraft mc = Minecraft.getMinecraft();
        if (def == d && cx == x && cy == y && cz == z) {
            def = null;
            mc.thePlayer.addChatMessage(new ChatComponentTranslation("ic2cextras.guide.hologram.off"));
            return;
        }
        def = d;
        cx = x;
        cy = y;
        cz = z;
        facing = f;
        dim = mc.theWorld.provider.dimensionId;
        mc.thePlayer.addChatMessage(new ChatComponentTranslation("ic2cextras.guide.hologram.on"));
    }

    public void clear() {
        def = null;
    }

    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent event) {
        if (def == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        if (world == null || world.provider.dimensionId != dim) {
            def = null;
            return;
        }
        if (!world.blockExists(cx, cy, cz)) return;
        MultiblockDef.Part ctrl = def.partAt(def.ctrlX, def.ctrlY, def.ctrlZ);
        if (world.getBlock(cx, cy, cz) != ctrl.block) {
            def = null;
            return;
        }
        Map<Long, MultiblockDef.Part> ghosts = new HashMap<Long, MultiblockDef.Part>();
        List<int[]> ghostPos = new ArrayList<int[]>();
        List<int[]> wrong = new ArrayList<int[]>();
        for (int y = 0; y < def.height; y++) for (int z = 0; z < def.depth; z++) for (int x = 0; x < def.width; x++) {
            MultiblockDef.Part part = def.partAt(x, y, z);
            if (part.controller) continue;
            int[] p = def.toWorld(cx, cy, cz, facing, x, y, z);
            if (!world.blockExists(p[0], p[1], p[2])) continue;
            Block b = world.getBlock(p[0], p[1], p[2]);
            int m = world.getBlockMetadata(p[0], p[1], p[2]);
            boolean air = b.isAir(world, p[0], p[1], p[2]);
            if (part.isAir()) {
                if (!air) wrong.add(p);
            } else if (!part.matches(b, m)) {
                if (air || b.isReplaceable(world, p[0], p[1], p[2])) {
                    ghosts.put(key(p[0], p[1], p[2]), part);
                    ghostPos.add(p);
                } else wrong.add(p);
            }
        }
        if (ghostPos.isEmpty() && wrong.isEmpty()) return;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        mc.entityRenderer.disableLightmap(event.partialTicks);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendColor(1, 1, 1, 0.45F);
        GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        if (!ghostPos.isEmpty()) BlockDrawer.draw(new GhostAccess(world, ghosts), ghostPos);

        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(1.0F, 0.15F, 0.15F, 0.9F);
        for (int[] p : wrong)
            MultiblockView.box(p[0] - 0.005, p[1] - 0.005, p[2] - 0.005, p[0] + 1.005, p[1] + 1.005, p[2] + 1.005);
        GL11.glColor4f(0.3F, 0.8F, 1.0F, 0.5F);
        for (int[] p : ghostPos)
            MultiblockView.box(p[0] + 0.01, p[1] + 0.01, p[2] + 0.01, p[0] + 0.99, p[1] + 0.99, p[2] + 0.99);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDepthMask(true);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1, 1, 1, 1);
        mc.entityRenderer.enableLightmap(event.partialTicks);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    /** The real world with the projected blocks filled in, so connected textures and face culling look right. */
    private static final class GhostAccess implements IBlockAccess {

        private final World world;
        private final Map<Long, MultiblockDef.Part> ghosts;

        GhostAccess(World world, Map<Long, MultiblockDef.Part> ghosts) {
            this.world = world;
            this.ghosts = ghosts;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            MultiblockDef.Part p = ghosts.get(key(x, y, z));
            return p != null ? p.block : world.getBlock(x, y, z);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            MultiblockDef.Part p = ghosts.get(key(x, y, z));
            return p != null ? Math.max(0, p.meta) : world.getBlockMetadata(x, y, z);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return ghosts.containsKey(key(x, y, z)) ? null : world.getTileEntity(x, y, z);
        }

        @Override
        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) {
            return 15 << 20 | 15 << 4;
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
            return world.getBiomeGenForCoords(x, z);
        }

        @Override
        public int getHeight() {
            return world.getHeight();
        }

        @Override
        public boolean extendedLevelsInChunkCache() {
            return false;
        }

        @Override
        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default) {
            if (ghosts.containsKey(key(x, y, z))) return getBlock(x, y, z).isOpaqueCube();
            return world.isSideSolid(x, y, z, side, _default);
        }
    }
}
