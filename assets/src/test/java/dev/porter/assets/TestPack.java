package dev.porter.assets;

import dev.porter.assets.texture.Png;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds synthetic asset trees (invented mod "examplemod", no third-party content). */
final class TestPack {
    final Path assets;

    TestPack(Path root) {
        this.assets = root.resolve("assets");
    }

    TestPack text(String path, String content) {
        try {
            Path p = assets.resolve(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    TestPack png(String path, int w, int h, int argb) {
        try {
            Path p = assets.resolve(path);
            Files.createDirectories(p.getParent());
            Files.write(p, Png.write(image(w, h, argb)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    static BufferedImage image(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb ^ (x * 7 + y * 13));
            }
        }
        return img;
    }

    AssetsCommand.Result convert(String from, String to, Context ctx) {
        return AssetsCommand.convert(assets, AssetVersion.of(from).orElseThrow(), AssetVersion.of(to).orElseThrow(), ctx);
    }

    static Context ctx() {
        return new Context();
    }
}
