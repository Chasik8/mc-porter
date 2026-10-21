// porter:adapter (generic, client) - 1.19.2 AbstractWidget / Widget / GuiEventListener. The 1.19.2-API screens
// (ModernContainerScreen, ModernScreen) render and dispatch input to them; no 1.7.10 GuiButton involved.
package dev.porter.shim.mc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

public abstract class AbstractWidget extends GuiComponent implements Widget, GuiEventListener {
    public static final ResourceLocation WIDGETS_LOCATION = new ResourceLocation("textures/gui/widgets.png");
    public int x, y;
    protected int width, height;
    private IChatComponent message;
    protected boolean isHovered;
    public boolean active = true;
    public boolean visible = true;
    protected float alpha = 1F;
    private boolean focused;
    protected int packedFGColor = -1;

    public AbstractWidget(int x, int y, int width, int height, IChatComponent message) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.message = message;
    }

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    public void setWidth(int w) { this.width = w; }

    public void setHeight(int h) { this.height = h; }

    public IChatComponent getMessage() { return message; }

    public void setMessage(IChatComponent m) { this.message = m; }

    public boolean isHoveredOrFocused() { return isHovered || focused; }

    public boolean isFocused() { return focused; }

    public void setFocused(boolean f) { this.focused = f; }

    public boolean isActive() { return visible && active; }

    public void setAlpha(float a) { this.alpha = a; }

    public int getFGColor() { return packedFGColor != -1 ? packedFGColor : active ? 0xFFFFFF : 0xA0A0A0; }

    public void setFGColor(int c) { this.packedFGColor = c; }

    protected int getYImage(boolean hovered) { return !active ? 0 : hovered ? 2 : 1; }

    @Override
    public void render(PoseStack p, int mx, int my, float pt) {
        if (!visible) return;
        isHovered = mx >= x && my >= y && mx < x + width && my < y + height;
        renderButton(p, mx, my, pt);
    }

    /** vanilla button look (1.7.10 widgets.png layout). */
    public void renderButton(PoseStack p, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(WIDGETS_LOCATION);
        GL11.glColor4f(1F, 1F, 1F, alpha);
        int k = getYImage(isHoveredOrFocused());
        GL11.glEnable(GL11.GL_BLEND);
        blit(p, x, y, 0, 46 + k * 20, width / 2, height);
        blit(p, x + width / 2, y, 200 - width / 2, 46 + k * 20, width / 2, height);
        renderBg(p, mc, mx, my);
        if (message != null) drawCenteredString(p, Font.get(), message, x + width / 2, y + (height - 8) / 2, getFGColor());
    }

    protected void renderBg(PoseStack p, Minecraft mc, int mx, int my) {}

    public void renderToolTip(PoseStack p, int mx, int my) {}

    public void onClick(double mx, double my) {}

    public void onRelease(double mx, double my) {}

    protected void onDrag(double mx, double my, double dx, double dy) {}

    protected boolean isValidClickButton(int b) { return b == 0; }

    protected boolean clicked(double mx, double my) { return active && visible && isMouseOver(mx, my); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (active && visible && isValidClickButton(button) && clicked(mx, my)) {
            playDownSound(Minecraft.getMinecraft());
            onClick(mx, my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (isValidClickButton(button)) {
            onRelease(mx, my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (isValidClickButton(button)) {
            onDrag(mx, my, dx, dy);
            return true;
        }
        return false;
    }

    @Override
    public boolean isMouseOver(double mx, double my) { return active && visible && mx >= x && my >= y && mx < x + width && my < y + height; }

    public void playDownSound(Minecraft mc) {
        mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }
}
