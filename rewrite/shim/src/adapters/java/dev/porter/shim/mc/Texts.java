// porter:adapter (the mod-specific, PRIVATE) - IChatComponent#withStyle: 1.7.10 sets the style in place (setChatStyle).
package dev.porter.shim.mc;

import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

public final class Texts {
    private Texts() {}

    public static IChatComponent withStyle(IChatComponent component, ChatStyle style) {
        component.setChatStyle(style);
        return component;
    }
}
