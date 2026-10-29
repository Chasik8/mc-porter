package dev.porter.shim.gen.helpers;

import dev.porter.shim.gen.network.chat.FormattedText;
import dev.porter.shim.gen.util.FormattedCharSequence;
import dev.porter.shim.gen.util.FormattedCharSink;
import net.minecraft.util.ChatStyle;

/** Text conversions between the generated 1.19.2 text types and the plain (formatted) strings 1.7.10 draws. */
public final class DdText {
    private DdText() {
    }

    /** A character sequence over a plain string. */
    public static final class Seq implements FormattedCharSequence {
        public final String text;

        Seq(String text) {
            this.text = text;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            for (int i = 0; i < text.length(); i++) {
                if (!sink.accept(i, null, text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A formatted text over a plain string. */
    public static final class Txt implements FormattedText {
        public final String text;

        Txt(String text) {
            this.text = text;
        }

        @Override
        public String getString() {
            return text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public static FormattedCharSequence seq(String s) {
        return new Seq(s == null ? "" : s);
    }

    public static FormattedText formatted(String s) {
        return new Txt(s == null ? "" : s);
    }

    /** The plain string of a String, FormattedText or FormattedCharSequence. */
    public static String text(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Seq) {
            return ((Seq) o).text;
        }
        if (o instanceof FormattedText) {
            String s = ((FormattedText) o).getString();
            return s == null ? "" : s;
        }
        if (o instanceof FormattedCharSequence) {
            final StringBuilder sb = new StringBuilder();
            ((FormattedCharSequence) o).accept(new FormattedCharSink() {
                @Override
                public boolean accept(int index, ChatStyle style, int codePoint) {
                    sb.appendCodePoint(codePoint);
                    return true;
                }
            });
            return sb.toString();
        }
        return String.valueOf(o);
    }
}
