// porter:adapter (generic) - 1.19.2 Nameable (Component = IChatComponent on 1.7.10).
package dev.porter.shim.mc;

import net.minecraft.util.IChatComponent;

public interface Nameable {
    IChatComponent getName();

    default boolean hasCustomName() { return getCustomName() != null; }

    default IChatComponent getDisplayName() { return getName(); }

    default IChatComponent getCustomName() { return null; }
}
