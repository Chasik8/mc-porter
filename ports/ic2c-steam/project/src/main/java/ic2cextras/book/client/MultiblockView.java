package ic2cextras.book.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.book.MultiblockDef;

/**
 * State and 3D rendering of one multiblock preview (1.19.2 MultiBlockComponent): drag to rotate, wheel to zoom, one
 * layer or all, controller outlined.
 */
@SideOnly(Side.CLIENT)
public class MultiblockView {

    public static final float DEFAULT_ROT_X = 28, DEFAULT_ROT_Y = -38;

    public final MultiblockDef def;
    public final StructureAccess access;
    public float rotX = DEFAULT_ROT_X, rotY = DEFAULT_ROT_Y, zoom = 1;
    private int lastX, lastY;
    public boolean dragging;

    public MultiblockView(MultiblockDef def) {
        this.def = def;
        this.access = new StructureAccess(def);
    }

    public int layer() {
        return access.layer;
    }

    /** -1 = all layers. */
    public void setLayer(int layer) {
        access.layer = layer < 0 || layer >= def.height ? -1 : layer;
    }

    /** Cycles all -> 1 -> 2 .. -> top -> all (up) or the other way round. */
    public void stepLayer(int dir) {
        int l = access.layer;
        if (dir > 0) setLayer(l < 0 ? 0 : l + 1 >= def.height ? -1 : l + 1);
        else setLayer(l < 0 ? def.height - 1 : l - 1);
    }

    public void reset() {
        rotX = DEFAULT_ROT_X;
        rotY = DEFAULT_ROT_Y;
        zoom = 1;
        access.layer = -1;
    }

    public void startDrag(int mx, int my) {
        dragging = true;
        lastX = mx;
        lastY = my;
    }

    public void drag(int mx, int my) {
        if (!dragging) return;
        rotY += (mx - lastX) * 1.5F;
        rotX = Math.max(-90, Math.min(90, rotX + (my - lastY) * 1.5F));
        lastX = mx;
        lastY = my;
    }

    public void zoom(int dir) {
        zoom = Math.max(0.4F, Math.min(4F, zoom * (dir > 0 ? 1.15F : 1 / 1.15F)));
    }

    /** Renders into the screen rectangle (GUI coordinates). */
    public void render(int vx, int vy, int vw, int vh) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = sr.getScaleFactor();
        List<int[]> positions = new ArrayList<int[]>();
        for (int y = 0; y < def.height; y++) for (int z = 0; z < def.depth; z++)
            for (int x = 0; x < def.width; x++) positions.add(new int[] { x, y, z });

        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_SCISSOR_BIT
                | GL11.GL_LINE_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(vx * f, mc.displayHeight - (vy + vh) * f, vw * f, vh * f);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        float diag = (float) Math.sqrt(def.width * def.width + def.height * def.height + def.depth * def.depth);
        float s = Math.min(vw, vh) * 0.82F / diag * zoom;
        GL11.glTranslatef(vx + vw / 2F, vy + vh / 2F, 400);
        GL11.glScalef(s, -s, s);
        GL11.glRotatef(rotX, 1, 0, 0);
        GL11.glRotatef(rotY, 0, 1, 0);
        GL11.glTranslatef(-def.width / 2F, -def.height / 2F, -def.depth / 2F);

        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        BlockDrawer.draw(access, positions);

        // controller outline, pulsing
        if (access.layer < 0 || access.layer == def.ctrlY) {
            float pulse = 0.6F + 0.4F * (float) Math.sin(Minecraft.getSystemTime() / 250.0);
            int cz = def.depth - 1 - def.ctrlZ;
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(2.0F);
            GL11.glColor4f(1.0F, 0.85F * pulse + 0.15F, 0.1F, 1);
            box(def.ctrlX - 0.02, def.ctrlY - 0.02, cz - 0.02, def.ctrlX + 1.02, def.ctrlY + 1.02, cz + 1.02);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        GL11.glPopMatrix();
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPopAttrib();
        GL11.glColor4f(1, 1, 1, 1);
    }

    static void box(double x1, double y1, double z1, double x2, double y2, double z2) {
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        double[][] c = { { x1, y1, z1 }, { x2, y1, z1 }, { x2, y1, z2 }, { x1, y1, z2 }, { x1, y2, z1 }, { x2, y2, z1 },
            { x2, y2, z2 }, { x1, y2, z2 } };
        int[][] e = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 },
            { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (int[] ed : e) {
            t.addVertex(c[ed[0]][0], c[ed[0]][1], c[ed[0]][2]);
            t.addVertex(c[ed[1]][0], c[ed[1]][1], c[ed[1]][2]);
        }
        t.draw();
    }
}
