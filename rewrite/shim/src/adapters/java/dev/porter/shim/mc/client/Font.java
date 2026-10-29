// porter:adapter (generic, client) - 1.19.2 Font over the 1.7.10 FontRenderer (Component = IChatComponent,
// FormattedText = IChatComponent, FormattedCharSequence = an already formatted string).
package dev.porter.shim.mc.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.IChatComponent;

public final class Font {
    private static Font instance;
    public final FontRenderer fr;
    public final int lineHeight;

    /** A font over one renderer; null = the game font (see get()). */
    public Font(FontRenderer fr) {
        this.fr = fr;
        this.lineHeight = 9; // FontRenderer.FONT_HEIGHT, a constant in 1.7.10
    }

    /** The game's font (what a 1.19.2 screen's font field holds): always the current Minecraft.fontRenderer. */
    public static Font get() {
        if (instance == null) instance = new Font(null);
        return instance;
    }

    /** The renderer to draw with: a Font of the game font follows Minecraft.fontRenderer when it is replaced. */
    public FontRenderer renderer() {
        return r();
    }

    private FontRenderer r() {
        return fr != null ? fr : Minecraft.getMinecraft().fontRenderer;
    }

    public int draw(PoseStack p, String s, float x, float y, int color) { return r().drawString(s, (int) x, (int) y, color); }

    public int draw(PoseStack p, IChatComponent c, float x, float y, int color) { return draw(p, c.getFormattedText(), x, y, color); }

    public int draw(PoseStack p, FormattedCharSequence c, float x, float y, int color) { return draw(p, c.text, x, y, color); }

    public int drawShadow(PoseStack p, String s, float x, float y, int color) { return r().drawStringWithShadow(s, (int) x, (int) y, color); }

    public int drawShadow(PoseStack p, IChatComponent c, float x, float y, int color) { return drawShadow(p, c.getFormattedText(), x, y, color); }

    public int drawShadow(PoseStack p, FormattedCharSequence c, float x, float y, int color) { return drawShadow(p, c.text, x, y, color); }

    public int width(String s) { return r().getStringWidth(s); }

    public int width(IChatComponent c) { return r().getStringWidth(c.getFormattedText()); }

    public int width(FormattedCharSequence c) { return r().getStringWidth(c.text); }

    @SuppressWarnings("unchecked")
    public List<FormattedCharSequence> split(IChatComponent c, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Object o : r().listFormattedStringToWidth(c.getFormattedText(), Math.max(1, width))) out.add(new FormattedCharSequence(String.valueOf(o)));
        return out;
    }

    /** 1.19.2 Font#drawWordWrap(text, x, y, width, color): wrapped text drawn at (x, y). */
    public void drawWordWrap(IChatComponent c, int x, int y, int width, int color) {
        r().drawSplitString(c.getFormattedText(), x, y, Math.max(1, width), color);
    }

    public String plainSubstrByWidth(String s, int width) { return r().trimStringToWidth(s, width); }

    public int wordWrapHeight(String s, int width) { return r().splitStringWidth(s, width); }

    /** 1.19.2 FormattedCharSequence: a formatted 1.7.10 string. */
    public static final class FormattedCharSequence {
        public static final FormattedCharSequence EMPTY = new FormattedCharSequence("");
        public final String text;

        public FormattedCharSequence(String text) { this.text = text; }

        public static FormattedCharSequence of(IChatComponent c) { return new FormattedCharSequence(c == null ? "" : c.getFormattedText()); }

        public static FormattedCharSequence forward(String s, Object style) { return new FormattedCharSequence(s); }

        public boolean accept(dev.porter.shim.mc.Chat.FormattedCharSink sink) { return dev.porter.shim.mc.Chat.accept(this, sink); }

        @Override
        public String toString() { return text; }
    }

    public int getStringWidth(String s) { return r().getStringWidth(s); }

    /** Font.substrByWidth(FormattedText, width) */
    public IChatComponent substrByWidth(IChatComponent c, int width) {
        return new net.minecraft.util.ChatComponentText(r().trimStringToWidth(c.getFormattedText(), width));
    }

    public Splitter getSplitter() { return Splitter.INSTANCE; }

    /** StringSplitter: style lookups (hover/click) are not supported for 1.7.10 strings. */
    public static final class Splitter {
        static final Splitter INSTANCE = new Splitter();

        public net.minecraft.util.ChatStyle componentStyleAtWidth(Object text, int x) { return null; }
    }
}
