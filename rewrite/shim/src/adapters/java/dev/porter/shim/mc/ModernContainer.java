// porter:adapter (generic) - 1.19.2 AbstractContainerMenu API on a 1.7.10 Container. slots/lastSlots ARE the
// 1.7.10 inventorySlots/inventoryItemStacks lists (same objects), so vanilla sync keeps working.
package dev.porter.shim.mc;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

public abstract class ModernContainer extends Container {
    public final NonNullList<Slot> slots;
    public final NonNullList<ItemStack> lastSlots;
    public final int containerId;
    protected EntityPlayer porterPlayer;

    protected ModernContainer(Object menuType, int containerId) {
        this.slots = NonNullList.create();
        this.lastSlots = NonNullList.create();
        this.inventorySlots = slots;
        this.inventoryItemStacks = lastSlots;
        this.containerId = containerId;
        this.windowId = containerId;
    }

    protected Slot addSlot(Slot s) { return addSlotToContainer(s); }

    public void broadcastChanges() { detectAndSendChanges(); }

    /** 1.19.2 stillValid(Player) */
    public boolean stillValid(EntityPlayer p) { return true; }

    @Override
    public boolean canInteractWith(EntityPlayer p) { return stillValid(p); }

    /** 1.19.2 quickMoveStack(Player, int) */
    public ItemStack quickMoveStack(EntityPlayer p, int index) { return null; }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) { return quickMoveStack(p, index); }

    /** 1.19.2 removed(Player) */
    public void removed(EntityPlayer p) {}

    @Override
    public void onContainerClosed(EntityPlayer p) {
        super.onContainerClosed(p);
        removed(p);
    }

    /** the player this menu belongs to (1.19.2 menus know their inventory owner; FML containers do not). */
    public void bindPlayer(EntityPlayer p) { this.porterPlayer = p; }

    /** the stack carried by the mouse (1.19.2 menu carried item) of the player using this menu. */
    public ItemStack getCarried() { return porterPlayer == null ? null : porterPlayer.inventory.getItemStack(); }

    public void setCarried(ItemStack s) { if (porterPlayer != null) porterPlayer.inventory.setItemStack(s); }

    @Override
    public void addCraftingToCrafters(net.minecraft.inventory.ICrafting c) {
        if (c instanceof EntityPlayer) porterPlayer = (EntityPlayer) c;
        super.addCraftingToCrafters(c);
    }
}
