// porter:adapter (generic, client) - 1.19.2 AbstractContainerScreen (+ Screen) API on a 1.7.10 GuiContainer:
// drawScreen -> render(PoseStack, ...), background/foreground layers -> renderBg/renderLabels, initGui -> init,
// updateScreen -> containerTick, mouse/keyboard -> 1.19.2 listeners (widgets in renderables/children first).
package dev.porter.shim.mc.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IIcon;
import org.lwjgl.input.Mouse;

public abstract class ModernContainerScreen<T extends Container> extends GuiContainer {
    protected static final PoseStack POSE = new PoseStack();
    protected final T menu;
    protected final IChatComponent title;
    protected final IChatComponent playerInventoryTitle;
    protected Font font;
    protected Minecraft minecraft;
    protected ItemRenderer itemRenderer = new ItemRenderer();
    public final List<Widget> renderables = new ArrayList<>();
    protected final List<GuiEventListener> children = new ArrayList<>();
    protected Slot hoveredSlot;
    protected int blitOffset;
    private int lastMx, lastMy;
    protected boolean dragSplitting;

    @SuppressWarnings("unchecked")
    protected ModernContainerScreen(Container menu, InventoryPlayer inv, IChatComponent title) {
        super(menu);
        this.menu = (T) menu;
        this.title = title == null ? new ChatComponentText("") : title;
        this.playerInventoryTitle = new ChatComponentTranslation(inv == null ? "container.inventory" : inv.getInventoryName());
        this.minecraft = Minecraft.getMinecraft();
        this.font = Font.get();
    }

    // ------------------------------------------------------------------ 1.19.2 API

    protected void init() {}

    public void render(PoseStack p, int mx, int my, float pt) {
        super.drawScreen(mx, my, pt);
        for (Widget w : new ArrayList<>(renderables)) w.render(p, mx, my, pt);
    }

    protected abstract void renderBg(PoseStack p, float pt, int mx, int my);

    protected void renderLabels(PoseStack p, int mx, int my) {
        font.draw(p, title, 8, 6, 0x404040);
        font.draw(p, playerInventoryTitle, 8, ySize - 96 + 2, 0x404040);
    }

    public void containerTick() {}

    public void removed() {}

    public void onClose() { minecraft.thePlayer.closeScreen(); }

    public T getMenu() { return menu; }

    public Minecraft getMinecraft() { return minecraft; }

    public IChatComponent getTitle() { return title; }

    public int getGuiLeft() { return guiLeft; }

    public int getGuiTop() { return guiTop; }

    public int getXSize() { return xSize; }

    public int getYSize() { return ySize; }

    public Slot getSlotUnderMouse() { return hoveredSlot; }

    public List<? extends GuiEventListener> children() { return children; }

    public <W extends GuiEventListener & Widget> W addRenderableWidget(W w) {
        renderables.add(w);
        children.add(w);
        return w;
    }

    public <W extends Widget> W addRenderableOnly(W w) {
        renderables.add(w);
        return w;
    }

    public <W extends GuiEventListener> W addWidget(W w) {
        children.add(w);
        return w;
    }

    public void removeWidget(Object w) {
        renderables.remove(w);
        children.remove(w);
    }

    public void clearWidgets() {
        renderables.clear();
        children.clear();
    }

    public void renderBackground(PoseStack p) { drawDefaultBackground(); }

    public void renderTooltip(PoseStack p, int mx, int my) {
        if (hoveredSlot != null && hoveredSlot.getHasStack() && minecraft.thePlayer.inventory.getItemStack() == null) {
            renderToolTip(hoveredSlot.getStack(), mx, my);
        }
    }

    public void renderTooltip(PoseStack p, List<?> lines, int mx, int my) {
        List<String> s = new ArrayList<>();
        for (Object o : lines) s.add(o instanceof IChatComponent ? ((IChatComponent) o).getFormattedText() : String.valueOf(o));
        drawHoveringText(s, mx, my, fontRendererObj);
    }

    public void renderComponentTooltip(PoseStack p, List<IChatComponent> lines, int mx, int my) { renderTooltip(p, lines, mx, my); }

    public void renderTooltip(PoseStack p, IChatComponent line, int mx, int my) {
        List<IChatComponent> l = new ArrayList<>();
        l.add(line);
        renderTooltip(p, l, mx, my);
    }

    @SuppressWarnings("unchecked")
    public List<IChatComponent> getTooltipFromItem(ItemStack s) {
        List<IChatComponent> out = new ArrayList<>();
        for (Object o : s.getTooltip(minecraft.thePlayer, minecraft.gameSettings.advancedItemTooltips)) out.add(new ChatComponentText(String.valueOf(o)));
        return out;
    }

    protected boolean hasClickedOutside(double mx, double my, int left, int top, int button) {
        return mx < left || my < top || mx >= left + xSize || my >= top + ySize;
    }

    public boolean isMouseOver(double mx, double my) { return true; }

    public int getBlitOffset() { return blitOffset; }

    public void setBlitOffset(int o) {
        this.blitOffset = o;
        this.zLevel = o;
    }

    public void blit(PoseStack p, int x, int y, int u, int v, int w, int h) { drawTexturedModalRect(x, y, u, v, w, h); }

    public static void blit(PoseStack p, int x, int y, int z, int w, int h, IIcon sprite) { GuiComponent.blit(p, x, y, z, w, h, sprite); }

    public static void blit(PoseStack p, int x, int y, float u, float v, int w, int h, int texW, int texH) { GuiComponent.blit(p, x, y, u, v, w, h, texW, texH); }

    public static void fill(PoseStack p, int x0, int y0, int x1, int y1, int color) { GuiComponent.fill(p, x0, y0, x1, y1, color); }

    public static boolean hasShiftDown() { return isShiftKeyDown(); }

    public static boolean hasControlDown() { return isCtrlKeyDown(); }

    public static boolean hasAltDown() { return org.lwjgl.input.Keyboard.isKeyDown(56) || org.lwjgl.input.Keyboard.isKeyDown(184); }

    // ------------------------------------------------------------------ input (1.19.2 listeners)

    public boolean mouseClicked(double mx, double my, int button) {
        for (GuiEventListener c : new ArrayList<>(children)) if (c.mouseClicked(mx, my, button)) return true;
        super.mouseClicked((int) mx, (int) my, button);
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        for (GuiEventListener c : new ArrayList<>(children)) c.mouseReleased(mx, my, button);
        super.mouseMovedOrUp((int) mx, (int) my, button);
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        for (GuiEventListener c : new ArrayList<>(children)) if (c.mouseDragged(mx, my, button, dx, dy)) return true;
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        for (GuiEventListener c : new ArrayList<>(children)) if (c.mouseScrolled(mx, my, delta)) return true;
        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        for (GuiEventListener c : new ArrayList<>(children)) if (c.keyPressed(key, scan, mods)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mods) {
        for (GuiEventListener c : new ArrayList<>(children)) if (c.charTyped(ch, mods)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 1.7.10 -> 1.19.2 bridges

    @Override
    public void initGui() {
        super.initGui();
        this.font = Font.get();
        this.minecraft = mc;
        clearWidgets();
        init();
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        lastMx = mx;
        lastMy = my;
        hoveredSlot = null;
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            int sx = guiLeft + s.xDisplayPosition, sy = guiTop + s.yDisplayPosition;
            if (mx >= sx - 1 && mx < sx + 17 && my >= sy - 1 && my < sy + 17) hoveredSlot = s;
        }
        render(POSE, mx, my, pt);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) { renderBg(POSE, pt, mx, my); }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) { renderLabels(POSE, mx, my); }

    @Override
    public void updateScreen() {
        super.updateScreen();
        containerTick();
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) { mouseClicked((double) mx, (double) my, button); }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which >= 0) mouseReleased((double) mx, (double) my, which);
        else super.mouseMovedOrUp(mx, my, which);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (!mouseDragged(mx, my, button, mx - lastMx, my - lastMy)) super.mouseClickMove(mx, my, button, time);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) mouseScrolled(lastMx, lastMy, d > 0 ? 1 : -1);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (keyPressed(key, 0, 0) || charTyped(c, 0)) return;
        super.keyTyped(c, key);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        removed();
    }

    protected void renderTooltip(PoseStack p, ItemStack s, int mx, int my) { renderToolTip(s, mx, my); }

    public void renderComponentHoverEffect(PoseStack p, net.minecraft.util.ChatStyle style, int mx, int my) {}

    /** AbstractContainerScreen.isHovering(x, y, w, h, mouseX, mouseY) (relative to the GUI origin). */
    protected boolean isHovering(int x, int y, int w, int h, double mx, double my) {
        mx -= guiLeft;
        my -= guiTop;
        return mx >= x - 1 && mx < x + w + 1 && my >= y - 1 && my < y + h + 1;
    }

    /** 1.19.2 ItemRenderer subset used by GUIs. */
    public static class ItemRenderer {
        public float blitOffset;

        public void renderAndDecorateItem(net.minecraft.entity.EntityLivingBase e, ItemStack s, int x, int y, int seed) { renderAndDecorateItem(s, x, y); }

        public void renderGuiItem(ItemStack s, int x, int y) { renderAndDecorateItem(s, x, y); }

        public void renderAndDecorateItem(ItemStack s, int x, int y) {
            Minecraft mc = Minecraft.getMinecraft();
            float z = itemRender.zLevel;
            itemRender.zLevel = blitOffset;
            itemRender.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), s, x, y);
            itemRender.zLevel = z;
        }

        public void renderGuiItemDecorations(Font f, ItemStack s, int x, int y) { renderGuiItemDecorations(f, s, x, y, null); }

        public void renderGuiItemDecorations(Font f, ItemStack s, int x, int y, String text) {
            Minecraft mc = Minecraft.getMinecraft();
            itemRender.renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), s, x, y, text);
        }
    }
}
