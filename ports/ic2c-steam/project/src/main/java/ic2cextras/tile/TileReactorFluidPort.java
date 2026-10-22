package ic2cextras.tile;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import ic2.api.reactor.ISteamReactor;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2cextras.Config;

/**
 * Sits next to a steam reactor or steam chamber. Pushes the reactor's steam into every neighbouring fluid handler and
 * pulls water from them, like the 1.19.2 reactor does with its pipe network. It is also a fluid handler itself, so
 * pipes can insert water and extract steam.
 */
public class TileReactorFluidPort extends TileEntity implements IFluidHandler {

    public TileEntityNuclearReactor getReactor() {
        return worldObj == null ? null : ReactorHelper.findAdjacent(worldObj, xCoord, yCoord, zCoord);
    }

    @Override
    public void updateEntity() {
        if (worldObj.isRemote) return;
        TileEntityNuclearReactor reactor = getReactor();
        if (reactor == null) return;
        ISteamReactor steamReactor = (ISteamReactor) reactor;
        FluidTank steamTank = steamReactor.getSteamTank();
        FluidTank waterTank = steamReactor.getWaterTank();
        int steamBudget = Math.min(steamTank.getFluidAmount(), Config.portSteamPerTick);

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity te = worldObj.getTileEntity(xCoord + dir.offsetX, yCoord + dir.offsetY, zCoord + dir.offsetZ);
            if (!(te instanceof IFluidHandler) || ReactorHelper.isReactorPart(te) || te instanceof TileReactorFluidPort)
                continue;
            IFluidHandler handler = (IFluidHandler) te;
            ForgeDirection from = dir.getOpposite();

            if (steamBudget > 0 && steamTank.getFluid() != null) {
                FluidStack offer = new FluidStack(steamTank.getFluid(), steamBudget);
                int accepted = handler.fill(from, offer, true);
                if (accepted > 0) {
                    steamTank.drain(accepted, true);
                    steamBudget -= accepted;
                }
            }

            int space = waterTank.getCapacity() - waterTank.getFluidAmount();
            if (space > 0 && Config.portWaterPerTick > 0) {
                FluidStack want = new FluidStack(FluidRegistry.WATER, Math.min(space, Config.portWaterPerTick));
                FluidStack got = handler.drain(from, want, false);
                if (got != null && got.getFluid() == FluidRegistry.WATER && got.amount > 0) {
                    got = handler.drain(from, new FluidStack(FluidRegistry.WATER, got.amount), true);
                    if (got != null && got.amount > 0) waterTank.fill(got, true);
                }
            }
        }
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        TileEntityNuclearReactor reactor = getReactor();
        if (reactor == null || resource == null || resource.getFluid() != FluidRegistry.WATER) return 0;
        return ((ISteamReactor) reactor).getWaterTank()
            .fill(resource, doFill);
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (resource == null || !resource.isFluidEqual(getSteam())) return null;
        return drain(from, resource.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        TileEntityNuclearReactor reactor = getReactor();
        if (reactor == null) return null;
        return ((ISteamReactor) reactor).getSteamTank()
            .drain(maxDrain, doDrain);
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return fluid == FluidRegistry.WATER && getReactor() != null;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return getReactor() != null && (fluid == null || fluid == FluidRegistry.getFluid("steam"));
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        TileEntityNuclearReactor reactor = getReactor();
        if (reactor == null) return new FluidTankInfo[0];
        ISteamReactor r = (ISteamReactor) reactor;
        return new FluidTankInfo[] { r.getWaterTank()
            .getInfo(),
            r.getSteamTank()
                .getInfo() };
    }

    private static FluidStack getSteam() {
        Fluid steam = FluidRegistry.getFluid("steam");
        return steam == null ? null : new FluidStack(steam, 1);
    }
}
