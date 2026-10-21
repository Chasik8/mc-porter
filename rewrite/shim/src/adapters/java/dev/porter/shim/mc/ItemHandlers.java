// porter:adapter (generic) - Forge 1.19.2 ItemHandlerHelper / EmptyHandler / InvWrapper / SidedInvWrapper on the
// 1.7.10 IInventory / ISidedInventory.
package dev.porter.shim.mc;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;

public final class ItemHandlers {
    private ItemHandlers() {}

    public static boolean isEmpty(ItemStack s) { return s == null || s.stackSize <= 0; }

    /** ItemHandlerHelper.canItemStacksStack */
    public static boolean canItemStacksStack(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b) || a.getItem() != b.getItem()) return false;
        if (!a.isStackable()) return false;
        if (a.getHasSubtypes() && a.getItemDamage() != b.getItemDamage()) return false;
        if (a.hasTagCompound() != b.hasTagCompound()) return false;
        return !a.hasTagCompound() || a.getTagCompound().equals(b.getTagCompound());
    }

    /** ItemHandlerHelper.copyStackWithSize */
    public static ItemStack copyStackWithSize(ItemStack s, int size) {
        if (size <= 0 || isEmpty(s)) return null;
        ItemStack c = s.copy();
        c.stackSize = size;
        return c;
    }

    /** ItemHandlerHelper.insertItem(handler, stack, simulate) */
    public static ItemStack insertItem(IItemHandler h, ItemStack stack, boolean simulate) {
        if (h == null || isEmpty(stack)) return stack;
        for (int i = 0; i < h.getSlots(); i++) {
            stack = h.insertItem(i, stack, simulate);
            if (isEmpty(stack)) return null;
        }
        return stack;
    }

    /** EmptyHandler.INSTANCE */
    public static final IItemHandlerModifiable EMPTY = new IItemHandlerModifiable() {
        public int getSlots() { return 0; }
        public ItemStack getStackInSlot(int slot) { return null; }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return null; }
        public int getSlotLimit(int slot) { return 0; }
        public boolean isItemValid(int slot, ItemStack stack) { return false; }
        public void setStackInSlot(int slot, ItemStack stack) {}
    };

    /** InvWrapper / SidedInvWrapper: an IInventory (optionally sided) as IItemHandlerModifiable. */
    public static IItemHandlerModifiable wrap(IInventory inv, int side) {
        return new InvWrapper(inv, side);
    }

    static final class InvWrapper implements IItemHandlerModifiable {
        final IInventory inv;
        final int side;
        final int[] slots;

        InvWrapper(IInventory inv, int side) {
            this.inv = inv;
            this.side = side;
            this.slots = inv instanceof ISidedInventory && side >= 0 ? ((ISidedInventory) inv).getAccessibleSlotsFromSide(side) : null;
        }

        int slot(int i) { return slots == null ? i : slots[i]; }

        public int getSlots() { return slots == null ? inv.getSizeInventory() : slots.length; }

        public ItemStack getStackInSlot(int i) { return inv.getStackInSlot(slot(i)); }

        public int getSlotLimit(int i) { return inv.getInventoryStackLimit(); }

        public boolean isItemValid(int i, ItemStack s) {
            int sl = slot(i);
            if (!inv.isItemValidForSlot(sl, s)) return false;
            return !(inv instanceof ISidedInventory && side >= 0) || ((ISidedInventory) inv).canInsertItem(sl, s, side);
        }

        public void setStackInSlot(int i, ItemStack s) {
            inv.setInventorySlotContents(slot(i), s);
            inv.markDirty();
        }

        public ItemStack insertItem(int i, ItemStack stack, boolean simulate) {
            if (isEmpty(stack) || !isItemValid(i, stack)) return stack;
            int sl = slot(i);
            ItemStack cur = inv.getStackInSlot(sl);
            int limit = Math.min(getSlotLimit(i), stack.getMaxStackSize());
            if (isEmpty(cur)) {
                int n = Math.min(limit, stack.stackSize);
                if (!simulate) {
                    inv.setInventorySlotContents(sl, copyStackWithSize(stack, n));
                    inv.markDirty();
                }
                return copyStackWithSize(stack, stack.stackSize - n);
            }
            if (!canItemStacksStack(cur, stack)) return stack;
            int n = Math.min(limit - cur.stackSize, stack.stackSize);
            if (n <= 0) return stack;
            if (!simulate) {
                ItemStack c = cur.copy();
                c.stackSize += n;
                inv.setInventorySlotContents(sl, c);
                inv.markDirty();
            }
            return copyStackWithSize(stack, stack.stackSize - n);
        }

        public ItemStack extractItem(int i, int amount, boolean simulate) {
            if (amount <= 0) return null;
            int sl = slot(i);
            ItemStack cur = inv.getStackInSlot(sl);
            if (isEmpty(cur)) return null;
            if (inv instanceof ISidedInventory && side >= 0 && !((ISidedInventory) inv).canExtractItem(sl, cur, side)) return null;
            int n = Math.min(amount, cur.stackSize);
            if (!simulate) {
                inv.decrStackSize(sl, n);
                inv.markDirty();
            }
            return copyStackWithSize(cur, n);
        }
    }
}
