// porter:adapter (generic, no mod content) - the 1.19.2 BlockEntity API on a 1.7.10 TileEntity. The root block
// entity of a ported mod extends this class (its "extends BlockEntity" is redirected here). It provides:
//  - the 1.19.2 constructor shape (type, pos, state) next to the no-arg constructor 1.7.10 needs,
//  - the block's 1.19.2 ticker driven from updateEntity(), onLoad() on the first tick, onChunkUnloaded(),
//  - update tag <-> description packet, the full block state for blocks with more than 16 states (IStateHolder),
//  - capabilities: getCapability(...) answered by the ported code, exposed to 1.7.10 as IFluidHandler and
//    ISidedInventory (what other 1.7.10 mods, vanilla hoppers and the porter testkit use).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

public abstract class ModernTileEntity extends TileEntity
        implements ICapabilityProvider, Levels.IStateHolder, net.minecraftforge.fluids.IFluidHandler, ISidedInventory {
    private final BlockEntityType<?> porterType;
    private BlockState porterState;
    private boolean porterLoaded;
    public boolean porterTicking = true;
    @SuppressWarnings("rawtypes")
    private BlockEntityTicker porterTicker;
    private boolean porterTickerResolved;

    protected ModernTileEntity() { this(null, null, null); }

    protected ModernTileEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        this.porterType = type;
        this.porterState = state;
        if (pos != null) {
            this.xCoord = pos.getX();
            this.yCoord = pos.getY();
            this.zCoord = pos.getZ();
        }
    }

    public BlockEntityType<?> porterType() { return porterType; }

    /** 1.19.2 BlockEntity.worldPosition (kept in sync with xCoord/yCoord/zCoord by validate/readFromNBT/getBlockPos). */
    protected BlockPos worldPosition = new BlockPos(0, 0, 0);

    public BlockPos getBlockPos() {
        if (worldPosition.getX() != xCoord || worldPosition.getY() != yCoord || worldPosition.getZ() != zCoord) {
            worldPosition = new BlockPos(xCoord, yCoord, zCoord);
        }
        return worldPosition;
    }

    // ------------------------------------------------------------------ 1.19.2 BlockEntity API

    public void onLoad() {}

    public void onChunkUnloaded() {}

    @Override
    public void onChunkUnload() { onChunkUnloaded(); }

    /** 1.19.2 default: an empty tag; mods add their client data. */
    public NBTTagCompound getUpdateTag() { return new NBTTagCompound(); }

    /** 1.19.2 default: load(tag). */
    public void handleUpdateTag(NBTTagCompound tag) { readFromNBT(tag); }

    public void requestModelDataUpdate() {}

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) { return LazyOptional.empty(); }

    public void invalidateCaps() {}

    public void reviveCaps() {}

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = getUpdateTag();
        return tag == null ? null : new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) { handleUpdateTag(pkt.func_148857_g()); }

    // ------------------------------------------------------------------ ticking

    @Override
    public boolean canUpdate() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public void updateEntity() {
        if (worldObj == null) return;
        if (!porterLoaded) {
            porterLoaded = true;
            onLoad();
        }
        if (!porterTicking) return;
        if (!porterTickerResolved) {
            porterTickerResolved = true;
            Block b = getBlockType();
            if (b instanceof StateEntityBlock) {
                porterTicker = ((StateEntityBlock) b).getTicker(worldObj, Levels.getBlockState(this), (BlockEntityType) porterType);
            } else if (b instanceof EntityBlock) { // interface form
                porterTicker = ((EntityBlock) b).getTicker(worldObj, Levels.getBlockState(this), (BlockEntityType) porterType);
            }
            if (porterTicker == null) porterTicker = porterFallbackTicker();
        }
        if (porterTicker != null) porterTicker.tick(worldObj, new BlockPos(xCoord, yCoord, zCoord), Levels.getBlockState(this), this);
    }

    /**
     * Ticker used when the tile's block supplies none (a block generated from the IR is a plain Block, not an
     * EntityBlock). A ported mod overrides this with the ticker its own blocks return.
     */
    @SuppressWarnings("rawtypes")
    protected BlockEntityTicker porterFallbackTicker() { return null; }

    @Override
    public void validate() {
        super.validate();
        porterTickerResolved = false;
        getBlockPos();
    }

    // ------------------------------------------------------------------ full block state (> 16 states)

    @Override
    public BlockState porterGetState() { return porterState; }

    @Override
    public void porterSetState(BlockState s) {
        this.porterState = s;
        markDirty();
    }

    /** subclasses call super.readFromNBT/writeToNBT; the full state rides along under "porter:state". */
    @Override
    public void readFromNBT(NBTTagCompound tag) {
        int x = xCoord, y = yCoord, z = zCoord;
        super.readFromNBT(tag);
        if (!tag.hasKey("x")) { // a 1.19.2 update tag (handleUpdateTag -> load) carries no position: keep ours
            xCoord = x;
            yCoord = y;
            zCoord = z;
        }
        getBlockPos();
        if (tag.hasKey("ForgeData", 10)) TileData.set(this, tag.getCompoundTag("ForgeData"));
        if (tag.hasKey("porter:state") && getBlockType() instanceof IStateBlock) {
            StateDefinition<?, ?> d = ((IStateBlock) getBlockType()).getStateDefinition();
            int i = tag.getInteger("porter:state");
            if (i >= 0 && i < d.states.length) porterState = d.states[i];
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (porterState != null && porterState.def != null) tag.setInteger("porter:state", porterState.index);
        net.minecraft.nbt.NBTTagCompound data = TileData.peek(this);
        if (data != null && !data.hasNoTags()) tag.setTag("ForgeData", data.copy());
    }

    /** Forge IForgeBlockEntity.getTileData()/getPersistentData(): the per-tile compound saved under "ForgeData". */
    public NBTTagCompound getTileData() {
        return TileData.get(this);
    }

    // ------------------------------------------------------------------ 1.7.10 IFluidHandler <- FLUID_HANDLER capability

    private IFluidHandler fluids(ForgeDirection from) {
        Direction d = from == null || from == ForgeDirection.UNKNOWN ? null : Direction.from3DDataValue(from.ordinal());
        LazyOptional<IFluidHandler> o = getCapability(ForgeCapabilities.FLUID_HANDLER, d);
        if (!o.isPresent() && this instanceof IFluidHandler) return (IFluidHandler) this;
        return o.orElse(null);
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        IFluidHandler h = fluids(from);
        return h == null ? 0 : h.fill(resource, doFill);
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        IFluidHandler h = fluids(from);
        return h == null ? null : h.drain(resource, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        IFluidHandler h = fluids(from);
        return h == null ? null : h.drain(maxDrain, doDrain);
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        IFluidHandler h = fluids(from);
        return h != null && fluid != null && h.fill(new FluidStack(fluid, 1), false) > 0;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        IFluidHandler h = fluids(from);
        if (h == null) return false;
        FluidStack s = fluid == null ? h.drain(1, false) : h.drain(new FluidStack(fluid, 1), false);
        return s != null && s.amount > 0;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        IFluidHandler h = fluids(from);
        if (h == null) return new FluidTankInfo[0];
        FluidTankInfo[] out = new FluidTankInfo[h.getTanks()];
        for (int i = 0; i < out.length; i++) {
            FluidStack f = h.getFluidInTank(i);
            out[i] = new FluidTankInfo(f == null ? null : f.copy(), h.getTankCapacity(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ 1.7.10 ISidedInventory <- ITEM_HANDLER capability

    private IItemHandler items() {
        return getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(ItemHandlers.EMPTY);
    }

    /**
     * Raw slot access (1.7.10 IInventory semantics: no filters, like a 1.19.2 Container). A mod's own inventory
     * interface bridges to it (default methods); otherwise the unsided ITEM_HANDLER capability is used.
     */
    public interface SlotAccess {
        int porterSlotCount();

        ItemStack porterGetSlot(int slot);

        void porterSetSlot(int slot, ItemStack stack);
    }

    @Override
    public int getSizeInventory() { return this instanceof SlotAccess ? ((SlotAccess) this).porterSlotCount() : items().getSlots(); }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= getSizeInventory()) return null;
        return this instanceof SlotAccess ? ((SlotAccess) this).porterGetSlot(slot) : items().getStackInSlot(slot);
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (!(this instanceof SlotAccess)) return items().extractItem(slot, amount, false);
        ItemStack cur = getStackInSlot(slot);
        if (cur == null || amount <= 0) return null;
        ItemStack out = cur.splitStack(Math.min(amount, cur.stackSize));
        setInventorySlotContents(slot, cur.stackSize <= 0 ? null : cur);
        return out;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) { return null; }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (this instanceof SlotAccess) {
            ((SlotAccess) this).porterSetSlot(slot, stack);
            markDirty();
            return;
        }
        IItemHandler h = items();
        if (h instanceof IItemHandlerModifiable) {
            ((IItemHandlerModifiable) h).setStackInSlot(slot, stack);
        } else {
            ItemStack cur = h.getStackInSlot(slot);
            if (cur != null) h.extractItem(slot, cur.stackSize, false);
            if (stack != null) h.insertItem(slot, stack, false);
        }
        markDirty();
    }

    @Override
    public String getInventoryName() { return getBlockType() == null ? "container" : getBlockType().getUnlocalizedName(); }

    @Override
    public boolean hasCustomInventoryName() { return false; }

    @Override
    public int getInventoryStackLimit() { return 64; }

    @Override
    public boolean isUseableByPlayer(EntityPlayer p) {
        return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this && p.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64.0D;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) { return items().isItemValid(slot, stack); }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        int n = getSizeInventory();
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = i;
        return s;
    }

    /** sided access is approximated by the unsided handler's validity check (slot indices of side views differ). */
    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) { return isItemValidForSlot(slot, stack); }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) { return true; }
}
