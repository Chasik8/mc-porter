package ic2cextras.book.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.IC2CExtras;
import ic2cextras.book.GuideBook;
import ic2cextras.book.MultiblockDef;

/**
 * Turns the contents page and entries into page sides.
 * <p>
 * Text values come from .lang files, which hold one line per key. Escapes inside a value: {@code \n} = line break,
 * {@code \p} = new page side, a line starting with {@code ## } = sub-heading. A long text can be split over several
 * keys: {@code key}, {@code key.2}, {@code key.3}, ... are joined with line breaks.
 */
@SideOnly(Side.CLIENT)
final class Layout {

    private Layout() {}

    static List<PageSide> index(GuiGuideBook gui) {
        List<PageSide> sides = new ArrayList<PageSide>();
        Sides.Title title = title(gui, new ItemStack(GuideBook.guideBook), tr("ic2cextras.guide.title"));
        List<Sides.Line> intro = lines(gui, text("ic2cextras.guide.intro"));
        flowText(gui, sides, title, intro);
        int per = GuiGuideBook.SIDE_H / Sides.Index.ROW;
        List<Object> rows = new ArrayList<Object>();
        for (GuideData.Category c : gui.data.categories) {
            if (c.entries.isEmpty()) continue;
            if (rows.size() + 2 > per) {
                sides.add(new Sides.Index(null, rows));
                rows = new ArrayList<Object>();
            }
            rows.add(c);
            for (GuideData.Entry e : c.entries) {
                if (rows.size() >= per) {
                    sides.add(new Sides.Index(null, rows));
                    rows = new ArrayList<Object>();
                }
                rows.add(e);
            }
        }
        if (!rows.isEmpty()) sides.add(new Sides.Index(null, rows));
        return sides;
    }

    static List<PageSide> entry(GuiGuideBook gui, GuideData.Entry entry) {
        List<PageSide> sides = new ArrayList<PageSide>();
        Sides.Title title = title(gui, entry.iconStack(), entry.title());
        for (GuideData.Page page : entry.pages) {
            Sides.Title t = sides.isEmpty() ? title : null;
            try {
                if ("text".equals(page.type)) {
                    flowText(gui, sides, t, lines(gui, text(page.str("text"))));
                } else if ("recipe".equals(page.type)) {
                    List<RecipeLookup.View> views = new ArrayList<RecipeLookup.View>();
                    for (String spec : page.list("items")) {
                        ItemStack target = GuideData.parseItem(spec);
                        if (target == null) IC2CExtras.LOG.warn("[guide] {}: unknown item {}", entry.id, spec);
                        else views.addAll(RecipeLookup.find(target));
                    }
                    int first = t != null ? 1 : 2;
                    if (t != null && t.height() + Sides.Recipes.BLOCK * 2 > GuiGuideBook.SIDE_H) first = 1;
                    int i = 0;
                    while (i < views.size()) {
                        int n = sides.isEmpty() && t != null ? first : 2;
                        List<RecipeLookup.View> chunk = new ArrayList<RecipeLookup.View>(
                            views.subList(i, Math.min(views.size(), i + n)));
                        sides.add(new Sides.Recipes(sides.isEmpty() ? t : null, chunk));
                        i += n;
                    }
                } else if ("multiblock".equals(page.type)) {
                    MultiblockDef def = GuiGuideBook.structure(page.str("structure"));
                    if (def == null) {
                        IC2CExtras.LOG.warn("[guide] {}: unknown structure {}", entry.id, page.str("structure"));
                        continue;
                    }
                    if (sides.isEmpty()) flowText(gui, sides, t, new ArrayList<Sides.Line>());
                    if (sides.size() % 2 == 1) sides.add(new Sides.Text(null, new ArrayList<Sides.Line>()));
                    MultiblockView view = new MultiblockView(def);
                    String heading = page.str("title") != null ? tr(page.str("title"))
                        : StatCollector.translateToLocal("ic2cextras.guide.structure");
                    sides.add(new Sides.Preview(view, heading));
                    sides.add(new Sides.Parts(view));
                } else {
                    IC2CExtras.LOG.warn("[guide] {}: unknown page type {}", entry.id, page.type);
                }
            } catch (Exception e) {
                IC2CExtras.LOG.error("[guide] {}: page {} failed", entry.id, page.type, e);
            }
        }
        if (sides.isEmpty()) sides.add(new Sides.Text(title, new ArrayList<Sides.Line>()));
        return sides;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** Value of key, key.2, key.3 ... joined; escapes kept for {@link #lines}. */
    static String text(String key) {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder(StatCollector.translateToLocal(key));
        for (int i = 2; StatCollector.canTranslate(key + "." + i); i++) sb.append("\\n")
            .append(StatCollector.translateToLocal(key + "." + i));
        return sb.toString();
    }

    static Sides.Title title(GuiGuideBook gui, ItemStack icon, String text) {
        int w = GuiGuideBook.SIDE_W - (icon != null ? 19 : 0);
        List<String> l = gui.wrap("§l" + text, w);
        if (l.size() > 2) l = l.subList(0, 2);
        return new Sides.Title(icon, new ArrayList<String>(l));
    }

    /** Splits escapes and wraps. A null line marks a forced page break. */
    static List<Sides.Line> lines(GuiGuideBook gui, String raw) {
        List<Sides.Line> out = new ArrayList<Sides.Line>();
        String norm = raw.replace("\\p", "\n\u0000\n")
            .replace("\\n", "\n");
        for (String para : norm.split("\n", -1)) {
            if (para.equals("\u0000")) {
                out.add(null);
                continue;
            }
            if (para.trim()
                .isEmpty()) {
                out.add(new Sides.Line("", Sides.BLANK_LINE));
                continue;
            }
            boolean header = para.startsWith("## ");
            String body = header ? "§l" + para.substring(3) : para;
            for (String l : gui.wrap(body, GuiGuideBook.SIDE_W))
                out.add(new Sides.Line(l, header ? Sides.HEADER_LINE : Sides.TEXT_LINE));
        }
        return out;
    }

    /** Fills text sides; the title (if any) goes on the first one. */
    static void flowText(GuiGuideBook gui, List<PageSide> sides, Sides.Title title, List<Sides.Line> lines) {
        int cap = (GuiGuideBook.SIDE_H - (title != null ? title.height() : 0)) / GuiGuideBook.LINE;
        List<Sides.Line> cur = new ArrayList<Sides.Line>();
        Sides.Title t = title;
        for (int i = 0; i < lines.size(); i++) {
            Sides.Line l = lines.get(i);
            boolean brk = l == null;
            // keep a sub-heading together with its first line
            boolean full = cur.size() >= cap || (l != null && l.kind == Sides.HEADER_LINE && cur.size() >= cap - 1);
            if (brk || full) {
                if (!cur.isEmpty() || t != null) {
                    sides.add(new Sides.Text(t, trimBlank(cur)));
                    t = null;
                    cur = new ArrayList<Sides.Line>();
                    cap = GuiGuideBook.SIDE_H / GuiGuideBook.LINE;
                }
                if (brk) continue;
            }
            if (cur.isEmpty() && l.kind == Sides.BLANK_LINE) continue;
            cur.add(l);
        }
        if (!cur.isEmpty() || t != null) sides.add(new Sides.Text(t, trimBlank(cur)));
    }

    private static List<Sides.Line> trimBlank(List<Sides.Line> l) {
        while (!l.isEmpty() && l.get(l.size() - 1).kind == Sides.BLANK_LINE) l.remove(l.size() - 1);
        return l;
    }
}
