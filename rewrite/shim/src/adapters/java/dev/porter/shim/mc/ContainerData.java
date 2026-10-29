// porter:adapter (the mod-specific, PRIVATE) - net.minecraft.world.inventory.ContainerData: an int array view shared by a menu and
// its block entity. 1.7.10 containers have no data slots; the holder stays server-side (see r-more-dpipe-pattern-adddataslots).
package dev.porter.shim.mc;

public interface ContainerData {
    int get(int index);

    void set(int index, int value);

    int getCount();
}
