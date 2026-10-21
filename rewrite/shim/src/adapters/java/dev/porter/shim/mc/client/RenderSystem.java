// porter:adapter (generic, client) - 1.19.2 RenderSystem on 1.7.10 GL11 / TextureManager.
package dev.porter.shim.mc.client;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

public final class RenderSystem {
    private static final PoseStack MODEL_VIEW = new PoseStack();
    private static final float[] COLOR = {1F, 1F, 1F, 1F};

    private RenderSystem() {}

    /** shaders do not exist on 1.7.10: fixed function. */
    public static void setShader(Supplier<?> shader) {}

    public static void setShaderTexture(int unit, ResourceLocation tex) { Minecraft.getMinecraft().getTextureManager().bindTexture(tex); }

    public static void setShaderTexture(int unit, int glId) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId); }

    public static void setShaderColor(float r, float g, float b, float a) {
        COLOR[0] = r;
        COLOR[1] = g;
        COLOR[2] = b;
        COLOR[3] = a;
        GL11.glColor4f(r, g, b, a);
    }

    public static float[] getShaderColor() { return COLOR.clone(); }

    public static void enableBlend() { GL11.glEnable(GL11.GL_BLEND); }

    public static void disableBlend() { GL11.glDisable(GL11.GL_BLEND); }

    public static void defaultBlendFunc() { OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO); }

    public static void blendFunc(int src, int dst) { GL11.glBlendFunc(src, dst); }

    public static void enableDepthTest() { GL11.glEnable(GL11.GL_DEPTH_TEST); }

    public static void disableDepthTest() { GL11.glDisable(GL11.GL_DEPTH_TEST); }

    public static void depthMask(boolean b) { GL11.glDepthMask(b); }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) { GL11.glColorMask(r, g, b, a); }

    public static void enableTexture() { GL11.glEnable(GL11.GL_TEXTURE_2D); }

    public static void disableTexture() { GL11.glDisable(GL11.GL_TEXTURE_2D); }

    public static void enableCull() { GL11.glEnable(GL11.GL_CULL_FACE); }

    public static void disableCull() { GL11.glDisable(GL11.GL_CULL_FACE); }

    public static void lineWidth(float w) { GL11.glLineWidth(w); }

    public static void enableScissor(int x, int y, int w, int h) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, w, h);
    }

    public static void disableScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    public static PoseStack getModelViewStack() { return MODEL_VIEW; }

    public static void applyModelViewMatrix() {}

    public static void enableRescaleNormal() { GL11.glEnable(32826); }
}
