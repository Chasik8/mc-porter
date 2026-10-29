package dev.porter.testkit.runner.gui;

import dev.porter.testkit.runner.gui.GuiCaptures.Capture;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Compares GUI captures. {@code diff} mode: one capture per version, structural diff first (size, slot layout,
 * texts mapped through lang, dynamic indicators), then a tolerant image diff with masks (texts, slot contents,
 * {@code check.mask}) and a heatmap. {@code structural} mode: one version alone (renders without errors, non-empty,
 * texts fit, expected texts present). {@code absent}: no screen may open.
 *
 * <p>Verdicts: {@code equal}, {@code acceptable-diff}, {@code broken} (diff/absent), {@code ok}/{@code broken}
 * (structural).
 */
public final class GuiCompare {
    public enum Level { BROKEN, ACCEPTABLE, INFO }

    public record Finding(Level level, String text) {
    }

    /** A dynamic indicator: a GUI region that changed between two captures of the same screen. */
    public record Box(int x, int y, int w, int h) {
        double iou(Box o) {
            int ix = Math.max(0, Math.min(x + w, o.x + o.w) - Math.max(x, o.x));
            int iy = Math.max(0, Math.min(y + h, o.y + o.h) - Math.max(y, o.y));
            double inter = (double) ix * iy;
            return inter / (w * h + o.w * o.h - inter);
        }

        double centreDistance(Box o) {
            return Math.hypot(x + w / 2.0 - (o.x + o.w / 2.0), y + h / 2.0 - (o.y + o.h / 2.0));
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + " " + w + "x" + h + ")";
        }
    }

    public record Result(String name, String mode, String verdict, List<Finding> findings, double diffRatio,
            BufferedImage heatmap, List<Box> indicatorsA, List<Box> indicatorsB, Capture a, Capture b) {
    }

    private static final double EQUAL_RATIO = 0.005;

    private GuiCompare() {
    }

    // ------------------------------------------------------------------ diff mode

    public static Result diff(Capture a, Capture b, Capture aBase, Capture bBase, Lang langA, Lang langB) {
        List<Finding> f = new ArrayList<>();
        String name = a != null ? a.name() : b.name();
        if (a == null || b == null) {
            f.add(new Finding(Level.BROKEN, "capture missing in " + (a == null ? "A (original)" : "B (port)")));
            return new Result(name, "diff", "broken", f, Double.NaN, null, List.of(), List.of(), a, b);
        }
        String mode = a.mode();
        if (mode.equals("absent")) {
            if (!a.absent()) {
                f.add(new Finding(Level.BROKEN, "A (original) opened a screen: " + a.entry().path("screenClass").asString()));
            }
            if (!b.absent()) {
                f.add(new Finding(Level.BROKEN, b.failed() ? "B op failed: " + b.entry().path("error").asString()
                        : "B (port) opened a screen where none is expected: " + b.entry().path("screenClass").asString()));
            }
            if (f.isEmpty()) {
                f.add(new Finding(Level.INFO, "no GUI in either version (as expected)"));
            }
            return new Result(name, mode, verdict(f, 0), f, 0, null, List.of(), List.of(), a, b);
        }
        sanity(a, "A", langA, f, true);
        sanity(b, "B", langB, f, false);
        if (a.failed() || b.failed() || a.image() == null || b.image() == null) {
            return new Result(name, mode, "broken", f, Double.NaN, null, List.of(), List.of(), a, b);
        }
        // size
        int dw = b.width() - a.width();
        int dh = b.height() - a.height();
        if (dw != 0 || dh != 0) {
            f.add(new Finding(Math.abs(dw) > 4 || Math.abs(dh) > 4 ? Level.BROKEN : Level.ACCEPTABLE,
                    "GUI size " + a.width() + "x" + a.height() + " (A) vs " + b.width() + "x" + b.height() + " (B)"));
        }
        slots(a, b, f);
        texts(a, b, langA, langB, f);
        // dynamic indicators
        List<Box> ia = List.of();
        List<Box> ib = List.of();
        if (aBase != null && bBase != null && aBase.image() != null && bBase.image() != null) {
            ia = indicators(aBase, a);
            ib = indicators(bBase, b);
            indicatorDiff(ia, ib, f);
        } else if (a.check().has("dynamicFrom")) {
            f.add(new Finding(Level.BROKEN, "dynamicFrom capture " + a.check().path("dynamicFrom").asString() + " missing"));
        }
        // image
        boolean[][] mask = mask(a, b);
        double threshold = a.check().path("pixelThreshold").asDouble(0.1);
        int w = Math.min(a.image().getWidth(), b.image().getWidth());
        int h = Math.min(a.image().getHeight(), b.image().getHeight());
        BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long differing = 0;
        long counted = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pb = b.image().getRGB(x, y);
                int grey = (int) (lum(pb) * 0.45 + 40);
                int base = grey << 16 | grey << 8 | grey;
                if (mask[y][x]) {
                    heat.setRGB(x, y, mix(base, 0x3060ff, 0.35));
                    continue;
                }
                counted++;
                double d = Math.min(nearest(a.image(), x, y, pb), nearest(b.image(), x, y, a.image().getRGB(x, y)));
                if (d > threshold) {
                    differing++;
                    heat.setRGB(x, y, mix(base, 0xff2010, Math.min(1, 0.45 + d)));
                } else {
                    heat.setRGB(x, y, base);
                }
            }
        }
        double ratio = counted == 0 ? 0 : (double) differing / counted;
        double maxDiff = a.check().path("maxDiff").asDouble(0.1);
        String pct = String.format(java.util.Locale.ROOT, "%.2f %%", ratio * 100);
        if (ratio > maxDiff) {
            f.add(new Finding(Level.BROKEN, "image: " + pct + " of unmasked pixels differ (limit " + Math.round(maxDiff * 100) + " %)"));
        } else if (ratio > EQUAL_RATIO) {
            f.add(new Finding(Level.ACCEPTABLE, "image: " + pct + " of unmasked pixels differ"));
        } else {
            f.add(new Finding(Level.INFO, "image: " + pct + " of unmasked pixels differ"));
        }
        for (Box bx : ia) {
            outline(heat, bx, 0x20d020);
        }
        for (Box bx : ib) {
            outline(heat, bx, 0xffd000);
        }
        return new Result(name, mode, verdict(f, ratio), f, ratio, heat, ia, ib, a, b);
    }

    // ------------------------------------------------------------------ structural mode (one version)

    public static Result structural(Capture c, Lang lang) {
        List<Finding> f = new ArrayList<>();
        if (c.mode().equals("absent")) {
            if (!c.absent()) {
                f.add(new Finding(Level.BROKEN, c.failed() ? c.entry().path("error").asString() : "a screen opened: " + c.entry().path("screenClass").asString()));
            } else {
                f.add(new Finding(Level.INFO, "no GUI (as expected)"));
            }
            return new Result(c.name(), "absent", f.stream().anyMatch(x -> x.level() == Level.BROKEN) ? "broken" : "ok", f, 0, null, List.of(), List.of(), c, null);
        }
        sanity(c, "", lang, f, false);
        boolean broken = f.stream().anyMatch(x -> x.level() == Level.BROKEN);
        if (!broken) {
            f.add(new Finding(Level.INFO, c.entry().path("texts").size() + " texts, all inside their boxes; "
                    + distinctColours(c.image()) + " distinct colours"));
        }
        return new Result(c.name(), "structural", broken ? "broken" : "ok", f, Double.NaN, null, List.of(), List.of(), null, c);
    }

    /** Per-version checks: op succeeded, no render errors, non-empty image, texts fit, no raw keys, expected texts. */
    static void sanity(Capture c, String side, Lang lang, List<Finding> f, boolean original) {
        String p = side.isEmpty() ? "" : side + ": ";
        Level lvl = original ? Level.ACCEPTABLE : Level.BROKEN; // problems of the original are notes, not port bugs
        if (c.failed()) {
            f.add(new Finding(Level.BROKEN, p + "GUI op failed: " + c.entry().path("error").asString()));
            return;
        }
        if (c.absent()) {
            f.add(new Finding(Level.BROKEN, p + "no screen opened"));
            return;
        }
        for (JsonNode e : c.entry().path("renderErrors")) {
            f.add(new Finding(lvl, p + "logged while rendering: " + e.asString()));
        }
        if (c.image() == null) {
            f.add(new Finding(Level.BROKEN, p + "no image captured"));
            return;
        }
        List<Box> missing = missingTextures(c.image());
        if (!missing.isEmpty()) {
            f.add(new Finding(lvl, p + "missing-texture pattern (magenta/black) drawn at " + missing.size() + " place(s): "
                    + missing.subList(0, Math.min(6, missing.size()))));
        }
        int colours = distinctColours(c.image());
        if (colours < 3 || dominantShare(c.image()) > 0.97) {
            f.add(new Finding(Level.BROKEN, p + "image is (nearly) empty: " + colours + " colours"));
        }
        List<int[]> boxes = new ArrayList<>();
        for (JsonNode r : c.check().path("textBoxes")) {
            boxes.add(new int[] {r.get(0).asInt(), r.get(1).asInt(), r.get(2).asInt(), r.get(3).asInt()});
        }
        if (boxes.isEmpty()) {
            boxes.add(new int[] {0, 0, c.width(), c.height()});
        }
        int overflow = 0;
        for (JsonNode t : c.entry().path("texts")) {
            int x = t.path("x").asInt();
            int y = t.path("y").asInt();
            int w = t.path("width").asInt();
            boolean inside = false;
            for (int[] b : boxes) {
                inside |= x >= b[0] && y >= b[1] && x + w <= b[0] + b[2] && y + 8 <= b[1] + b[3];
            }
            // an empty label (width 0) is an icon-only widget (tab / IconButton with an item icon): nothing is drawn as text,
            // so it cannot overflow, even when the icon tab sits outside the text frame.
            boolean blank = w <= 0 && Lang.strip(t.path("text").asString()).isBlank();
            if (!inside && !blank && overflow++ < 6) {
                f.add(new Finding(lvl, p + "text overflows its box: \"" + Lang.strip(t.path("text").asString()) + "\" at (" + x + "," + y + ") width " + w));
            }
            if (Lang.looksLikeKey(t.path("text").asString())) {
                f.add(new Finding(lvl, p + "untranslated lang key drawn: " + t.path("text").asString()));
            }
        }
        for (JsonNode k : c.check().path("texts")) {
            Pattern pt = lang.pattern(k.asString());
            if (pt == null) {
                f.add(new Finding(lvl, p + "expected text key " + k.asString() + " has no translation"));
                continue;
            }
            boolean found = false;
            for (JsonNode t : c.entry().path("texts")) {
                found |= pt.matcher(Lang.strip(t.path("text").asString())).matches();
            }
            if (!found) {
                f.add(new Finding(lvl, p + "expected text not drawn: " + k.asString() + " = \"" + lang.get(k.asString()) + "\""));
            }
        }
    }

    // ------------------------------------------------------------------ structure

    static void slots(Capture a, Capture b, List<Finding> f) {
        List<int[]> ma = slotList(a, false);
        List<int[]> mb = slotList(b, false);
        int ha = hidden(ma, a);
        int hb = hidden(mb, b);
        if (ha + hb > 0) {
            f.add(new Finding(Level.INFO, "slots parked outside the GUI (hidden, not compared): " + ha + " (A), " + hb + " (B)"));
        }
        List<int[]> missing = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int[] s : ma) {
            int hit = -1;
            for (int i = 0; i < mb.size(); i++) {
                if (!used.contains(i) && Math.abs(mb.get(i)[0] - s[0]) <= 1 && Math.abs(mb.get(i)[1] - s[1]) <= 1) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                missing.add(s);
            } else {
                used.add(hit);
            }
        }
        if (!missing.isEmpty()) {
            f.add(new Finding(Level.BROKEN, "machine slots of A missing in B at " + coords(missing)));
        }
        List<int[]> extra = new ArrayList<>();
        for (int i = 0; i < mb.size(); i++) {
            if (!used.contains(i)) {
                extra.add(mb.get(i));
            }
        }
        if (!extra.isEmpty()) {
            f.add(new Finding(Level.BROKEN, "extra machine slots in B at " + coords(extra)));
        }
        if (missing.isEmpty() && extra.isEmpty() && !ma.isEmpty()) {
            f.add(new Finding(Level.INFO, ma.size() + " machine slots at the same positions"));
        }
        List<int[]> pa = slotList(a, true);
        List<int[]> pb = slotList(b, true);
        if (pa.size() != pb.size()) {
            f.add(new Finding(Level.BROKEN, "player inventory slots: " + pa.size() + " (A) vs " + pb.size() + " (B)"));
        } else {
            int off = 0;
            for (int i = 0; i < pa.size(); i++) {
                if (pa.get(i)[0] != pb.get(i)[0] || pa.get(i)[1] != pb.get(i)[1]) {
                    off++;
                }
            }
            if (off > 0) {
                f.add(new Finding(Level.BROKEN, off + " player inventory slots at other positions (A first: " + coords(pa.subList(0, 1))
                        + ", B first: " + coords(pb.subList(0, 1)) + ")"));
            }
        }
    }

    /** Removes slots lying completely outside the GUI rectangle (screens park inactive slots off-panel). */
    static int hidden(List<int[]> l, Capture c) {
        int before = l.size();
        l.removeIf(s -> s[0] + 16 <= 0 || s[1] + 16 <= 0 || s[0] >= c.width() || s[1] >= c.height());
        return before - l.size();
    }

    static List<int[]> slotList(Capture c, boolean player) {
        List<int[]> l = new ArrayList<>();
        for (JsonNode s : c.entry().path("slots")) {
            if (s.path("player").asBoolean(false) == player) {
                l.add(new int[] {s.path("x").asInt(), s.path("y").asInt()});
            }
        }
        return l;
    }

    static String coords(List<int[]> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 8; i++) {
            sb.append(i == 0 ? "" : " ").append("(").append(l.get(i)[0]).append(",").append(l.get(i)[1]).append(")");
        }
        return sb + (l.size() > 8 ? " ..." : "");
    }

    /** Texts: identical after normalisation, else equivalent through a shared lang key / English value. */
    static void texts(Capture a, Capture b, Lang langA, Lang langB, List<Finding> f) {
        List<JsonNode> tb = new ArrayList<>();
        b.entry().path("texts").forEach(tb::add);
        Set<Integer> used = new HashSet<>();
        int same = 0;
        for (JsonNode t : a.entry().path("texts")) {
            String ta = Lang.strip(t.path("text").asString());
            if (ta.isEmpty()) {
                continue;
            }
            int hit = -1;
            String via = null;
            for (int i = 0; i < tb.size() && hit < 0; i++) {
                if (!used.contains(i) && norm(ta).equals(norm(Lang.strip(tb.get(i).path("text").asString())))) {
                    hit = i;
                }
            }
            if (hit < 0) {
                for (String k : langA.keysFor(ta)) {
                    List<Pattern> cands = new ArrayList<>();
                    if (langB.pattern(k) != null) {
                        cands.add(langB.pattern(k));
                    }
                    cands.add(Lang.toPattern(langA.get(k)));
                    for (int i = 0; i < tb.size() && hit < 0; i++) {
                        String s = Lang.strip(tb.get(i).path("text").asString());
                        for (Pattern p : cands) {
                            if (!used.contains(i) && p.matcher(s).matches()) {
                                hit = i;
                                via = k;
                                break;
                            }
                        }
                    }
                    if (hit >= 0) {
                        break;
                    }
                }
            }
            if (hit < 0) {
                boolean masked = inMask(a, b, t.path("x").asInt(), t.path("y").asInt());
                f.add(new Finding(masked ? Level.ACCEPTABLE : Level.BROKEN, (masked ? "masked region: " : "")
                        + "text of A not drawn in B: \"" + ta + "\" at (" + t.path("x").asInt() + "," + t.path("y").asInt() + ")"
                        + keyHint(langA, ta)));
                continue;
            }
            used.add(hit);
            JsonNode m = tb.get(hit);
            int dx = m.path("x").asInt() - t.path("x").asInt();
            int dy = m.path("y").asInt() - t.path("y").asInt();
            if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                f.add(new Finding(Level.ACCEPTABLE, "text \"" + ta + "\" moved by (" + dx + "," + dy + ")"));
            }
            if (via != null) {
                f.add(new Finding(Level.INFO, "text \"" + ta + "\" = \"" + Lang.strip(m.path("text").asString()) + "\" via lang key " + via));
            }
            same++;
        }
        for (int i = 0; i < tb.size(); i++) {
            if (!used.contains(i) && !Lang.strip(tb.get(i).path("text").asString()).isEmpty()) {
                f.add(new Finding(Level.ACCEPTABLE, "extra text in B: \"" + Lang.strip(tb.get(i).path("text").asString()) + "\""));
            }
        }
        if (same > 0) {
            f.add(new Finding(Level.INFO, same + " texts present in both"));
        }
    }

    private static String keyHint(Lang lang, String text) {
        List<String> keys = lang.keysFor(text);
        return keys.isEmpty() ? "" : " (lang key " + keys.get(0) + ")";
    }

    /** Values change with state and timing: compare texts with digits collapsed. */
    static String norm(String s) {
        return s.replaceAll("[0-9]+([.,][0-9]+)?", "#");
    }

    // ------------------------------------------------------------------ dynamic indicators

    /** Changed regions between two captures of the same screen, outside slot interiors. */
    static List<Box> indicators(Capture before, Capture after) {
        BufferedImage p = before.image();
        BufferedImage q = after.image();
        int w = Math.min(p.getWidth(), q.getWidth());
        int h = Math.min(p.getHeight(), q.getHeight());
        boolean[][] slot = new boolean[h][w];
        for (Capture c : List.of(before, after)) {
            for (JsonNode s : c.entry().path("slots")) {
                fill(slot, s.path("x").asInt() - 1, s.path("y").asInt() - 1, 18, 18);
            }
            for (JsonNode t : c.entry().path("texts")) {
                fill(slot, t.path("x").asInt() - 1, t.path("y").asInt() - 1, t.path("width").asInt() + 2, 10);
            }
        }
        boolean[][] ch = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ch[y][x] = !slot[y][x] && dist(p.getRGB(x, y), q.getRGB(x, y)) > 0.08;
            }
        }
        List<Box> boxes = new ArrayList<>();
        boolean[][] seen = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!ch[y][x] || seen[y][x]) {
                    continue;
                }
                int x0 = x, x1 = x, y0 = y, y1 = y, n = 0;
                ArrayDeque<int[]> q2 = new ArrayDeque<>();
                q2.add(new int[] {x, y});
                seen[y][x] = true;
                while (!q2.isEmpty()) {
                    int[] c = q2.poll();
                    n++;
                    x0 = Math.min(x0, c[0]);
                    x1 = Math.max(x1, c[0]);
                    y0 = Math.min(y0, c[1]);
                    y1 = Math.max(y1, c[1]);
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int nx = c[0] + dx, ny = c[1] + dy;
                            if (nx >= 0 && ny >= 0 && nx < w && ny < h && ch[ny][nx] && !seen[ny][nx]) {
                                seen[ny][nx] = true;
                                q2.add(new int[] {nx, ny});
                            }
                        }
                    }
                }
                if (n >= 4) {
                    boxes.add(new Box(x0, y0, x1 - x0 + 1, y1 - y0 + 1));
                }
            }
        }
        return boxes;
    }

    static void indicatorDiff(List<Box> ia, List<Box> ib, List<Finding> f) {
        Set<Integer> used = new HashSet<>();
        int matched = 0;
        for (Box a : ia) {
            int hit = -1;
            for (int i = 0; i < ib.size() && hit < 0; i++) {
                Box b = ib.get(i);
                if (!used.contains(i) && (a.iou(b) >= 0.3 || a.centreDistance(b) <= 6 + Math.max(a.w, a.h) / 4.0)) {
                    hit = i;
                }
            }
            if (hit < 0) {
                f.add(new Finding(Level.BROKEN, "dynamic indicator of A " + a + " does not change in B"));
            } else {
                used.add(hit);
                matched++;
                Box b = ib.get(hit);
                if (a.iou(b) < 0.6) {
                    f.add(new Finding(Level.ACCEPTABLE, "dynamic indicator A " + a + " vs B " + b));
                }
            }
        }
        for (int i = 0; i < ib.size(); i++) {
            if (!used.contains(i)) {
                f.add(new Finding(Level.ACCEPTABLE, "extra dynamic region in B " + ib.get(i)));
            }
        }
        f.add(new Finding(Level.INFO, "dynamic indicators: " + ia.size() + " (A), " + ib.size() + " (B), " + matched + " matched"));
    }

    // ------------------------------------------------------------------ image helpers

    /** Clusters of the missing-texture magenta (#f800f8 in both versions) of at least 6 pixels. */
    static List<Box> missingTextures(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] m = new boolean[h][w];
        int any = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
                m[y][x] = r >= 200 && b >= 200 && g <= 40;
                any += m[y][x] ? 1 : 0;
            }
        }
        List<Box> out = new ArrayList<>();
        if (any < 6) {
            return out;
        }
        boolean[][] seen = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x] || seen[y][x]) {
                    continue;
                }
                int x0 = x, x1 = x, y0 = y, y1 = y, n = 0;
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[] {x, y});
                seen[y][x] = true;
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    n++;
                    x0 = Math.min(x0, c[0]);
                    x1 = Math.max(x1, c[0]);
                    y0 = Math.min(y0, c[1]);
                    y1 = Math.max(y1, c[1]);
                    // the checkerboard alternates with black: join magenta cells up to 3 px apart
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int nx = c[0] + dx, ny = c[1] + dy;
                            if (nx >= 0 && ny >= 0 && nx < w && ny < h && m[ny][nx] && !seen[ny][nx]) {
                                seen[ny][nx] = true;
                                q.add(new int[] {nx, ny});
                            }
                        }
                    }
                }
                if (n >= 6) {
                    out.add(new Box(x0, y0, x1 - x0 + 1, y1 - y0 + 1));
                }
            }
        }
        return out;
    }

    /**
     * Whether (x, y) lies in a {@code check.mask} region of either capture. A masked region is not compared: neither
     * its pixels nor a text of A drawn there (the scenario documents why, e.g. a feature the target does not have).
     */
    static boolean inMask(Capture a, Capture b, int x, int y) {
        for (Capture c : List.of(a, b)) {
            for (JsonNode r : c.check().path("mask")) {
                int rx = r.get(0).asInt(), ry = r.get(1).asInt();
                if (x >= rx && y >= ry && x < rx + r.get(2).asInt() && y < ry + r.get(3).asInt()) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean[][] mask(Capture a, Capture b) {
        int w = Math.min(a.image().getWidth(), b.image().getWidth());
        int h = Math.min(a.image().getHeight(), b.image().getHeight());
        boolean[][] m = new boolean[h][w];
        for (Capture c : List.of(a, b)) {
            for (JsonNode r : c.check().path("mask")) {
                fill(m, r.get(0).asInt(), r.get(1).asInt(), r.get(2).asInt(), r.get(3).asInt());
            }
            for (JsonNode t : c.entry().path("texts")) {
                fill(m, t.path("x").asInt() - 1, t.path("y").asInt() - 1, t.path("width").asInt() + 2, 10);
            }
            for (JsonNode s : c.entry().path("slots")) {
                fill(m, s.path("x").asInt(), s.path("y").asInt(), 16, 16);
            }
        }
        return m;
    }

    static void fill(boolean[][] m, int x, int y, int w, int h) {
        for (int j = Math.max(0, y); j < Math.min(m.length, y + h); j++) {
            for (int i = Math.max(0, x); i < Math.min(m[j].length, x + w); i++) {
                m[j][i] = true;
            }
        }
    }

    /** Smallest colour distance between rgb and the 3x3 neighbourhood of (x, y) in img (1 px shift tolerance). */
    static double nearest(BufferedImage img, int x, int y, int rgb) {
        double best = 1;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < img.getWidth() && ny < img.getHeight()) {
                    best = Math.min(best, dist(img.getRGB(nx, ny), rgb));
                }
            }
        }
        return best;
    }

    /** Perceptual-ish colour distance 0..1: weighted RGB (redmean). */
    static double dist(int p, int q) {
        int r1 = p >> 16 & 255, g1 = p >> 8 & 255, b1 = p & 255;
        int r2 = q >> 16 & 255, g2 = q >> 8 & 255, b2 = q & 255;
        double rm = (r1 + r2) / 2.0;
        double dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        double d = Math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db);
        return Math.min(1, d / 764.8);
    }

    static double lum(int p) {
        return 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
    }

    static int mix(int base, int tint, double t) {
        int r = (int) ((base >> 16 & 255) * (1 - t) + (tint >> 16 & 255) * t);
        int g = (int) ((base >> 8 & 255) * (1 - t) + (tint >> 8 & 255) * t);
        int b = (int) ((base & 255) * (1 - t) + (tint & 255) * t);
        return r << 16 | g << 8 | b;
    }

    static void outline(BufferedImage img, Box b, int rgb) {
        for (int x = b.x(); x < b.x() + b.w(); x++) {
            set(img, x, b.y(), rgb);
            set(img, x, b.y() + b.h() - 1, rgb);
        }
        for (int y = b.y(); y < b.y() + b.h(); y++) {
            set(img, b.x(), y, rgb);
            set(img, b.x() + b.w() - 1, y, rgb);
        }
    }

    private static void set(BufferedImage img, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
            img.setRGB(x, y, rgb);
        }
    }

    static int distinctColours(BufferedImage img) {
        if (img == null) {
            return 0;
        }
        Set<Integer> s = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                s.add(img.getRGB(x, y) & 0xffffff);
                if (s.size() > 5000) {
                    return s.size();
                }
            }
        }
        return s.size();
    }

    static double dominantShare(BufferedImage img) {
        java.util.HashMap<Integer, Integer> n = new java.util.HashMap<>();
        int max = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                max = Math.max(max, n.merge(img.getRGB(x, y) & 0xffffff, 1, Integer::sum));
            }
        }
        return (double) max / Math.max(1, img.getWidth() * img.getHeight());
    }

    static String verdict(List<Finding> f, double ratio) {
        if (f.stream().anyMatch(x -> x.level() == Level.BROKEN)) {
            return "broken";
        }
        if (f.stream().anyMatch(x -> x.level() == Level.ACCEPTABLE) || ratio > EQUAL_RATIO) {
            return "acceptable-diff";
        }
        return "equal";
    }
}
