package ic2cextras.book.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.IC2CExtras;
import ic2cextras.book.MultiblockDef;
import ic2cextras.book.Structures;

/**
 * The guide book screen, after the IC2 Classic 1.19.2 wiki: a two-page spread (texture and page geometry from 1.19.2
 * WikiBoxContainer/PageComponent). The contents page lists the categories of {@link GuideData}; an entry is laid out
 * into page sides (text, recipes, multiblock preview + parts) which are shown two at a time.
 */
@SideOnly(Side.CLIENT)
public class GuiGuideBook extends GuiScreen {

    static final ResourceLocation BOOK = new ResourceLocation(IC2CExtras.MODID, "textures/gui/guide_book.png");
    static final ResourceLocation COMPONENTS = new ResourceLocation(
        IC2CExtras.MODID,
        "textures/gui/guide_components.png");

    public static final int W = 256, H = 192;
    /** Page side geometry, relative to the book corner. */
    static final int LEFT_X = 9, RIGHT_X = 131, TOP_Y = 10, SIDE_W = 114, SIDE_H = 160;
    static final int LINE = 10;
    static final int TEXT = 0x3A3A2D, HEADER = 0x1E3C78, MUTED = 0x7A7864, LINK = 0x1F4FA8;

    /** Remembered between openings. */
    private static String lastEntry;
    private static int lastSpread;

    GuideData data;
    private String entryId;
    private int spread;
    private List<PageSide> sides = new ArrayList<PageSide>();
    private final Deque<Object[]> history = new ArrayDeque<Object[]>();
    int gx, gy;

    /** Set while drawing, consumed at the end of the frame. */
    ItemStack hoverStack;
    List<String> hoverText;
    /** Side under the mouse when dragging started. */
    private PageSide dragSide;

    public GuiGuideBook() {
        this.entryId = lastEntry;
        this.spread = lastSpread;
    }

    // ------------------------------------------------------------ navigation

    public void openEntry(String id) {
        history.push(new Object[] { entryId, spread });
        show(id, 0);
    }

    public void show(String id, int spreadIndex) {
        entryId = id != null && data != null && !data.entries.containsKey(id) ? null : id;
        spread = Math.max(0, spreadIndex);
        rebuild();
        lastEntry = entryId;
        lastSpread = spread;
    }

    public void back() {
        if (history.isEmpty()) {
            if (entryId != null) show(null, 0);
            return;
        }
        Object[] h = history.pop();
        show((String) h[0], (Integer) h[1]);
    }

    public String entryId() {
        return entryId;
    }

    public int spreadCount() {
        return (sides.size() + 1) / 2;
    }

    /** First multiblock view of the shown entry (used by the screenshot hook). */
    public MultiblockView multiblockView() {
        for (PageSide s : sides) if (s instanceof Sides.Preview) return ((Sides.Preview) s).view;
        return null;
    }

    /** Spread index showing the first multiblock preview, or -1. */
    public int multiblockSpread() {
        for (int i = 0; i < sides.size(); i++) if (sides.get(i) instanceof Sides.Preview) return i / 2;
        return -1;
    }

    /** Spread index of the first recipe side, or -1. */
    public int recipeSpread() {
        for (int i = 0; i < sides.size(); i++) if (sides.get(i) instanceof Sides.Recipes) return i / 2;
        return -1;
    }

    private void rebuild() {
        if (data == null || fontRendererObj == null) return;
        GuideData.Entry entry = entryId == null ? null : data.entries.get(entryId);
        sides = entry == null ? Layout.index(this) : Layout.entry(this, entry);
        spread = Math.min(spread, Math.max(0, spreadCount() - 1));
        dragSide = null;
    }

    // ------------------------------------------------------------ screen

    @Override
    public void initGui() {
        super.initGui();
        gx = (width - W) / 2;
        gy = (height - H) / 2;
        if (data == null) data = GuideData.load();
        rebuild();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        hoverStack = null;
        hoverText = null;
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BOOK);
        drawTexturedModalRect(gx, gy, 0, 0, W, H);

        for (int i = 0; i < 2; i++) {
            PageSide s = side(i);
            if (s == null) continue;
            GL11.glColor4f(1, 1, 1, 1);
            try {
                s.draw(this, sideX(i), gy + TOP_Y, mx, my, pt);
            } catch (Exception e) {
                IC2CExtras.LOG.error("[guide] page draw failed", e);
                sides.set(spread * 2 + i, new Sides.Text(null, new ArrayList<Sides.Line>()));
            }
        }
        drawNavigation(mx, my);

        if (hoverStack != null) {
            renderToolTip(hoverStack, mx, my);
        } else if (hoverText != null) {
            drawHoveringText(hoverText, mx, my, fontRendererObj);
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawNavigation(int mx, int my) {
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BOOK);
        boolean prev = spread > 0, next = spread + 1 < spreadCount();
        // page corners, 1.19.2 PageComponent sprites
        if (prev) {
            if (in(mx, my, gx + 6, gy + 174, 12, 12)) drawTexturedModalRect(gx + 6, gy + 170, 151, 240, 16, 16);
            else drawTexturedModalRect(gx + 6, gy + 174, 154, 227, 12, 12);
        }
        if (next) {
            if (in(mx, my, gx + 238, gy + 174, 12, 12)) drawTexturedModalRect(gx + 234, gy + 170, 169, 240, 16, 16);
            else drawTexturedModalRect(gx + 238, gy + 174, 167, 227, 12, 12);
        }
        if (entryId != null) {
            boolean hover = in(mx, my, gx + 120, gy + 176, 16, 12);
            if (hover) GL11.glColor4f(0.75F, 0.75F, 0.75F, 1);
            drawTexturedModalRect(gx + 120, gy + 176, 16, 194, 16, 12);
            GL11.glColor4f(1, 1, 1, 1);
            if (hover) {
                hoverText = new ArrayList<String>();
                hoverText.add(StatCollector.translateToLocal("ic2cextras.guide.contents"));
                hoverText.add("§7" + StatCollector.translateToLocal("ic2cextras.guide.backHint"));
            }
        }
        int count = Math.max(1, sides.size());
        String l = (spread * 2 + 1) + "/" + count;
        fontRendererObj.drawString(l, gx + 67 - fontRendererObj.getStringWidth(l) / 2, gy + 178, 0x404040);
        if (spread * 2 + 1 < sides.size()) {
            String r = (spread * 2 + 2) + "/" + count;
            fontRendererObj.drawString(r, gx + 190 - fontRendererObj.getStringWidth(r) / 2, gy + 178, 0x404040);
        }
    }

    private PageSide side(int i) {
        int idx = spread * 2 + i;
        return idx < sides.size() ? sides.get(idx) : null;
    }

    int sideX(int i) {
        return gx + (i == 0 ? LEFT_X : RIGHT_X);
    }

    static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    // ------------------------------------------------------------ input

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        for (int i = 0; i < 2; i++) {
            PageSide s = side(i);
            if (s != null && s.click(this, sideX(i), gy + TOP_Y, mx, my, button)) {
                dragSide = s;
                return;
            }
        }
        if (button == 1) {
            back();
            return;
        }
        if (button != 0) return;
        if (spread > 0 && in(mx, my, gx + 6, gy + 174, 12, 12)) flip(-1);
        else if (in(mx, my, gx + 238, gy + 174, 12, 12)) flip(1);
        else if (entryId != null && in(mx, my, gx + 120, gy + 176, 16, 12)) {
            history.clear();
            show(null, 0);
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (dragSide != null) dragSide.drag(this, mx, my);
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int state) {
        if (state >= 0 && dragSide != null) {
            dragSide.release(this);
            dragSide = null;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int d = wheel > 0 ? 1 : -1;
        for (int i = 0; i < 2; i++) {
            PageSide s = side(i);
            if (s != null && s.scroll(this, sideX(i), gy + TOP_Y, mx, my, d)) return;
        }
        flip(-d);
    }

    void flip(int d) {
        int n = spread + d;
        if (n < 0 || n >= spreadCount()) return;
        spread = n;
        lastSpread = n;
        mc.getSoundHandler()
            .playSound(
                net.minecraft.client.audio.PositionedSoundRecord
                    .func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_PRIOR) flip(-1);
        else if (key == Keyboard.KEY_RIGHT || key == Keyboard.KEY_NEXT) flip(1);
        else if (key == Keyboard.KEY_BACK) back();
        else if (key == Keyboard.KEY_HOME) show(null, 0);
        else if (key == mc.gameSettings.keyBindInventory.getKeyCode()) mc.displayGuiScreen(null);
        else super.keyTyped(c, key);
    }

    // ------------------------------------------------------------ drawing helpers for the sides

    void text(String s, int x, int y, int color) {
        fontRendererObj.drawString(s, x, y, color);
    }

    void centered(String s, int x, int y, int w, int color) {
        fontRendererObj.drawString(s, x + (w - fontRendererObj.getStringWidth(s)) / 2, y, color);
    }

    int width(String s) {
        return fontRendererObj.getStringWidth(s);
    }

    List<String> wrap(String s, int w) {
        @SuppressWarnings("unchecked")
        List<String> l = fontRendererObj.listFormattedStringToWidth(s, w);
        return l;
    }

    String trim(String s, int w) {
        if (fontRendererObj.getStringWidth(s) <= w) return s;
        return fontRendererObj.trimStringToWidth(s, w - fontRendererObj.getStringWidth("...")) + "...";
    }

    /** Item icon at (x, y), size 16 * scale; sets the tooltip when hovered. */
    void item(ItemStack stack, int x, int y, float scale, int mx, int my) {
        if (stack == null) return;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        try {
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, 0, 0);
        } catch (Exception ignored) {}
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        int size = Math.round(16 * scale);
        if (in(mx, my, x, y, size, size)) hoverStack = stack;
    }

    void slot(int x, int y) {
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(COMPONENTS);
        drawTexturedModalRect(x, y, 124, 0, 18, 18);
    }

    void sprite(ResourceLocation tex, int x, int y, int u, int v, int w, int h) {
        mc.getTextureManager()
            .bindTexture(tex);
        drawTexturedModalRect(x, y, u, v, w, h);
    }

    void rect(int x1, int y1, int x2, int y2, int argb) {
        drawRect(x1, y1, x2, y2, argb);
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** Entry that documents this item (recipe pages and icons), or null. */
    String entryFor(ItemStack stack) {
        if (stack == null || data == null) return null;
        String key = Item.itemRegistry.getNameForObject(stack.getItem()) + "@" + stack.getItemDamage();
        for (GuideData.Entry e : data.entries.values()) {
            for (GuideData.Page p : e.pages) {
                if (!"recipe".equals(p.type)) continue;
                for (String spec : p.list("items")) {
                    ItemStack s = GuideData.parseItem(spec);
                    if (s == null) continue;
                    String k = Item.itemRegistry.getNameForObject(s.getItem()) + "@" + s.getItemDamage();
                    if (k.equals(key)) return e.id;
                }
            }
        }
        return null;
    }

    static MultiblockDef structure(String id) {
        for (MultiblockDef d : Structures.all()) if (d.id.equals(id) || d.id.endsWith(":" + id)) return d;
        return null;
    }
}
