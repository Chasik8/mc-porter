// porter:helper (generic) - generated Component shell <-> 1.7.10 IChatComponent.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.gen.network.chat.MutableComponent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

public final class Comp {
    private Comp() {}

    /** a generated component that carries the 1.7.10 component. */
    public static MutableComponent wrap(IChatComponent c) {
        MutableComponent m = new MutableComponent() { };
        GenAttach.put(m, "icc", c);
        return m;
    }

    /** the 1.7.10 component behind a generated component (plain text of its toString when it carries none). */
    public static IChatComponent unwrap(Object c) {
        if (c == null) {
            return new ChatComponentText("");
        }
        if (c instanceof IChatComponent) {
            return (IChatComponent) c;
        }
        Object v = GenAttach.get(c, "icc");
        if (v instanceof IChatComponent) {
            return (IChatComponent) v;
        }
        return new ChatComponentText(String.valueOf(c));
    }

    public static String text(Object c) {
        return unwrap(c).getUnformattedText();
    }

    public static MutableComponent literal(String s) {
        return wrap(new ChatComponentText(s == null ? "" : s));
    }

    /** translation key with arguments; generated components in the arguments become 1.7.10 components. */
    public static MutableComponent translatable(String key, Object... args) {
        Object[] a = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < a.length; i++) {
            Object o = args[i];
            a[i] = (o instanceof String || o instanceof Number || o instanceof IChatComponent) ? o
                    : (GenAttach.get(o, "icc") != null ? GenAttach.get(o, "icc") : String.valueOf(o));
        }
        return wrap(new ChatComponentTranslation(key, a));
    }
}
