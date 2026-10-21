// porter:adapter (generic, client) - 1.19.2 Tesselator/BufferBuilder/VertexFormat over the 1.7.10 Tessellator.
package dev.porter.shim.mc.client;

import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

public final class Tesselator {
    private static final Tesselator INSTANCE = new Tesselator();
    private final BufferBuilder builder = new BufferBuilder();

    public static Tesselator getInstance() { return INSTANCE; }

    public BufferBuilder getBuilder() { return builder; }

    public void end() { builder.end(); }

    public static final class BufferBuilder {
        private double x, y, z;
        private float u, v;
        private boolean hasUv, hasColor;
        private int r = 255, g = 255, b = 255, a = 255;
        private boolean building;

        public void begin(VertexFormat.Mode mode, Object format) {
            Tessellator.instance.startDrawing(mode.gl);
            building = true;
        }

        public BufferBuilder vertex(Matrix4f m, float x, float y, float z) { return vertex((double) x, y, z); }

        public BufferBuilder vertex(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            hasUv = false;
            hasColor = false;
            return this;
        }

        public BufferBuilder uv(float u, float v) {
            this.u = u;
            this.v = v;
            hasUv = true;
            return this;
        }

        public BufferBuilder color(int r, int g, int b, int a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            hasColor = true;
            return this;
        }

        public BufferBuilder color(float r, float g, float b, float a) { return color((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255)); }

        /** 1.19.2 ARGB int (VertexConsumer.color(int)). */
        public BufferBuilder color(int argb) { return color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }

        public BufferBuilder normal(float x, float y, float z) {
            Tessellator.instance.setNormal(x, y, z);
            return this;
        }

        public BufferBuilder overlayCoords(int i) { return this; }

        public BufferBuilder uv2(int i) { return this; }

        public void endVertex() {
            Tessellator t = Tessellator.instance;
            if (hasColor) t.setColorRGBA(r, g, b, a);
            if (hasUv) t.addVertexWithUV(x, y, z, u, v);
            else t.addVertex(x, y, z);
        }

        public void end() {
            if (building) {
                Tessellator.instance.draw();
                building = false;
            }
        }
    }

    public static final class VertexFormat {
        public enum Mode {
            LINES(GL11.GL_LINES), DEBUG_LINES(GL11.GL_LINES), LINE_STRIP(GL11.GL_LINE_STRIP), TRIANGLES(GL11.GL_TRIANGLES),
            TRIANGLE_STRIP(GL11.GL_TRIANGLE_STRIP), TRIANGLE_FAN(GL11.GL_TRIANGLE_FAN), QUADS(GL11.GL_QUADS);

            final int gl;

            Mode(int gl) { this.gl = gl; }
        }
    }

    /** DefaultVertexFormat constants (formats are implicit in 1.7.10's Tessellator). */
    public static final class DefaultVertexFormat {
        public static final Object POSITION = "position";
        public static final Object POSITION_COLOR = "position_color";
        public static final Object POSITION_TEX = "position_tex";
        public static final Object POSITION_COLOR_TEX = "position_color_tex";
        public static final Object POSITION_TEX_COLOR = "position_tex_color";
        public static final Object POSITION_COLOR_NORMAL = "position_color_normal";
        public static final Object BLOCK = "block";
        public static final Object NEW_ENTITY = "new_entity";

        private DefaultVertexFormat() {}
    }

    /** GameRenderer::getXShader suppliers for RenderSystem.setShader (no shaders on 1.7.10). */
    public static final class GameRenderer {
        public static Object getPositionShader() { return null; }

        public static Object getPositionColorShader() { return null; }

        public static Object getPositionTexShader() { return null; }

        public static Object getPositionColorTexShader() { return null; }

        public static Object getPositionTexColorShader() { return null; }

        public static Object getRendertypeLinesShader() { return null; }

        private GameRenderer() {}
    }

    /** com.mojang.blaze3d.platform.Lighting */
    public static final class Lighting {
        public static void setupFor3DItems() { net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting(); }

        public static void setupForFlatItems() { net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting(); }

        public static void setupForEntityInInventory() { net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting(); }

        private Lighting() {}
    }
}
