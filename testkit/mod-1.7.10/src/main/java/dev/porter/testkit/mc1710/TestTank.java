package dev.porter.testkit.mc1710;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/**
 * Test fixture, not mod content: a plain 1.7.10 tank block ("portertestkit:tank", 16000 mB, fills and drains from every
 * side) that implements Forge's IFluidHandler. Scenarios use it as the source and target of fluid transport, so that a
 * port's fluid bridge is tested against an ordinary 1.7.10 handler. Registered only when a plan runs.
 */
public final class TestTank extends Block implements ITileEntityProvider {
    static void register() {
        GameRegistry.registerBlock(new TestTank().setBlockName("portertestkit.tank"), "tank");
        GameRegistry.registerTileEntity(Tile.class, "portertestkit:tank");
    }

    private TestTank() {
        super(Material.iron);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new Tile();
    }

    public static final class Tile extends TileEntity implements IFluidHandler {
        private final FluidTank tank = new FluidTank(16000);

        @Override
        public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
            return tank.fill(resource, doFill);
        }

        @Override
        public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
            FluidStack in = tank.getFluid();
            return in != null && resource != null && in.isFluidEqual(resource) ? tank.drain(resource.amount, doDrain) : null;
        }

        @Override
        public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
            return tank.drain(maxDrain, doDrain);
        }

        @Override
        public boolean canFill(ForgeDirection from, Fluid fluid) {
            return true;
        }

        @Override
        public boolean canDrain(ForgeDirection from, Fluid fluid) {
            return true;
        }

        @Override
        public FluidTankInfo[] getTankInfo(ForgeDirection from) {
            return new FluidTankInfo[] {tank.getInfo()};
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            tank.writeToNBT(tag);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            tank.readFromNBT(tag);
        }
    }
}
