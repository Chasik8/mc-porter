// porter:adapter (the mod-specific, PRIVATE) - the 1.19.2 Inventory armor/items/offhand lists as write-through views of
// the 1.7.10 InventoryPlayer arrays (armorInventory 0..3 = feet..head like 1.19.2 armor, mainInventory, no offhand).
package dev.porter.shim.mc;


import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

public final class PlayerInv {
    private PlayerInv() {}

    public static NonNullList<ItemStack> armor(InventoryPlayer inv) { return new ArrayView(inv.armorInventory); }

    public static NonNullList<ItemStack> items(InventoryPlayer inv) { return new ArrayView(inv.mainInventory); }

    /** 1.7.10 has no offhand: a detached one-slot list, writes are lost. */
    public static NonNullList<ItemStack> offhand(InventoryPlayer inv) { return new ArrayView(new ItemStack[1]); }

    private static final class ArrayView extends NonNullList<ItemStack> {
        private static final long serialVersionUID = 1L;
        private final ItemStack[] array;

        ArrayView(ItemStack[] array) {
            super(new java.util.ArrayList<ItemStack>(), null);
            this.array = array;
        }

        @Override
        public ItemStack get(int index) { return array[index]; }

        @Override
        public ItemStack set(int index, ItemStack stack) {
            ItemStack old = array[index];
            array[index] = stack;
            return old;
        }

        @Override
        public int size() { return array.length; }

        @Override
        public boolean isEmpty() { return array.length == 0; }

        @Override
        public Iterator<ItemStack> iterator() {
            return new Iterator<ItemStack>() {
                int next;

                public boolean hasNext() { return next < array.length; }

                public ItemStack next() {
                    if (next >= array.length) throw new NoSuchElementException();
                    return array[next++];
                }
            };
        }

        @Override
        public void clear() { java.util.Arrays.fill(array, null); }
    }
}
