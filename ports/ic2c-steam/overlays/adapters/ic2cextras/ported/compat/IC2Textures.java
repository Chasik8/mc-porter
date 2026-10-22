// porter:adapter (IC2C-specific, client) - IC2C texture packages on 1.7.10 (the hub ic2...IC2Textures is
// redirected here by a type rule). IC2C maps sprite names to textures
// in "texture packages" (assets/<ns>/textures/<target>/<path>/texture_package.json); 1.19.2 IC2C stitches every
// package into the block atlas (TextureStitchEvent.Pre) and serves name -> sprite maps. Here: the packages shipped in
// the mod's own assets (kept by `porter assets --keep-model-files`, block -> blocks paths) are read at block-atlas
// stitch time; block sprites are plain 1.7.10 block icons, other sprites (misc/gui ...) go through the AtlasSprites
// adapter. Sprites are filled in place when the atlas is stitched. Namespace "ic2" (IC2.MOD_ID) = the port's asset
// namespace. A package that is not shipped yields an empty map (logged once) instead of IC2C's exception.
package ic2cextras.ported.compat;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.ported.shim.mc.client.AtlasSprites;

@SideOnly(value = Side.CLIENT)
public class IC2Textures {

    public static final IC2Textures INSTANCE = new IC2Textures();
    /** asset namespace of the port (IC2C's own textures live under "ic2"). */
    static final String NS = "ic2cextras";
    private static final String ROOT = "assets/" + NS + "/textures/";
    private static final String FILE = "texture_package.json";
    private static boolean registered;
    private final Map<String, Map<String, TextureAtlasSprite>> spriteMap = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    /** Called once on the client (ClosurePort): IC2C registered INSTANCE on the Forge bus the same way. */
    public static void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
    }

    public static Map<String, TextureAtlasSprite> getMappedEntries(String id) {
        Map<String, TextureAtlasSprite> m = INSTANCE.spriteMap.get(id);
        if (m == null) {
            if (INSTANCE.warned.add(id)) System.err.println("[ic2cextras] texture package " + id + " is not shipped");
            return Collections.emptyMap();
        }
        return m;
    }

    public static Map<String, TextureAtlasSprite> getMappedEntriesIC2(String id) {
        return getMappedEntries(id);
    }

    public static Map<String, TextureAtlasSprite> getMappedEntriesBlockIC2(String id) {
        return getMappedEntries(id.isEmpty() ? "block" : "block/" + id);
    }

    public static Map<String, TextureAtlasSprite> getMappedEntriesBlock(String domain, String id) {
        return getMappedEntriesBlockIC2(id);
    }

    public static Map<String, TextureAtlasSprite> getMappedEntriesItem(String domain, String id) {
        return getMappedEntriesItemIC2(id);
    }

    public static Map<String, TextureAtlasSprite> getMappedEntriesItemIC2(String id) {
        return getMappedEntries(id.isEmpty() ? "item" : "item/" + id);
    }

    @SubscribeEvent
    public void preStitch(TextureStitchEvent.Pre event) {
        if (event.map.getTextureType() != 0) return; // IC2C: block atlas only
        load(event.map);
    }

    private void load(TextureMap map) {
        spriteMap.clear();
        for (String file : packageFiles()) {
            String dir = file.substring(ROOT.length(), file.length() - FILE.length() - 1); // blocks/electric/.. | misc/gui
            String key = dir.equals("blocks") || dir.startsWith("blocks/") ? "block" + dir.substring(6)
                : dir.equals("items") || dir.startsWith("items/") ? "item" + dir.substring(5) : dir;
            boolean block = key.equals("block") || key.startsWith("block/");
            JsonObject obj;
            try (InputStream in = IC2Textures.class.getResourceAsStream("/" + file)) {
                if (in == null) continue;
                obj = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            } catch (Exception e) {
                System.err.println("[ic2cextras] bad texture package " + file + ": " + e);
                continue;
            }
            Map<String, TextureAtlasSprite> sprites = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (!e.getValue()
                    .isJsonPrimitive()) continue;
                String ref = e.getValue()
                    .getAsString();
                int colon = ref.indexOf(':');
                if (colon < 0) continue;
                String path = ref.substring(colon + 1); // blocks/... (converted), misc/gui/add, item/...
                if (path.startsWith("block/")) path = "blocks/" + path.substring(6);
                else if (path.startsWith("item/")) path = "items/" + path.substring(5);
                TextureAtlasSprite sprite = AtlasSprites.register(map, NS, path);
                if (block) decodeSides(e.getKey(), sprite, sprites);
                else sprites.put(e.getKey(), sprite);
            }
            spriteMap.put(key, sprites);
        }
    }

    /** The texture packages shipped in this mod's jar (or resources directory in a dev run). */
    private static List<String> packageFiles() {
        Set<String> out = new TreeSet<>();
        try {
            URL src = IC2Textures.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
            String s = src.toString();
            if (s.startsWith("jar:")) s = s.substring(4, s.indexOf("!/"));
            File f = new File(new URL(s).toURI());
            if (f.isFile()) {
                try (ZipFile zip = new ZipFile(f)) {
                    for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                        String n = en.nextElement()
                            .getName();
                        if (n.startsWith(ROOT) && n.endsWith("/" + FILE)) out.add(n);
                    }
                }
            } else {
                // dev run: classes and resources in separate directories
                for (File root : new File[] { f, new File(f, "../../../resources/main"), new File(f, "../../resources/main") }) {
                    scan(new File(root, ROOT), ROOT, out);
                }
            }
        } catch (Exception e) {
            System.err.println("[ic2cextras] cannot list texture packages: " + e);
        }
        return new ArrayList<>(out);
    }

    private static void scan(File dir, String rel, Set<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File c : files) {
            if (c.isDirectory()) scan(c, rel + c.getName() + "/", out);
            else if (c.getName()
                .equals(FILE)) out.add(rel + FILE);
        }
    }

    // ---- IC2C face aliases (IC2Textures.decodeSides / convertToVariants)

    private static void decodeSides(String name, TextureAtlasSprite t, Map<String, TextureAtlasSprite> map) {
        if (!variants(name).isEmpty()) {
            for (String s : variants(name)) map.put(s, t);
            return;
        }
        int u = name.lastIndexOf('_');
        if (u >= 0 && !variants(name.substring(u + 1)).isEmpty()) {
            String pre = name.substring(0, u);
            for (String s : variants(name.substring(u + 1))) map.put(pre + "_" + s, t);
            return;
        }
        map.put(name, t);
    }

    private static List<String> variants(String s) {
        List<String> l = new ArrayList<>();
        switch (s) {
            case "vertical":
            case "yaxis":
                Collections.addAll(l, "up", "down");
                break;
            case "horizontal":
            case "side":
                Collections.addAll(l, "east", "west", "north", "south");
                break;
            case "xaxis":
                Collections.addAll(l, "east", "west");
                break;
            case "zaxis":
                Collections.addAll(l, "north", "south");
                break;
            case "back":
                l.add("north");
                break;
            case "front":
                l.add("south");
                break;
            case "left":
                l.add("west");
                break;
            case "right":
                l.add("east");
                break;
            case "top":
                l.add("up");
                break;
            case "bottom":
                l.add("down");
                break;
            case "all":
                Collections.addAll(l, "east", "west", "north", "south", "up", "down");
                break;
            default:
                return l;
        }
        l.add(s);
        return l;
    }
}
