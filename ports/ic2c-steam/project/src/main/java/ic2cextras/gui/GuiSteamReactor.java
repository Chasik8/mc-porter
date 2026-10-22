package ic2cextras.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.lwjgl.opengl.GL11;

import ic2.api.reactor.ISteamReactor;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2cextras.IC2CExtras;

/** 1.19.2-style steam reactor screen: water gauge, steam gauge, heat bar and steam output. */
public class GuiSteamReactor extends GuiContainer {

    private static final int TANK_W = 12, TANK_H = 106, TANK_Y = 18;

    private final ContainerSteamReactor container;
    private final ResourceLocation texture;
    private final boolean wide;
    private final int waterX, steamX;
    private final int waterCapacity, steamCapacity;

    public GuiSteamReactor(InventoryPlayer playerInv, TileEntityNuclearReactor reactor) {
        super(new ContainerSteamReactor(playerInv, reactor));
        container = (ContainerSteamReactor) inventorySlots;
        int size = Math.max(3, Math.min(9, container.size));
        texture = new ResourceLocation(IC2CExtras.MODID, "textures/gui/steam_reactor_6by" + size + ".png");
        wide = size >= 8;
        xSize = wide ? 194 : 176;
        ySize = 238;
        waterX = wide ? -10 : 8;
        steamX = wide ? 174 : 156;
        ISteamReactor r = (ISteamReactor) reactor;
        waterCapacity = r.getWaterTank()
            .getCapacity();
        steamCapacity = r.getSteamTank()
            .getCapacity();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(texture);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
        if (wide) drawTexturedModalRect(guiLeft - 18, guiTop, 194, 0, 22, 132);

        int maxHeat = Math.max(1, container.maxHeat());
        int heatPx = (int) Math.min(160L, (long) container.heat() * 160L / maxHeat);
        // the heat gradient sits right below the 238px tall screen in the texture
        drawTexturedModalRect(guiLeft + 8, guiTop + 130, 0, 238, heatPx, 10);

        drawTank(waterX, FluidRegistry.WATER, container.water(), waterCapacity);
        drawTank(steamX, FluidRegistry.getFluid("steam"), container.steam(), steamCapacity);
    }

    private void drawTank(int x, Fluid fluid, int amount, int capacity) {
        if (fluid == null || amount <= 0 || capacity <= 0) return;
        int height = Math.max(1, Math.min(TANK_H, amount * TANK_H / capacity));
        IIcon icon = fluid.getIcon();
        int left = guiLeft + x, bottom = guiTop + TANK_Y + TANK_H;
        if (icon != null) {
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            int color = fluid.getColor();
            GL11.glColor4f((color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, 1.0F);
            for (int drawn = 0; drawn < height; drawn += 16) {
                int h = Math.min(16, height - drawn);
                drawIconPart(icon, left, bottom - drawn - h, TANK_W, h);
            }
        } else {
            drawRect(
                left,
                bottom - height,
                left + TANK_W,
                bottom,
                fluid == FluidRegistry.WATER ? 0xFF2050D0 : 0xFFE0E0E0);
        }
        // gauge marks on top of the fluid (transparent strip right of the panel, further right on the wide textures)
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(texture);
        GL11.glEnable(GL11.GL_BLEND);
        drawTexturedModalRect(left, guiTop + TANK_Y, wide ? 216 : 176, TANK_Y, TANK_W, TANK_H);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** Draws the bottom-left w*h pixels of a 16x16 icon. */
    private void drawIconPart(IIcon icon, int x, int y, int w, int h) {
        double u0 = icon.getMinU(), u1 = icon.getInterpolatedU(w);
        double v0 = icon.getInterpolatedV(16 - h), v1 = icon.getMaxV();
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + h, zLevel, u0, v1);
        t.addVertexWithUV(x + w, y + h, zLevel, u1, v1);
        t.addVertexWithUV(x + w, y, zLevel, u1, v0);
        t.addVertexWithUV(x, y, zLevel, u0, v0);
        t.draw();
    }

    /**
     * Labels as the 1.19.2 IC2C screen draws them: centred title, "Inventory" and the steam output below the heat bar.
     */
    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        String title = StatCollector.translateToLocal("ic2cextras.gui.reactor.title");
        fontRendererObj.drawString(title, xSize / 2 - fontRendererObj.getStringWidth(title) / 2, 6, 0x404040);
        String heat = StatCollector.translateToLocal("ic2cextras.gui.reactor.heat");
        fontRendererObj.drawString(heat, 89 - fontRendererObj.getStringWidth(heat) / 2, 131, 0x404040);
        fontRendererObj.drawString(StatCollector.translateToLocal("container.inventory"), 8, ySize - 92, 0x404040);
        String out = StatCollector.translateToLocalFormatted("ic2cextras.gui.reactor.output", container.output());
        fontRendererObj.drawString(out, 169 - fontRendererObj.getStringWidth(out), 146, 0x404040);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        int rx = mouseX - guiLeft, ry = mouseY - guiTop;
        List<String> tip = new ArrayList<String>();
        if (inTank(rx, ry, waterX)) {
            tip.add(StatCollector.translateToLocal("ic2cextras.gui.reactor.water"));
            tip.add(container.water() + " / " + waterCapacity + " mB");
        } else if (inTank(rx, ry, steamX)) {
            tip.add(StatCollector.translateToLocal("ic2cextras.gui.reactor.steam"));
            tip.add(container.steam() + " / " + steamCapacity + " mB");
        } else if (rx >= 7 && rx <= 169 && ry >= 129 && ry <= 141) {
            tip.add(
                StatCollector.translateToLocalFormatted(
                    "ic2cextras.gui.reactor.heatAmount",
                    container.heat(),
                    container.maxHeat()));
        }
        if (!tip.isEmpty()) drawHoveringText(tip, mouseX, mouseY, fontRendererObj);
    }

    private static boolean inTank(int rx, int ry, int x) {
        return rx >= x && rx < x + TANK_W && ry >= TANK_Y && ry < TANK_Y + TANK_H;
    }
}
