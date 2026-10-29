package dev.porter.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetSelectTest {
    @Test
    void doubledPngExtensionIsNotCopied(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("src/mod/textures/item/glass");
        Files.createDirectories(dir);
        for (String f : List.of("tea.png", "tea.png.png", "tea.png.mcmeta")) {
            Files.write(dir.resolve(f), new byte[] {1});
        }
        Files.writeString(dir.resolve("texture_package.json"),
            "{\"tea\": \"mod:item/glass/tea\", \"tea.png\": \"mod:item/glass/tea.png\"}");
        Path out = tmp.resolve("out");
        AssetSelect.Result r = AssetSelect.select(tmp.resolve("src"), "mod", List.of(), List.of("textures/item/glass/*"), List.of(), out);
        Path got = out.resolve("assets/mod/textures/item/glass");
        assertEquals(3, r.textures()); // tea.png, its mcmeta, the package
        assertTrue(Files.isRegularFile(got.resolve("tea.png")));
        assertTrue(Files.isRegularFile(got.resolve("tea.png.mcmeta")));
        assertFalse(Files.exists(got.resolve("tea.png.png")));
    }

    @Test
    void literalTextureAndSoundPathsAreKept(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("src/mod");
        Files.createDirectories(a.resolve("textures/gui"));
        Files.createDirectories(a.resolve("sounds/machine"));
        for (String f : List.of("textures/gui/panel.png", "textures/gui/other.png", "textures/gui/bar.png",
                "textures/gui/bar.png.mcmeta", "sounds/machine/hum.ogg", "textures/gui/unused.png")) {
            Files.write(a.resolve(f), new byte[] {1});
        }
        Path java = tmp.resolve("Gui.java");
        Files.writeString(java, "class Gui { Object a = new ResourceLocation(\"mod\", \"textures/gui/panel.png\");\n"
                + " Object b = \"mod:textures/gui/other.png\"; Object c = \"gui/bar.png\"; Object d = \"machine/hum\";\n"
                + " Object e = \"textures/gui/missing.png\"; }\n");
        Path out = tmp.resolve("out");
        AssetSelect.select(tmp.resolve("src"), "mod", List.of(), List.of(), List.of(java), out);
        Path got = out.resolve("assets/mod");
        assertTrue(Files.isRegularFile(got.resolve("textures/gui/panel.png")));
        assertTrue(Files.isRegularFile(got.resolve("textures/gui/other.png")));
        assertTrue(Files.isRegularFile(got.resolve("textures/gui/bar.png")));
        assertTrue(Files.isRegularFile(got.resolve("textures/gui/bar.png.mcmeta")));
        assertTrue(Files.isRegularFile(got.resolve("sounds/machine/hum.ogg")));
        assertFalse(Files.exists(got.resolve("textures/gui/unused.png")));
        assertFalse(Files.exists(got.resolve("textures/gui/missing.png")));
    }
}
