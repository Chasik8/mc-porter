// porter:adapter (generic, client) - 1.19.2 com.mojang.blaze3d.platform.Window (Minecraft.getWindow()).
package dev.porter.shim.mc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

public final class Window {
    private static final Window INSTANCE = new Window();

    public static Window get(Minecraft mc) { return INSTANCE; }

    private static ScaledResolution res() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    public int getWidth() { return Minecraft.getMinecraft().displayWidth; }

    public int getHeight() { return Minecraft.getMinecraft().displayHeight; }

    public int getScreenWidth() { return getWidth(); }

    public int getScreenHeight() { return getHeight(); }

    public int getGuiScaledWidth() { return res().getScaledWidth(); }

    public int getGuiScaledHeight() { return res().getScaledHeight(); }

    public double getGuiScale() { return res().getScaleFactor(); }
}
