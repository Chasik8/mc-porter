package dev.porter.testkit.runner.gui;

import dev.porter.testkit.runner.gui.GuiCaptures.Capture;
import dev.porter.testkit.runner.gui.GuiCompare.Finding;
import dev.porter.testkit.runner.gui.GuiCompare.Result;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Builds the GUI report: compares capture sets (A = original version, B = port; or one set alone for structural
 * checks) and writes {@code gui-report.html} (side-by-side images, heatmaps, findings) and {@code gui-report.md}
 * (verdict table) into a report directory, images under {@code img/}.
 */
public final class GuiReport {
    private final Path out;
    private final List<Result> results = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    public GuiReport(Path out) {
        this.out = out;
    }

    public List<Result> results() {
        return results;
    }

    /**
     * A diff scenario where one side's run produced no captures (crash, hang): every capture of the other side is
     * broken, with the failed run's message.
     */
    public void failedSide(GuiCaptures.Set other, boolean missingIsA, String message) {
        for (Capture c : other.captures().values()) {
            List<Finding> f = new ArrayList<>();
            f.add(new Finding(GuiCompare.Level.BROKEN, (missingIsA ? "A" : "B") + " run produced no capture: " + message));
            results.add(new Result(c.name(), c.mode(), "broken", f, Double.NaN, null, List.of(), List.of(),
                    missingIsA ? null : c, missingIsA ? c : null));
            labels.add(other.scenario());
        }
    }

    /** Compares two capture sets of the same scenario (diff/absent captures pairwise, structural ones on B alone). */
    public void compare(GuiCaptures.Set a, GuiCaptures.Set b) {
        Lang la = a == null ? Lang.of(List.of()) : Lang.of(a.mods());
        Lang lb = b == null ? Lang.of(List.of()) : Lang.of(b.mods());
        Set<String> names = new LinkedHashSet<>();
        if (a != null) {
            names.addAll(a.captures().keySet());
        }
        if (b != null) {
            names.addAll(b.captures().keySet());
        }
        String scenario = a != null ? a.scenario() : b.scenario();
        for (String n : names) {
            Capture ca = a == null ? null : a.captures().get(n);
            Capture cb = b == null ? null : b.captures().get(n);
            Capture any = cb != null ? cb : ca;
            String mode = any.mode();
            Result r;
            if (mode.equals("structural") || a == null || b == null) {
                // structural captures, and single-version runs of diff captures: check what one version can show
                r = GuiCompare.structural(any, cb != null ? lb : la);
            } else {
                String base = any.check().path("dynamicFrom").asString(null);
                Capture ba = base == null || ca == null ? null : a.captures().get(base);
                Capture bb = base == null || cb == null ? null : b.captures().get(base);
                r = GuiCompare.diff(ca, cb, ba, bb, la, lb);
            }
            results.add(r);
            labels.add(scenario);
        }
    }

    /** Writes the report; returns the HTML file. */
    public Path write(String title, String aLabel, String bLabel) {
        try {
            Path img = out.resolve("img");
            Files.createDirectories(img);
            StringBuilder html = new StringBuilder();
            StringBuilder md = new StringBuilder();
            html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>GUI report</title><style>")
                    .append("body{font:14px system-ui,sans-serif;margin:16px;background:#fafafa;color:#222}")
                    .append("table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 8px;text-align:left;vertical-align:top}")
                    .append(".equal,.ok{color:#176b2c;font-weight:600}.acceptable-diff{color:#9a6500;font-weight:600}.broken{color:#b3141b;font-weight:600}")
                    .append("img{image-rendering:pixelated;border:1px solid #bbb;background:#666}.cap{margin:24px 0;padding:12px;background:#fff;border:1px solid #ddd}")
                    .append(".imgs{display:flex;gap:16px;flex-wrap:wrap}.imgs figure{margin:0}figcaption{font-size:12px;color:#555}")
                    .append("li.BROKEN{color:#b3141b}li.ACCEPTABLE{color:#9a6500}li.INFO{color:#555}</style></head><body>");
            html.append("<h1>").append(esc(title)).append("</h1><p>A = ").append(esc(aLabel)).append(", B = ").append(esc(bLabel))
                    .append(". Images at GUI scale 1, shown 2x. Heatmap: red = differing pixels, blue = masked (texts, slot contents, check.mask), ")
                    .append("green/yellow boxes = dynamic indicators of A/B.</p><table><tr><th>scenario</th><th>GUI</th><th>mode</th><th>verdict</th><th>image diff</th><th>main finding</th></tr>");
            md.append("# ").append(title).append("\n\nA = ").append(aLabel).append(", B = ").append(bLabel).append("\n\n")
                    .append("| scenario | GUI | mode | verdict | image diff | main finding |\n|---|---|---|---|---|---|\n");
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                String main = mainFinding(r);
                String ratio = Double.isNaN(r.diffRatio()) ? "-" : String.format(java.util.Locale.ROOT, "%.2f %%", r.diffRatio() * 100);
                html.append("<tr><td>").append(esc(labels.get(i))).append("</td><td><a href=\"#").append(esc(r.name())).append("\">")
                        .append(esc(r.name())).append("</a></td><td>").append(r.mode()).append("</td><td class=\"").append(r.verdict()).append("\">")
                        .append(r.verdict()).append("</td><td>").append(ratio).append("</td><td>").append(esc(main)).append("</td></tr>");
                md.append("| ").append(labels.get(i)).append(" | ").append(r.name()).append(" | ").append(r.mode()).append(" | **")
                        .append(r.verdict()).append("** | ").append(ratio).append(" | ").append(main.replace("|", "\\|")).append(" |\n");
            }
            html.append("</table>");
            md.append("\nDetails, side-by-side images and heatmaps: gui-report.html\n");
            for (Result r : results) {
                html.append("<div class=\"cap\" id=\"").append(esc(r.name())).append("\"><h2>").append(esc(r.name()))
                        .append(" <span class=\"").append(r.verdict()).append("\">").append(r.verdict()).append("</span></h2><div class=\"imgs\">");
                figure(html, img, r.name() + "-a.png", r.a() == null ? null : r.a().image(), "A " + screen(r.a()));
                figure(html, img, r.name() + "-b.png", r.b() == null ? null : r.b().image(), "B " + screen(r.b()));
                figure(html, img, r.name() + "-heat.png", r.heatmap(), "heatmap");
                html.append("</div><ul>");
                for (Finding f : r.findings()) {
                    html.append("<li class=\"").append(f.level()).append("\">").append(f.level().name().toLowerCase()).append(": ")
                            .append(esc(f.text())).append("</li>");
                }
                html.append("</ul></div>");
                md.append("\n## ").append(r.name()).append(" — ").append(r.verdict()).append("\n\n");
                for (Finding f : r.findings()) {
                    md.append("- ").append(f.level().name().toLowerCase()).append(": ").append(f.text()).append("\n");
                }
            }
            html.append("</body></html>\n");
            Path h = out.resolve("gui-report.html");
            Files.writeString(h, html.toString(), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("gui-report.md"), md.toString(), StandardCharsets.UTF_8);
            return h;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String screen(Capture c) {
        if (c == null) {
            return "(missing)";
        }
        if (c.failed()) {
            return "(failed)";
        }
        String s = c.entry().path("screenClass").asString("none");
        return s.substring(s.lastIndexOf('.') + 1) + " " + c.width() + "x" + c.height();
    }

    private static void figure(StringBuilder html, Path dir, String file, BufferedImage img, String caption) throws IOException {
        if (img == null) {
            return;
        }
        ImageIO.write(img, "png", dir.resolve(file).toFile());
        html.append("<figure><img src=\"img/").append(esc(file)).append("\" width=\"").append(img.getWidth() * 2).append("\" height=\"")
                .append(img.getHeight() * 2).append("\"><figcaption>").append(esc(caption)).append("</figcaption></figure>");
    }

    public static String mainFinding(Result r) {
        for (GuiCompare.Level l : GuiCompare.Level.values()) {
            for (Finding f : r.findings()) {
                if (f.level() == l) {
                    return f.text();
                }
            }
        }
        return "";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public boolean anyBroken() {
        return results.stream().anyMatch(r -> r.verdict().equals("broken"));
    }
}
