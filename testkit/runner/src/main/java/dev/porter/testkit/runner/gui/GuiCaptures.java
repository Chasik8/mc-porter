package dev.porter.testkit.runner.gui;

import dev.porter.core.json.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GUI captures of one client run, exported next to the trace as {@code <scenario>-<version>.gui/}: {@code gui.json}
 * (testkit-internal format {@code porter-testkit-gui/1}: scenario, version, the mod jars (for lang lookups) and one
 * entry per capture with slots, texts, check, render errors) plus {@code <capture>.png}, the framebuffer cropped to
 * the GUI rectangle (GUI pixels when the client runs at GUI scale 1).
 */
public final class GuiCaptures {
    public static final String FORMAT = "porter-testkit-gui/1";

    /** One capture with its cropped image (null for absent screens or failed ops). */
    public record Capture(String name, JsonNode entry, BufferedImage image, Path imageFile) {
        public boolean failed() {
            return entry.has("error");
        }

        public boolean absent() {
            return !failed() && entry.path("screenClass").isNull();
        }

        public JsonNode check() {
            return entry.path("check");
        }

        public String mode() {
            return check().path("mode").asString("diff");
        }

        public int width() {
            return entry.path("size").path("width").asInt(image == null ? 0 : image.getWidth());
        }

        public int height() {
            return entry.path("size").path("height").asInt(image == null ? 0 : image.getHeight());
        }
    }

    /** A loaded capture directory. */
    public record Set(Path dir, String scenario, String version, List<Path> mods, Map<String, Capture> captures) {
    }

    private GuiCaptures() {
    }

    /** Directory for a trace file: {@code x.trace.json -> x.gui}. */
    public static Path dirFor(Path traceFile) {
        String n = traceFile.getFileName().toString();
        String base = n.endsWith(".trace.json") ? n.substring(0, n.length() - ".trace.json".length()) : n;
        return traceFile.resolveSibling(base + ".gui");
    }

    /** Exports the captures of a finished client run (raw result + run directory). */
    public static void export(JsonNode raw, Path runDir, Path out, List<Path> mods) {
        try {
            if (Files.isDirectory(out)) {
                try (var s = Files.list(out)) {
                    for (Path f : s.toList()) {
                        Files.delete(f);
                    }
                }
            }
            Files.createDirectories(out);
            List<String> console = Files.isRegularFile(runDir.resolve("console.log"))
                    ? Files.readAllLines(runDir.resolve("console.log"), StandardCharsets.ISO_8859_1) : List.of();
            ObjectNode doc = Json.json().createObjectNode();
            doc.put("format", FORMAT);
            doc.put("scenario", raw.path("scenario").asString());
            doc.put("version", raw.path("version").asString());
            ArrayNode m = doc.putArray("mods");
            mods.forEach(p -> m.add(p.toAbsolutePath().toString()));
            ArrayNode caps = doc.putArray("captures");
            for (JsonNode g : raw.path("gui")) {
                if (!g.has("capture")) {
                    continue;
                }
                ObjectNode e = (ObjectNode) g.deepCopy();
                String name = g.path("capture").asString();
                e.remove("screenshotFile");
                e.set("renderErrors", errors(console, name));
                String shot = g.path("screenshotFile").asString(null);
                if (shot != null && Files.isRegularFile(Path.of(shot))) {
                    BufferedImage full = ImageIO.read(Path.of(shot).toFile());
                    BufferedImage crop = crop(full, g);
                    ImageIO.write(crop, "png", out.resolve(name + ".png").toFile());
                    e.put("image", name + ".png");
                }
                caps.add(e);
            }
            Files.writeString(out.resolve("gui.json"), Json.json().writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                    .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Crops a full framebuffer screenshot to the GUI rectangle (scaled px * scale = framebuffer px). */
    static BufferedImage crop(BufferedImage full, JsonNode g) {
        int scale = Math.max(1, g.path("scale").asInt(1));
        int x = g.path("guiLeft").asInt() * scale;
        int y = g.path("guiTop").asInt() * scale;
        int w = g.path("size").path("width").asInt(full.getWidth()) * scale;
        int h = g.path("size").path("height").asInt(full.getHeight()) * scale;
        // a GUI may extend beyond the screen (or start left of it): crop the visible part, pad the rest transparent
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int sx = x + i;
                int sy = y + j;
                if (sx >= 0 && sy >= 0 && sx < full.getWidth() && sy < full.getHeight()) {
                    out.setRGB(i, j, full.getRGB(sx, sy) | 0xff000000);
                }
            }
        }
        if (scale == 1) {
            return out;
        }
        BufferedImage down = new BufferedImage(Math.max(1, w / scale), Math.max(1, h / scale), BufferedImage.TYPE_INT_ARGB);
        for (int j = 0; j < down.getHeight(); j++) {
            for (int i = 0; i < down.getWidth(); i++) {
                down.setRGB(i, j, out.getRGB(i * scale, j * scale));
            }
        }
        return down;
    }

    /** Error lines logged while the GUI op ran (between its start and end markers). */
    static ArrayNode errors(List<String> console, String capture) {
        ArrayNode a = Json.json().createArrayNode();
        boolean in = false;
        for (String l : console) {
            if (l.contains("[porter-testkit] gui op ") && l.contains(" " + capture + " start")) {
                in = true;
                continue;
            }
            if (in && l.contains("[porter-testkit] capture " + capture + " end")) {
                break;
            }
            if (in && l.contains("[porter-testkit] gui op ") && l.endsWith(" start")) {
                break;
            }
            if (in && isError(l) && a.size() < 20) {
                a.add(l.length() > 300 ? l.substring(0, 300) : l);
            }
        }
        return a;
    }

    static boolean isError(String l) {
        if (l.contains("[porter-testkit]")) {
            return false;
        }
        // sound/asset noise of a dev client without assets is not a GUI rendering error
        if (l.contains("Unable to play") || l.contains("SoundManager") || l.contains("Missing sound") || l.contains("sounds.json")) {
            return false;
        }
        return l.contains("/ERROR]") || l.contains("[ERROR]") || l.matches(".*\\b[A-Za-z.]*(Exception|Error)\\b.*: .*");
    }

    public static Set load(Path dir) {
        try {
            JsonNode doc = Json.json().readTree(Files.readString(dir.resolve("gui.json"), StandardCharsets.UTF_8));
            List<Path> mods = new ArrayList<>();
            doc.path("mods").forEach(p -> mods.add(Path.of(p.asString())));
            Map<String, Capture> caps = new LinkedHashMap<>();
            for (JsonNode e : doc.path("captures")) {
                String name = e.path("capture").asString();
                Path img = e.has("image") ? dir.resolve(e.path("image").asString()) : null;
                BufferedImage bi = img != null && Files.isRegularFile(img) ? ImageIO.read(img.toFile()) : null;
                caps.put(name, new Capture(name, e, bi, img));
            }
            return new Set(dir, doc.path("scenario").asString(), doc.path("version").asString(), mods, caps);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
