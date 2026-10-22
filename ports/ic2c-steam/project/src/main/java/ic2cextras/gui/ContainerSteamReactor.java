package ic2cextras.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import ic2.api.reactor.ISteamReactor;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;

/**
 * Steam reactor container laid out like the 1.19.2 one (reactor grid, water/steam gauges, heat bar, steam output).
 * Ints are synced as two 16-bit progress bar values, since 1.7.10 sends progress bars as shorts.
 */
public class ContainerSteamReactor extends Container {

    private static final int WATER = 0, STEAM = 1, HEAT = 2, MAX_HEAT = 3, OUTPUT = 4, FIELDS = 5;

    public final TileEntityNuclearReactor reactor;
    public final int size;
    /** Client-side copies of the synced values. */
    public final int[] values = new int[FIELDS];
    private final int[] lastSent = new int[FIELDS];

    public ContainerSteamReactor(InventoryPlayer playerInv, TileEntityNuclearReactor reactor) {
        this.reactor = reactor;
        this.size = reactor.getReactorSize();
        int startX = 89 - 9 * size;
        for (int i = 0; i < 54; i++) {
            int x = i % 9;
            if (x >= size) continue;
            addSlotToContainer(new Slot(reactor, i, startX + 18 * x, 18 + 18 * (i / 9)));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 156 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(playerInv, col, 8 + col * 18, 214));
        }
        java.util.Arrays.fill(lastSent, Integer.MIN_VALUE);
    }

    private int[] current() {
        ISteamReactor r = (ISteamReactor) reactor;
        return new int[] { r.getWaterTank()
            .getFluidAmount(),
            r.getSteamTank()
                .getFluidAmount(),
            reactor.getHeat(), reactor.getMaxHeat(), (int) Math.round(reactor.output * 3.2) };
    }

    @Override
    public void addCraftingToCrafters(ICrafting crafter) {
        super.addCraftingToCrafters(crafter);
        int[] now = current();
        for (int i = 0; i < FIELDS; i++) sendInt(crafter, i, now[i]);
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int[] now = current();
        for (int i = 0; i < FIELDS; i++) {
            if (now[i] == lastSent[i]) continue;
            for (Object o : crafters) sendInt((ICrafting) o, i, now[i]);
            lastSent[i] = now[i];
        }
    }

    private void sendInt(ICrafting crafter, int field, int value) {
        crafter.sendProgressBarUpdate(this, field * 2, value & 0xFFFF);
        crafter.sendProgressBarUpdate(this, field * 2 + 1, value >>> 16);
    }

    @Override
    public void updateProgressBar(int id, int value) {
        int field = id / 2;
        if (field >= FIELDS) return;
        if (id % 2 == 0) values[field] = (values[field] & 0xFFFF0000) | (value & 0xFFFF);
        else values[field] = (values[field] & 0xFFFF) | ((value & 0xFFFF) << 16);
    }

    public int water() {
        return values[WATER];
    }

    public int steam() {
        return values[STEAM];
    }

    public int heat() {
        return values[HEAT];
    }

    public int maxHeat() {
        return values[MAX_HEAT];
    }

    public int output() {
        return values[OUTPUT];
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return !reactor.isInvalid()
            && player.getDistanceSq(reactor.xCoord + 0.5, reactor.yCoord + 0.5, reactor.zCoord + 0.5) <= 64.0;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) return null;
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        int reactorSlots = 6 * size;
        if (index < reactorSlots) {
            if (!mergeItemStack(stack, reactorSlots, inventorySlots.size(), true)) return null;
        } else {
            // mergeItemStack ignores slot stack limits, reactor components go one per slot
            if (!reactor.isUsefulItem(stack)) return null;
            boolean moved = false;
            for (int i = 0; i < reactorSlots && stack.stackSize > 0; i++) {
                Slot target = (Slot) inventorySlots.get(i);
                if (target.getHasStack()) continue;
                target.putStack(stack.splitStack(1));
                moved = true;
            }
            if (!moved) return null;
        }
        if (stack.stackSize == 0) slot.putStack(null);
        else slot.onSlotChanged();
        return copy;
    }
}
