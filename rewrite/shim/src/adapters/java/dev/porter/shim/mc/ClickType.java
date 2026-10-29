// porter:adapter (the mod-specific, PRIVATE) - 1.19.2 net.minecraft.world.inventory.ClickType on 1.7.10, where a click is the
// Container#slotClick(slot, button, mode, player) integer triple: this enum is the 'mode' (see of()).
package dev.porter.shim.mc;

public enum ClickType {
    PICKUP(0), QUICK_MOVE(1), SWAP(2), CLONE(3), THROW(4), QUICK_CRAFT(5), PICKUP_ALL(6);

    /** the 1.7.10 slotClick mode this click type is sent as */
    public final int mode;

    ClickType(int mode) { this.mode = mode; }

    /** The click type of a 1.7.10 slotClick(slot, button, mode, player); unknown modes read as PICKUP. */
    public static ClickType of(int mode) {
        for (ClickType t : values()) {
            if (t.mode == mode) return t;
        }
        return PICKUP;
    }
}
