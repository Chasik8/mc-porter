// porter:adapter (generic, client) - 1.19.2 GuiComponent drawing helpers on 1.7.10 Gui/Tessellator.
package dev.porter.shim.mc.client;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IIcon;
import org.lwjgl.opengl.GL11;

public class GuiComponent {
    private static float blitOffsetStatic;
    protected int blitOffset;

    public int getBlitOffset() { return blitOffset; }

    public void setBlitOffset(int o) { this.blitOffset = o; }

    public void blit(PoseStack p, int x, int y, int u, int v, int w, int h) { blit(p, x, y, blitOffset, (float) u, (float) v, w, h, 256, 256); }

    public static void blit(PoseStack p, int x, int y, int z, float u, float v, int w, int h, int texW, int texH) {
        innerBlit(x, x + w, y, y + h, z, u / texW, (u + w) / texW, v / texH, (v + h) / texH);
    }

    public static void blit(PoseStack p, int x, int y, float u, float v, int w, int h, int texW, int texH) { blit(p, x, y, 0, u, v, w, h, texW, texH); }

    public static void blit(PoseStack p, int x, int y, int w, int h, float u, float v, int uw, int vh, int texW, int texH) {
        innerBlit(x, x + w, y, y + h, 0, u / texW, (u + uw) / texW, v / texH, (v + vh) / texH);
    }

    public static void blit(PoseStack p, int x, int y, int z, int w, int h, IIcon sprite) {
        innerBlit(x, x + w, y, y + h, z, sprite.getMinU(), sprite.getMaxU(), sprite.getMinV(), sprite.getMaxV());
    }

    static void innerBlit(int x0, int x1, int y0, int y1, int z, float u0, float u1, float v0, float v1) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x0, y1, z, u0, v1);
        t.addVertexWithUV(x1, y1, z, u1, v1);
        t.addVertexWithUV(x1, y0, z, u1, v0);
        t.addVertexWithUV(x0, y0, z, u0, v0);
        t.draw();
    }

    public static void fill(PoseStack p, int x0, int y0, int x1, int y1, int color) { net.minecraft.client.gui.Gui.drawRect(x0, y0, x1, y1, color); }

    public void fillGradient(PoseStack p, int x0, int y0, int x1, int y1, int c0, int c1) {
        float a0 = (c0 >>> 24) / 255F, r0 = (c0 >> 16 & 255) / 255F, g0 = (c0 >> 8 & 255) / 255F, b0 = (c0 & 255) / 255F;
        float a1 = (c1 >>> 24) / 255F, r1 = (c1 >> 16 & 255) / 255F, g1 = (c1 >> 8 & 255) / 255F, b1 = (c1 & 255) / 255F;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(r0, g0, b0, a0);
        t.addVertex(x1, y0, blitOffset);
        t.addVertex(x0, y0, blitOffset);
        t.setColorRGBA_F(r1, g1, b1, a1);
        t.addVertex(x0, y1, blitOffset);
        t.addVertex(x1, y1, blitOffset);
        t.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    public static void drawString(PoseStack p, Font f, String s, int x, int y, int color) { f.drawShadow(p, s, x, y, color); }

    public static void drawString(PoseStack p, Font f, IChatComponent c, int x, int y, int color) { f.drawShadow(p, c, x, y, color); }

    public static void drawString(PoseStack p, Font f, Font.FormattedCharSequence c, int x, int y, int color) { f.drawShadow(p, c, x, y, color); }

    public static void drawCenteredString(PoseStack p, Font f, String s, int x, int y, int color) { f.drawShadow(p, s, x - f.width(s) / 2, y, color); }

    public static void drawCenteredString(PoseStack p, Font f, IChatComponent c, int x, int y, int color) { f.drawShadow(p, c, x - f.width(c) / 2, y, color); }
}
