package dev.porter.assets.pack;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import dev.porter.assets.texture.Png;
import dev.porter.core.json.Json;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Packs separate sprite PNGs into sheets for 1.7.10 GUIs (which bind one texture and draw sub-rectangles), and
 * writes a map {@code sprite → (sheet, u, v, w, h)} to {@code meta/sheets/<name>.json} for generators.
 *
 * <p>Deterministic shelf packing: sprites sorted by height, width (both descending) and name; sheet width is the
 * smallest power of two that fits the widest sprite and the square root of the total area; sheets are power-of-two
 * sized and at most {@code maxSize}; overflow opens another sheet. Optional {@code trim} crops each sprite to its
 * non-transparent bounds (the offset is kept in the map). Animated sprites (with an animation .mcmeta) are skipped.
 * Sheets are written to {@code <ns>/textures/porter_sheets/<name>[_<n>].png}.
 */
public final class SheetPacker {
    private SheetPacker() {
    }

    record Sprite(String name, String path, BufferedImage img, int ox, int oy, int origW, int origH) {
        int w() {
            return img.getWidth();
        }

        int h() {
            return img.getHeight();
        }
    }

    /** Packs {@code sprites} (name → tree path); returns the number of sprites packed. */
    public static int pack(String name, String namespace, Map<String, String> sprites, AssetTree tree, Context ctx,
            int maxSize, boolean trim) {
        List<Sprite> list = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(sprites).entrySet()) {
            byte[] b = tree.get(e.getValue());
            if (b == null) {
                ctx.warn("pack-missing", name + ":" + e.getKey(), "sprite file not found: " + e.getValue());
                continue;
            }
            String meta = tree.text(e.getValue() + ".mcmeta");
            if (meta != null && Json.json().readTree(meta).has("animation")) {
                ctx.warn("pack-animated", name + ":" + e.getKey(), "animated sprite not packed: " + e.getValue());
                continue;
            }
            BufferedImage img = Png.read(b);
            int ox = 0;
            int oy = 0;
            int ow = img.getWidth();
            int oh = img.getHeight();
            if (trim) {
                int[] r = opaqueBounds(img);
                ox = r[0];
                oy = r[1];
                img = img.getSubimage(r[0], r[1], r[2], r[3]);
            }
            if (img.getWidth() > maxSize || img.getHeight() > maxSize) {
                ctx.warn("pack-too-large", name + ":" + e.getKey(), img.getWidth() + "x" + img.getHeight() + " exceeds " + maxSize);
                continue;
            }
            list.add(new Sprite(e.getKey(), e.getValue(), img, ox, oy, ow, oh));
        }
        list.sort(Comparator.comparingInt(Sprite::h).reversed().thenComparing(Comparator.comparingInt(Sprite::w).reversed())
                .thenComparing(Sprite::name));
        long area = list.stream().mapToLong(s -> (long) s.w() * s.h()).sum();
        int maxW = list.stream().mapToInt(Sprite::w).max().orElse(1);
        int width = Math.min(maxSize, pot(Math.max(maxW, (int) Math.ceil(Math.sqrt(area)))));

        Map<String, Object> spriteMap = new LinkedHashMap<>();
        List<Map<String, Object>> sheets = new ArrayList<>();
        List<Sprite> remaining = list;
        while (!remaining.isEmpty()) {
            List<Sprite> next = new ArrayList<>();
            Map<Sprite, int[]> placed = new LinkedHashMap<>();
            int x = 0;
            int y = 0;
            int shelf = 0;
            for (Sprite s : remaining) {
                if (x + s.w() > width) {
                    x = 0;
                    y += shelf;
                    shelf = 0;
                }
                if (y + s.h() > maxSize) {
                    next.add(s);
                    continue;
                }
                placed.put(s, new int[] {x, y});
                x += s.w();
                shelf = Math.max(shelf, s.h());
            }
            int height = pot(placed.entrySet().stream().mapToInt(e -> e.getValue()[1] + e.getKey().h()).max().orElse(1));
            BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int index = sheets.size();
            String file = "textures/porter_sheets/" + name + (index == 0 ? "" : "_" + index) + ".png";
            for (Map.Entry<Sprite, int[]> e : placed.entrySet()) {
                Sprite s = e.getKey();
                int[] px = s.img().getRGB(0, 0, s.w(), s.h(), null, 0, s.w());
                sheet.setRGB(e.getValue()[0], e.getValue()[1], s.w(), s.h(), px, 0, s.w()); // exact copy, no blending
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sheet", index);
                m.put("u", e.getValue()[0]);
                m.put("v", e.getValue()[1]);
                m.put("w", s.w());
                m.put("h", s.h());
                m.put("source", s.path());
                if (trim) {
                    m.put("trim", Map.of("x", s.ox(), "y", s.oy(), "width", s.origW(), "height", s.origH()));
                }
                spriteMap.put(s.name(), m);
            }
            tree.put(namespace + "/" + file, Png.write(sheet));
            Map<String, Object> sh = new LinkedHashMap<>();
            sh.put("texture", namespace + ":" + file);
            sh.put("width", width);
            sh.put("height", height);
            sheets.add(sh);
            if (next.size() == remaining.size()) {
                break; // cannot happen (each sprite fits an empty sheet), guard against loops
            }
            remaining = next;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("sheets", sheets);
        out.put("sprites", new TreeMap<>(spriteMap));
        ctx.putMeta("sheets/" + name + ".json", Json.json().writeValueAsString(out) + "\n");
        return spriteMap.size();
    }

    static int pot(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    /** x, y, w, h of the non-transparent pixels (whole image if fully opaque, 1x1 if fully transparent). */
    static int[] opaqueBounds(BufferedImage img) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return new int[] {0, 0, 1, 1};
        }
        return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }
}
