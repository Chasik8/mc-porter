// porter:adapter (generic) - 1.19.2 DyeColor (ids as 1.19.2: white = 0 .. black = 15; the 1.7.10 dye item damage is
// 15 - id, wool metadata = id).
package dev.porter.shim.mc;

public enum DyeColor {
    WHITE(0, "white", 16383998), ORANGE(1, "orange", 16351261), MAGENTA(2, "magenta", 13061821), LIGHT_BLUE(3, "light_blue", 3847130),
    YELLOW(4, "yellow", 16701501), LIME(5, "lime", 8439583), PINK(6, "pink", 15961002), GRAY(7, "gray", 4673362),
    LIGHT_GRAY(8, "light_gray", 10329495), CYAN(9, "cyan", 1481884), PURPLE(10, "purple", 8991416), BLUE(11, "blue", 3949738),
    BROWN(12, "brown", 8606770), GREEN(13, "green", 6192150), RED(14, "red", 11546150), BLACK(15, "black", 1908001);

    private final int id;
    private final String name;
    private final int textureDiffuseColor;

    DyeColor(int id, String name, int color) {
        this.id = id;
        this.name = name;
        this.textureDiffuseColor = color;
    }

    public int getId() { return id; }

    public String getName() { return name; }

    public String getSerializedName() { return name; }

    public int getTextColor() { return textureDiffuseColor; }

    public int getFireworkColor() { return textureDiffuseColor; }

    public int getMaterialColor() { return textureDiffuseColor; }

    /** 1.7.10 dye item damage. */
    public int dyeDamage() { return 15 - id; }

    public static DyeColor byId(int id) { return id < 0 || id >= 16 ? WHITE : values()[id]; }

    public static DyeColor byName(String name, DyeColor def) {
        for (DyeColor c : values()) if (c.name.equals(name)) return c;
        return def;
    }

    public static DyeColor byDyeDamage(int damage) { return byId(15 - damage); }
}
