package ic2cextras.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2.api.reactor.ISteamReactor;
import ic2.core.IC2;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2cextras.IC2CExtras;
import ic2cextras.tile.TileReactorFluidPort;

public class BlockReactorFluidPort extends BlockContainer {

    public BlockReactorFluidPort() {
        super(Material.iron);
        setBlockName(IC2CExtras.MODID + ".reactorFluidPort");
        setHardness(5.0F);
        setResistance(30.0F);
        setStepSound(soundTypeMetal);
        setCreativeTab(IC2.tabIC2);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileReactorFluidPort();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        blockIcon = reg.registerIcon(IC2CExtras.MODID + ":reactor_fluid_port");
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy,
        float hz) {
        if (player.isSneaking()) return false;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileReactorFluidPort)) return false;
        TileEntityNuclearReactor reactor = ((TileReactorFluidPort) te).getReactor();
        if (world.isRemote) return true;
        if (reactor == null) {
            player.addChatMessage(new ChatComponentTranslation("ic2cextras.port.noReactor"));
            return true;
        }
        if (tryPourWater(player, (ISteamReactor) reactor)) return true;
        player.openGui(
            IC2CExtras.instance,
            IC2CExtras.GUI_STEAM_REACTOR,
            world,
            reactor.xCoord,
            reactor.yCoord,
            reactor.zCoord);
        return true;
    }

    /** Empties a water bucket / water cell held by the player into the reactor. */
    static boolean tryPourWater(EntityPlayer player, ISteamReactor reactor) {
        ItemStack held = player.getCurrentEquippedItem();
        if (held == null) return false;
        FluidStack fluid = FluidContainerRegistry.getFluidForFilledItem(held);
        if (fluid == null || fluid.getFluid() != FluidRegistry.WATER) return false;
        FluidTank water = reactor.getWaterTank();
        if (water.fill(fluid, false) < fluid.amount) return true;
        water.fill(fluid, true);
        if (!player.capabilities.isCreativeMode) {
            ItemStack empty = FluidContainerRegistry.drainFluidContainer(held);
            held.stackSize--;
            if (empty != null && !player.inventory.addItemStackToInventory(empty)) {
                player.dropPlayerItemWithRandomChoice(empty, false);
            }
            if (held.stackSize <= 0) player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            player.inventoryContainer.detectAndSendChanges();
        }
        return true;
    }
}
