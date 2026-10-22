// porter:adapter (IC2C-specific) - IC2.KEYBOARD (server view: no keys pressed; names = translation keys).
package ic2cextras.ported.compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

public class Keyboard {
    public boolean isAltKeyDown(EntityPlayer p) { return false; }

    public boolean isBoostKeyDown(EntityPlayer p) { return false; }

    public boolean isModeSwitchKeyDown(EntityPlayer p) { return false; }

    public boolean isSideInventoryKeyDown(EntityPlayer p) { return false; }

    public boolean isHudModeKeyDown(EntityPlayer p) { return false; }

    public boolean isSneakKeyDown(EntityPlayer p) { return p != null && p.isSneaking(); }

    public IChatComponent getKeyName(Object key) { return new ChatComponentTranslation(String.valueOf(key)); }
}
