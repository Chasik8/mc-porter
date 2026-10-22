package ic2cextras.tile;

import cpw.mods.fml.common.eventhandler.Event;
import ic2.api.energy.event.EnergyTileUnloadEvent;
import ic2.api.energy.event.EnergyTileLoadEvent;
import ic2.api.energy.tile.IEnergySource;
import ic2cextras.Config;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.IFluidHandler;

/**
 * Standalone steam turbine block entity. Steam goes in through the fluid API; every server tick the rotor takes up to
 * {@link #MAX_DRAIN} mB, its speed (0..1) creeps by at most {@link #SPEED_STEP} per tick, and the energy produced is
 * speed times the configured base output, collected in a {@link #MAX_ENERGY} EU buffer that is offered to the grid at
 * up to 128 EU/t (tier 2).
 */
public class TileSteamTurbine extends TileEntity implements IEnergySource, IFluidHandler {

    public static final int MAX_ENERGY = 4000;
    public static final int MAX_DRAIN = 128;
    private static final int TANK_CAPACITY = 1000;
    private static final int OFFER_LIMIT = 128;
    private static final float SPEED_STEP = 0.005F;
    private static final int GRID_TIER = 2;
    private static final boolean EMITS_EVERYWHERE = true;
    private static final FluidStack NOTHING = null;

    public final FluidTank tank = new FluidTank(TANK_CAPACITY);
    public int energy;
    public float speed;
    public int lastProduction;
    public boolean active;
    private boolean onGrid;

    // ---- fluid side: steam in, nothing out

    private static boolean isSteam(Fluid candidate) {
        return candidate != null && FluidRegistry.getFluid("steam") == candidate;
    }

    @Override
    public boolean canFill(ForgeDirection side, Fluid candidate) {
        return isSteam(candidate);
    }

    @Override
    public boolean canDrain(ForgeDirection side, Fluid candidate) {
        return false;
    }

    @Override
    public int fill(ForgeDirection side, FluidStack offered, boolean commit) {
        if (offered == null || !isSteam(offered.getFluid())) return 0;
        return tank.fill(offered, commit);
    }

    @Override
    public FluidStack drain(ForgeDirection side, FluidStack wanted, boolean commit) {
        return NOTHING;
    }

    @Override
    public FluidStack drain(ForgeDirection side, int limitMb, boolean commit) {
        return NOTHING;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection side) {
        return new FluidTankInfo[] { new FluidTankInfo(tank) };
    }

    // ---- persistence and client sync

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        NBTTagCompound steamTag = new NBTTagCompound();
        tank.writeToNBT(steamTag);
        tag.setTag("tank", steamTag);
        tag.setBoolean("active", active);
        tag.setFloat("speed", speed);
        tag.setInteger("energy", energy);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        tank.readFromNBT(tag.getCompoundTag("tank"));
        active = tag.getBoolean("active");
        speed = tag.getFloat("speed");
        energy = tag.getInteger("energy");
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setBoolean("active", active);
        int px = xCoord, py = yCoord, pz = zCoord;
        return new S35PacketUpdateTileEntity(px, py, pz, 0, payload);
    }

    @Override
    public void onDataPacket(NetworkManager manager, S35PacketUpdateTileEntity packet) {
        active = packet.func_148857_g()
            .getBoolean("active");
        int px = xCoord, py = yCoord, pz = zCoord;
        worldObj.markBlockRangeForRenderUpdate(px, py, pz, px, py, pz);
    }

    // ---- energy grid

    @Override
    public int getSourceTier() {
        return GRID_TIER;
    }

    @Override
    public boolean emitsEnergyTo(TileEntity other, ForgeDirection towards) {
        return EMITS_EVERYWHERE;
    }

    @Override
    public double getOfferedEnergy() {
        return energy < OFFER_LIMIT ? energy : OFFER_LIMIT;
    }

    @Override
    public void drawEnergy(double taken) {
        energy -= (int) taken;
        if (energy < 0) energy = 0;
    }

    private static void broadcast(Event event) {
        MinecraftForge.EVENT_BUS.post(event);
    }

    private void joinGrid() {
        if (onGrid) return;
        onGrid = true;
        broadcast(new EnergyTileLoadEvent(this));
    }

    private void leaveGrid() {
        boolean wasOn = onGrid;
        onGrid = false;
        if (wasOn && worldObj != null && !worldObj.isRemote) {
            broadcast(new EnergyTileUnloadEvent(this));
        }
    }

    @Override
    public void invalidate() {
        leaveGrid();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        leaveGrid();
        super.onChunkUnload();
    }

    // ---- simulation

    @Override
    public void updateEntity() {
        if (worldObj.isRemote) return;
        joinGrid();

        boolean noRoom = energy >= MAX_ENERGY;
        boolean nothingToDo = speed <= 0.0F && tank.getFluidAmount() <= 0;
        if (noRoom || nothingToDo) {
            slowDown();
        } else {
            spinWithSteam();
        }

        energy = lastProduction > 0 ? Math.min(MAX_ENERGY, energy + lastProduction) : energy;
        syncActive();
    }

    private void slowDown() {
        if (speed <= 0.0F) {
            lastProduction = 0;
            return;
        }
        speed = bound(speed - SPEED_STEP);
        lastProduction = outputNow();
    }

    private void spinWithSteam() {
        FluidStack burnt = tank.drain(MAX_DRAIN, true);
        int mb = burnt == null ? 0 : burnt.amount;
        float matching = Math.min((float) MAX_DRAIN, MAX_DRAIN * speed);
        float error = mb - matching;
        float change = Math.max(-SPEED_STEP, Math.min(SPEED_STEP, error));
        speed = bound(speed + change);
        lastProduction = outputNow();
    }

    private int outputNow() {
        return (int) (speed * Config.turbineBaseOutput);
    }

    private static float bound(float rotor) {
        return Math.max(0.0F, Math.min(1.0F, rotor));
    }

    private void syncActive() {
        boolean turning = speed > 0.0F;
        if (turning != active) {
            active = turning;
            worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
        }
    }
}
