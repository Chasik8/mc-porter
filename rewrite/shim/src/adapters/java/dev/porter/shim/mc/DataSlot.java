// porter:adapter (the mod-specific, PRIVATE) - net.minecraft.world.inventory.DataSlot on 1.7.10: a plain int holder
// (1.7.10 containers sync ints through updateProgressBar; the Industrial Workbench "saving" flag is server-side only here).
package dev.porter.shim.mc;

public abstract class DataSlot {
    private int value;

    public static DataSlot standalone() {
        return new DataSlot() {};
    }

    public int get() {
        return value;
    }

    public void set(int value) {
        this.value = value;
    }
}
