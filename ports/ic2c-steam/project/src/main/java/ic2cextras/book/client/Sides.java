package ic2cextras.book.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.book.MultiblockDef;

/** The page side types. */
@SideOnly(Side.CLIENT)
final class Sides {

    private Sides() {}

    static final int HEADER_LINE = 0, TEXT_LINE = 1, BLANK_LINE = 2;

    static final class Line {

        final String text;
        final int kind;

        Line(String text, int kind) {
            this.text = text;
            this.kind = kind;
        }
    }

    /** Entry title block on top of a side. */
    static final class Title {

        final ItemStack icon;
        final List<String> lines;

        Title(ItemStack icon, List<String> lines) {
            this.icon = icon;
            this.lines = lines;
        }

        int height() {
            return Math.max(16, lines.size() * 9) + 5;
        }

        void draw(GuiGuideBook gui, int x, int y, int mx, int my) {
            int tx = icon != null ? x + 19 : x;
            int ty = y + (lines.size() == 1 ? 4 : 0);
            if (icon != null) gui.item(icon, x, y, 1, mx, my);
            for (String l : lines) {
                gui.text(l, tx, ty, GuiGuideBook.HEADER);
                ty += 9;
            }
            gui.rect(x, y + height() - 3, x + GuiGuideBook.SIDE_W, y + height() - 2, 0x60605A3C);
        }
    }

    /** Wrapped text with optional title. */
    static class Text extends PageSide {

        final Title title;
        final List<Line> lines;

        Text(Title title, List<Line> lines) {
            this.title = title;
            this.lines = lines;
        }

        @Override
        public void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt) {
            int ty = y;
            if (title != null) {
                title.draw(gui, x, y, mx, my);
                ty += title.height();
            }
            for (Line l : lines) {
                if (l.kind == HEADER_LINE) gui.text(l.text, x, ty, GuiGuideBook.HEADER);
                else if (l.kind == TEXT_LINE) gui.text(l.text, x, ty, GuiGuideBook.TEXT);
                ty += GuiGuideBook.LINE;
            }
        }
    }

    /** Contents: rows of categories and entries. */
    static final class Index extends PageSide {

        static final int ROW = 14;
        /** Either a GuideData.Category (header) or a GuideData.Entry. */
        final List<Object> rows;
        final Title title;

        Index(Title title, List<Object> rows) {
            this.title = title;
            this.rows = rows;
        }

        @Override
        public void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt) {
            int ty = y;
            if (title != null) {
                title.draw(gui, x, y, mx, my);
                ty += title.height();
            }
            for (Object o : rows) {
                if (o instanceof GuideData.Category) {
                    GuideData.Category c = (GuideData.Category) o;
                    gui.text("§l" + gui.trim(c.title(), GuiGuideBook.SIDE_W), x, ty + 4, GuiGuideBook.HEADER);
                } else {
                    GuideData.Entry e = (GuideData.Entry) o;
                    boolean hover = GuiGuideBook.in(mx, my, x, ty, GuiGuideBook.SIDE_W, ROW);
                    if (hover) gui.rect(x - 1, ty, x + GuiGuideBook.SIDE_W + 1, ty + ROW - 1, 0x30406090);
                    ItemStack icon = e.iconStack();
                    if (icon != null) gui.item(icon, x + 2, ty + 1, 0.75F, -1000, -1000);
                    gui.text(
                        gui.trim(e.title(), GuiGuideBook.SIDE_W - 18),
                        x + 17,
                        ty + 3,
                        hover ? GuiGuideBook.LINK : GuiGuideBook.TEXT);
                }
                ty += ROW;
            }
        }

        @Override
        public boolean click(GuiGuideBook gui, int x, int y, int mx, int my, int button) {
            if (button != 0) return false;
            int ty = y + (title != null ? title.height() : 0);
            for (Object o : rows) {
                if (o instanceof GuideData.Entry && GuiGuideBook.in(mx, my, x, ty, GuiGuideBook.SIDE_W, ROW)) {
                    gui.openEntry(((GuideData.Entry) o).id);
                    return true;
                }
                ty += ROW;
            }
            return false;
        }
    }

    /** Up to two crafting recipes. */
    static final class Recipes extends PageSide {

        static final int BLOCK = 78;
        final Title title;
        final List<RecipeLookup.View> views;

        Recipes(Title title, List<RecipeLookup.View> views) {
            this.title = title;
            this.views = views;
        }

        @Override
        public void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt) {
            int ty = y;
            if (title != null) {
                title.draw(gui, x, y, mx, my);
                ty += title.height();
            }
            long tick = Minecraft.getSystemTime() / 1000;
            for (RecipeLookup.View v : views) {
                String head = v.output.getDisplayName();
                if (v.output.stackSize > 1) head = v.output.stackSize + " x " + head;
                gui.text(gui.trim(head, GuiGuideBook.SIDE_W), x, ty, GuiGuideBook.HEADER);
                String kind = StatCollector.translateToLocal(
                    v.smelting ? "ic2cextras.guide.recipe.smelting"
                        : v.shapeless ? "ic2cextras.guide.recipe.shapeless" : "ic2cextras.guide.recipe.shaped");
                gui.text(kind, x, ty + 9, GuiGuideBook.MUTED);
                int gx = x + 2, gy = ty + 20;
                for (int i = 0; i < 9; i++) if (!v.smelting || i == 4) gui.slot(gx + i % 3 * 18, gy + i / 3 * 18);
                if (v.smelting)
                    gui.item(new ItemStack(net.minecraft.init.Blocks.furnace), gx + 19, gy + 37, 1, -1000, -1000);
                gui.sprite(GuiGuideBook.COMPONENTS, gx + 58, gy + 21, 124, 18, 16, 12);
                gui.slot(gx + 80, gy + 18);
                for (int i = 0; i < 9; i++) {
                    List<ItemStack> alts = v.grid.get(i);
                    if (alts == null) continue;
                    ItemStack s = alts.get((int) (tick % alts.size()));
                    gui.item(s, gx + 1 + i % 3 * 18, gy + 1 + i / 3 * 18, 1, mx, my);
                }
                gui.item(v.output, gx + 81, gy + 19, 1, mx, my);
                ty += BLOCK;
            }
        }

        @Override
        public boolean click(GuiGuideBook gui, int x, int y, int mx, int my, int button) {
            // the hovered stack of the last frame: open its entry if it has one
            if (button != 0 || gui.hoverStack == null) return false;
            String id = gui.entryFor(gui.hoverStack);
            if (id == null || id.equals(gui.entryId())) return false;
            gui.openEntry(id);
            return true;
        }
    }

    /** Left side of a multiblock spread: 3D view and controls. */
    static final class Preview extends PageSide {

        static final int VIEW_Y = 12, VIEW_H = 126, CTRL_Y = 142;
        final MultiblockView view;
        final String heading;

        Preview(MultiblockView view, String heading) {
            this.view = view;
            this.heading = heading;
        }

        @Override
        public void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt) {
            MultiblockDef def = view.def;
            String dims = def.width + "×" + def.height + "×" + def.depth;
            gui.text("§l" + gui.trim(heading, GuiGuideBook.SIDE_W - gui.width(dims) - 6), x, y, GuiGuideBook.HEADER);
            gui.text(dims, x + GuiGuideBook.SIDE_W - gui.width(dims), y, GuiGuideBook.MUTED);
            int vy = y + VIEW_Y;
            gui.rect(x - 1, vy - 1, x + GuiGuideBook.SIDE_W + 1, vy + VIEW_H + 1, 0x50605A3C);
            gui.rect(x, vy, x + GuiGuideBook.SIDE_W, vy + VIEW_H, 0x20FFFFFF);
            view.render(x, vy, GuiGuideBook.SIDE_W, VIEW_H);

            int cy = y + CTRL_Y;
            // layer down / up (1.19.2 wiki arrows), label, reset, valve toggle
            button(gui, x, cy, 12, 16, 50, 192, mx, my);
            button(gui, x + 14, cy, 12, 16, 34, 192, mx, my);
            String label = view.layer() < 0 ? StatCollector.translateToLocal("ic2cextras.guide.layer.all")
                : StatCollector.translateToLocalFormatted("ic2cextras.guide.layer.one", view.layer() + 1, def.height);
            gui.text(gui.trim(label, 50), x + 29, cy + 4, GuiGuideBook.TEXT);
            button(gui, x + 98, cy, 16, 16, 117, 219, mx, my);
            boolean alts = hasAlternatives(def);
            if (alts) {
                if (view.access.showAlternatives) gui.rect(x + 79, cy - 1, x + 97, cy + 17, 0x604080C0);
                ItemStack valve = altStack(def);
                gui.item(valve, x + 80, cy, 1, -1000, -1000);
            }
            if (GuiGuideBook.in(mx, my, x, cy, 12, 16)) tip(gui, "ic2cextras.guide.layer.down");
            else if (GuiGuideBook.in(mx, my, x + 14, cy, 12, 16)) tip(gui, "ic2cextras.guide.layer.up");
            else if (GuiGuideBook.in(mx, my, x + 98, cy, 16, 16)) tip(gui, "ic2cextras.guide.reset");
            else if (alts && GuiGuideBook.in(mx, my, x + 80, cy, 16, 16)) tip(gui, "ic2cextras.guide.showValves");
            else if (GuiGuideBook.in(mx, my, x, vy, GuiGuideBook.SIDE_W, VIEW_H)) tip(gui, "ic2cextras.guide.viewHint");
        }

        private static void tip(GuiGuideBook gui, String key) {
            gui.hoverText = new ArrayList<String>();
            for (String s : StatCollector.translateToLocal(key)
                .split("\\\\n")) gui.hoverText.add(s);
        }

        private static void button(GuiGuideBook gui, int x, int y, int w, int h, int u, int v, int mx, int my) {
            if (GuiGuideBook.in(mx, my, x, y, w, h)) org.lwjgl.opengl.GL11.glColor4f(0.7F, 0.85F, 1, 1);
            gui.sprite(GuiGuideBook.BOOK, x, y, u, v, w, h);
            org.lwjgl.opengl.GL11.glColor4f(1, 1, 1, 1);
        }

        @Override
        public boolean click(GuiGuideBook gui, int x, int y, int mx, int my, int button) {
            int cy = y + CTRL_Y;
            if (button == 0) {
                if (GuiGuideBook.in(mx, my, x, cy, 12, 16)) view.stepLayer(-1);
                else if (GuiGuideBook.in(mx, my, x + 14, cy, 12, 16)) view.stepLayer(1);
                else if (GuiGuideBook.in(mx, my, x + 98, cy, 16, 16)) view.reset();
                else if (hasAlternatives(view.def) && GuiGuideBook.in(mx, my, x + 80, cy, 16, 16))
                    view.access.showAlternatives = !view.access.showAlternatives;
                else if (GuiGuideBook.in(mx, my, x, y + VIEW_Y, GuiGuideBook.SIDE_W, VIEW_H)) view.startDrag(mx, my);
                else return false;
                return true;
            }
            return false;
        }

        @Override
        public void drag(GuiGuideBook gui, int mx, int my) {
            view.drag(mx, my);
        }

        @Override
        public void release(GuiGuideBook gui) {
            view.dragging = false;
        }

        @Override
        public boolean scroll(GuiGuideBook gui, int x, int y, int mx, int my, int dir) {
            if (!GuiGuideBook.in(mx, my, x, y + VIEW_Y, GuiGuideBook.SIDE_W, VIEW_H)) return false;
            view.zoom(dir);
            return true;
        }
    }

    static boolean hasAlternatives(MultiblockDef def) {
        return altStack(def) != null;
    }

    static ItemStack altStack(MultiblockDef def) {
        for (MultiblockDef.Part p : def.palette()
            .values()) if (p.altBlock != null) return p.altDisplayStack();
        return null;
    }

    /** Right side of a multiblock spread: parts with counts and the map of the selected layer. */
    static final class Parts extends PageSide {

        final MultiblockView view;

        Parts(MultiblockView view) {
            this.view = view;
        }

        /** A merged row: same block + meta. */
        private static final class Row {

            ItemStack stack;
            int count;
            int altCount;
            ItemStack alt;
            boolean controller;
            boolean air;
        }

        private List<Row> rows() {
            MultiblockDef def = view.def;
            int layer = view.layer();
            Map<String, Row> rows = new LinkedHashMap<String, Row>();
            for (int y = 0; y < def.height; y++) {
                if (layer >= 0 && y != layer) continue;
                for (int z = 0; z < def.depth; z++) for (int x = 0; x < def.width; x++) {
                    MultiblockDef.Part p = def.partAt(x, y, z);
                    String key = p.isAir() ? "air"
                        : p.controller ? "ctrl"
                            : net.minecraft.block.Block.blockRegistry.getNameForObject(p.block) + "@"
                                + Math.max(0, p.meta);
                    Row r = rows.get(key);
                    if (r == null) {
                        r = new Row();
                        r.stack = p.displayStack();
                        r.controller = p.controller;
                        r.air = p.isAir();
                        rows.put(key, r);
                    }
                    r.count++;
                    if (p.altBlock != null) {
                        r.altCount++;
                        r.alt = p.altDisplayStack();
                    }
                }
            }
            List<Row> out = new ArrayList<Row>(rows.values());
            // controller first, air last
            List<Row> sorted = new ArrayList<Row>();
            for (Row r : out) if (r.controller) sorted.add(r);
            for (Row r : out) if (!r.controller && !r.air) sorted.add(r);
            for (Row r : out) if (r.air) sorted.add(r);
            return sorted;
        }

        @Override
        public void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt) {
            MultiblockDef def = view.def;
            int layer = view.layer();
            String head = layer < 0 ? StatCollector.translateToLocal("ic2cextras.guide.parts")
                : StatCollector.translateToLocalFormatted("ic2cextras.guide.partsLayer", layer + 1);
            gui.text("§l" + gui.trim(head, GuiGuideBook.SIDE_W), x, y, GuiGuideBook.HEADER);
            int ty = y + 11;
            for (Row r : rows()) {
                if (r.controller) gui.rect(x - 1, ty - 1, x + GuiGuideBook.SIDE_W + 1, ty + 18, 0x40E0B020);
                if (r.air) gui.rect(x + 1, ty + 1, x + 15, ty + 15, 0x30605A3C);
                else gui.item(r.stack, x, ty, 1, mx, my);
                String name = r.air ? StatCollector.translateToLocal("ic2cextras.guide.air") : r.stack.getDisplayName();
                gui.text(gui.trim(name, GuiGuideBook.SIDE_W - 19), x + 19, ty, GuiGuideBook.TEXT);
                String sub = "×" + r.count;
                if (r.controller) sub += "  " + StatCollector.translateToLocal("ic2cextras.guide.controller");
                if (r.air) sub += "  " + StatCollector.translateToLocal("ic2cextras.guide.keepEmpty");
                gui.text(gui.trim(sub, GuiGuideBook.SIDE_W - 19), x + 19, ty + 9, GuiGuideBook.MUTED);
                ty += 20;
                if (r.alt != null && r.altCount > 0) {
                    gui.item(r.alt, x + 4, ty, 0.75F, mx, my);
                    String alt = StatCollector.translateToLocalFormatted("ic2cextras.guide.altCount", r.altCount);
                    List<String> l = gui.wrap("§9●§r " + alt, GuiGuideBook.SIDE_W - 19);
                    for (int i = 0; i < l.size() && i < 2; i++)
                        gui.text(l.get(i), x + 19, ty + i * 9, GuiGuideBook.MUTED);
                    ty += Math.max(14, Math.min(2, l.size()) * 9 + 2);
                }
            }
            drawMap(gui, x, ty + 2, y + GuiGuideBook.SIDE_H, mx, my);
        }

        /** Top-down map of the selected layer, front row at the bottom. */
        private void drawMap(GuiGuideBook gui, int x, int top, int bottom, int mx, int my) {
            MultiblockDef def = view.def;
            int layer = view.layer();
            if (layer < 0) {
                List<String> hint = gui
                    .wrap(StatCollector.translateToLocal("ic2cextras.guide.mapHint"), GuiGuideBook.SIDE_W);
                int ty = Math.max(top, bottom - hint.size() * 9);
                for (String s : hint) {
                    gui.text(s, x, ty, GuiGuideBook.MUTED);
                    ty += 9;
                }
                return;
            }
            int avail = bottom - top - 10;
            int cell = Math.min(16, Math.min(GuiGuideBook.SIDE_W / def.width, avail / def.depth));
            if (cell < 4) return;
            int mapW = cell * def.width, mapH = cell * def.depth;
            int ox = x + (GuiGuideBook.SIDE_W - mapW) / 2;
            int oy = top;
            gui.rect(ox - 1, oy - 1, ox + mapW + 1, oy + mapH + 1, 0x50605A3C);
            for (int z = 0; z < def.depth; z++) {
                for (int lx = 0; lx < def.width; lx++) {
                    MultiblockDef.Part p = def.partAt(lx, layer, z);
                    int cx = ox + lx * cell, cy = oy + (def.depth - 1 - z) * cell;
                    gui.rect(cx, cy, cx + cell, cy + cell, (lx + z) % 2 == 0 ? 0x18000000 : 0x0C000000);
                    if (!p.isAir()) gui.item(
                        view.access.showAlternatives && p.altBlock != null ? p.altDisplayStack() : p.displayStack(),
                        cx,
                        cy,
                        cell / 16F,
                        mx,
                        my);
                    if (p.controller) {
                        int c = 0xFFE0A000;
                        gui.rect(cx, cy, cx + cell, cy + 1, c);
                        gui.rect(cx, cy + cell - 1, cx + cell, cy + cell, c);
                        gui.rect(cx, cy, cx + 1, cy + cell, c);
                        gui.rect(cx + cell - 1, cy, cx + cell, cy + cell, c);
                    }
                    if (p.altBlock != null) gui.rect(cx + cell - 4, cy + 1, cx + cell - 1, cy + 4, 0xFF2060E0);
                }
            }
            String front = StatCollector.translateToLocal("ic2cextras.guide.front");
            gui.centered("▼ " + front + " ▼", x, oy + mapH + 2, GuiGuideBook.SIDE_W, GuiGuideBook.MUTED);
        }
    }
}
