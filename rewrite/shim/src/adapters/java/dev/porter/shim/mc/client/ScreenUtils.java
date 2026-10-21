// porter:adapter (generic, client) - Forge 1.19.2 ScreenUtils.blitWithBorder on GuiComponent.blit.
package dev.porter.shim.mc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

public final class ScreenUtils {
    private ScreenUtils() {}

    public static void blitWithBorder(PoseStack p, ResourceLocation tex, int x, int y, int u, int v, int w, int h, int texW, int texH,
            int top, int bottom, int left, int right, float zLevel) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        blitWithBorder(p, x, y, u, v, w, h, texW, texH, top, bottom, left, right, zLevel);
    }

    public static void blitWithBorder(PoseStack p, int x, int y, int u, int v, int w, int h, int texW, int texH,
            int top, int bottom, int left, int right, float zLevel) {
        int fillW = texW - left - right, fillH = texH - top - bottom, canW = w - left - right, canH = h - top - bottom;
        int z = (int) zLevel;
        GuiComponent.blit(p, x, y, z, u, v, left, top, 256, 256);
        GuiComponent.blit(p, x + left + canW, y, z, u + left + fillW, v, right, top, 256, 256);
        GuiComponent.blit(p, x, y + top + canH, z, u, v + top + fillH, left, bottom, 256, 256);
        GuiComponent.blit(p, x + left + canW, y + top + canH, z, u + left + fillW, v + top + fillH, right, bottom, 256, 256);
        for (int i = 0; i < canW; i += Math.max(1, fillW)) {
            int dw = Math.min(fillW, canW - i);
            GuiComponent.blit(p, x + left + i, y, z, u + left, v, dw, top, 256, 256);
            GuiComponent.blit(p, x + left + i, y + top + canH, z, u + left, v + top + fillH, dw, bottom, 256, 256);
            for (int j = 0; j < canH; j += Math.max(1, fillH)) {
                GuiComponent.blit(p, x + left + i, y + top + j, z, u + left, v + top, dw, Math.min(fillH, canH - j), 256, 256);
            }
        }
        for (int j = 0; j < canH; j += Math.max(1, fillH)) {
            int dh = Math.min(fillH, canH - j);
            GuiComponent.blit(p, x, y + top + j, z, u, v + top, left, dh, 256, 256);
            GuiComponent.blit(p, x + left + canW, y + top + j, z, u + left + fillW, v + top, right, dh, 256, 256);
        }
    }
}
