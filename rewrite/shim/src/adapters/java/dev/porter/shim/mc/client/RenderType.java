// porter:adapter (generic, client) - 1.19.2 block RenderType tokens (1.7.10 decides by Block.getRenderBlockPass()).
package dev.porter.shim.mc.client;

public final class RenderType {
    private static final RenderType SOLID = new RenderType("solid", 0), CUTOUT = new RenderType("cutout", 0),
        CUTOUT_MIPPED = new RenderType("cutout_mipped", 0), TRANSLUCENT = new RenderType("translucent", 1);
    private final String name;
    /** 1.7.10 render pass */
    public final int pass;

    private RenderType(String name, int pass) {
        this.name = name;
        this.pass = pass;
    }

    public static RenderType solid() { return SOLID; }

    public static RenderType cutout() { return CUTOUT; }

    public static RenderType cutoutMipped() { return CUTOUT_MIPPED; }

    public static RenderType translucent() { return TRANSLUCENT; }

    @Override
    public String toString() { return name; }
}
