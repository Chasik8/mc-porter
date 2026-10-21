// porter:adapter (generic, client) - 1.19.2 KeyMapping.getKey() (InputConstants.Key) on a 1.7.10 KeyBinding.
package dev.porter.shim.mc.client;

import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public final class Keys {
    private final int code;

    private Keys(int code) { this.code = code; }

    public static Keys key(KeyBinding k) { return new Keys(k.getKeyCode()); }

    public int getValue() { return code; }

    public IChatComponent getDisplayName() { return new ChatComponentText(GameSettings.getKeyDisplayString(code)); }
}
