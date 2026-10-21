package dev.porter.assets.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Minimal PNG helpers (header size, decode, deterministic encode). */
public final class Png {
    private Png() {
    }

    /** Width and height from the IHDR chunk, or null if the bytes are not a PNG. */
    public static int[] size(byte[] b) {
        if (b == null || b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        return new int[] {readInt(b, 16), readInt(b, 20)};
    }

    private static int readInt(byte[] b, int o) {
        return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
    }

    public static BufferedImage read(byte[] b) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) {
                throw new IOException("unsupported image");
            }
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] write(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
