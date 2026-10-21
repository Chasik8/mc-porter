// porter:adapter (generic) - Forge 1.19.2 INBTSerializable<T> (T: a 1.7.10 NBT type after the rewrite).
package dev.porter.shim.mc;

public interface INBTSerializable<T> {
    T serializeNBT();

    void deserializeNBT(T nbt);
}
