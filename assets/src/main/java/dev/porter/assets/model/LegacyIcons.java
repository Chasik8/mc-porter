package dev.porter.assets.model;

/**
 * 1.7.10 icon names. {@code IIconRegister#registerIcon("ns:x")} loads {@code textures/blocks/x.png} from the block
 * atlas and {@code textures/items/x.png} from the item atlas, so a texture ref {@code ns:blocks/x} becomes the block
 * icon {@code ns:x}. Textures outside blocks/ and items/ cannot be icons (null).
 */
public final class LegacyIcons {
    private LegacyIcons() {
    }

    public static String icon(String ref) {
        int c = ref.indexOf(':');
        String ns = c < 0 ? "minecraft" : ref.substring(0, c);
        String path = ref.substring(c + 1);
        for (String dir : new String[] {"blocks/", "items/", "block/", "item/"}) {
            if (path.startsWith(dir)) {
                return ns + ":" + path.substring(dir.length());
            }
        }
        return null;
    }

    /** Converted-tree ref ({@code ns:blocks/x}) → IR texture path in source layout ({@code ns:block/x}). */
    public static String sourceForm(String ref, boolean flattenedSource) {
        if (!flattenedSource) {
            return ref;
        }
        int c = ref.indexOf(':');
        String path = ref.substring(c + 1);
        if (path.startsWith("blocks/")) {
            return ref.substring(0, c + 1) + "block/" + path.substring(7);
        }
        if (path.startsWith("items/")) {
            return ref.substring(0, c + 1) + "item/" + path.substring(6);
        }
        return ref;
    }
}
