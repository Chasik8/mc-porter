// porter:adapter (generic) - 1.19.2 chat helpers on IChatComponent: withStyle(ChatFormatting...), property names,
// NBT pretty printing (TextComponentTagVisitor).
package dev.porter.shim.mc;

import net.minecraft.nbt.NBTBase;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

public final class Chat {
    private Chat() {}

    public static IChatComponent withStyle(IChatComponent c, EnumChatFormatting... f) {
        ChatStyle s = c.getChatStyle();
        for (EnumChatFormatting e : f) {
            if (e.isColor()) s.setColor(e);
            else if (e == EnumChatFormatting.BOLD) s.setBold(true);
            else if (e == EnumChatFormatting.ITALIC) s.setItalic(true);
            else if (e == EnumChatFormatting.UNDERLINE) s.setUnderlined(true);
            else if (e == EnumChatFormatting.STRIKETHROUGH) s.setStrikethrough(true);
            else if (e == EnumChatFormatting.OBFUSCATED) s.setObfuscated(true);
        }
        return c;
    }

    /** Style.applyFormat: a copy of the style with the formatting applied (1.7.10 styles are mutable). */
    public static ChatStyle applyFormat(ChatStyle style, EnumChatFormatting f) {
        ChatStyle s = style == null ? new ChatStyle() : style.createShallowCopy();
        withStyle(new ChatComponentText("").setChatStyle(s), f);
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String propertyName(Property p, Object v) { return p.getName((Comparable) v); }

    /** TextComponentTagVisitor(indent, depth).visit(tag) -> the tag as text. */
    public static final class TagVisitor {
        public TagVisitor(String indent, int depth) {}

        public IChatComponent visit(NBTBase tag) { return new ChatComponentText(String.valueOf(tag)); }
    }

    /** TextColor.fromRgb: nearest 1.7.10 chat colour. */
    public static EnumChatFormatting nearest(int rgb) {
        int[] cols = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55,
            0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        int best = 15;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < 16; i++) {
            int dr = (cols[i] >> 16 & 255) - (rgb >> 16 & 255), dg = (cols[i] >> 8 & 255) - (rgb >> 8 & 255), db = (cols[i] & 255) - (rgb & 255);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return EnumChatFormatting.values()[best];
    }

    /** 1.19.2 FormattedCharSink */
    public interface FormattedCharSink {
        boolean accept(int index, ChatStyle style, int codePoint);
    }

    /** FormattedCharSequence.accept(sink): the characters of a formatted 1.7.10 string with their colour style. */
    public static boolean accept(dev.porter.shim.mc.client.Font.FormattedCharSequence q, FormattedCharSink sink) {
        String t = q.text;
        ChatStyle style = new ChatStyle();
        int idx = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '§' && i + 1 < t.length()) {
                EnumChatFormatting f = byCode(t.charAt(++i));
                if (f == null || f == EnumChatFormatting.RESET) style = new ChatStyle();
                else if (f.isColor()) style = new ChatStyle().setColor(f);
                else withStyle(new ChatComponentText("").setChatStyle(style), f);
                continue;
            }
            if (!sink.accept(idx++, style, c)) return false;
        }
        return true;
    }

    static EnumChatFormatting byCode(char c) {
        for (EnumChatFormatting f : EnumChatFormatting.values()) if (f.toString().charAt(1) == Character.toLowerCase(c)) return f;
        return null;
    }
}
