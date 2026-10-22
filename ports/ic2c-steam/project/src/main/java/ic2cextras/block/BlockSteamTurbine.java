package ic2cextras.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2.core.IC2;
import ic2cextras.IC2CExtras;
import ic2cextras.tile.TileSteamTurbine;

public class BlockSteamTurbine extends BlockContainer {

    /** [active][front, back, side, up, down] */
    @SideOnly(Side.CLIENT)
    private IIcon[][] icons;

    public BlockSteamTurbine() {
        super(Material.iron);
        setBlockName(IC2CExtras.MODID + ".steamTurbine");
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setCreativeTab(IC2.tabIC2);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileSteamTurbine();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        String p = IC2CExtras.MODID + ":steam_turbine/";
        icons = new IIcon[][] {
            { reg.registerIcon(p + "inactive_front"), reg.registerIcon(p + "back"), reg.registerIcon(p + "side"),
                reg.registerIcon(p + "up"), reg.registerIcon(p + "down") },
            { reg.registerIcon(p + "active_front"), reg.registerIcon(p + "back"), reg.registerIcon(p + "side"),
                reg.registerIcon(p + "up"), reg.registerIcon(p + "down") } };
        blockIcon = icons[0][0];
    }

    @SideOnly(Side.CLIENT)
    private IIcon iconFor(int side, int facing, boolean active) {
        IIcon[] set = icons[active ? 1 : 0];
        if (side == 0) return set[4];
        if (side == 1) return set[3];
        if (side == facing) return set[0];
        if (side == (facing ^ 1)) return set[1];
        return set[2];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return iconFor(side, meta < 2 ? 3 : meta, false);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        TileEntity te = world.getTileEntity(x, y, z);
        boolean active = te instanceof TileSteamTurbine && ((TileSteamTurbine) te).active;
        int meta = world.getBlockMetadata(x, y, z);
        return iconFor(side, meta < 2 ? 3 : meta, active);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int rot = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] facing = { 2, 5, 3, 4 };
        world.setBlockMetadataWithNotify(x, y, z, facing[rot], 2);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy,
        float hz) {
        if (player.isSneaking()) return false;
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileSteamTurbine) {
            TileSteamTurbine t = (TileSteamTurbine) te;
            player.addChatMessage(
                new ChatComponentTranslation(
                    "ic2cextras.turbine.status",
                    Math.round(t.speed * 100.0F),
                    t.lastProduction,
                    t.tank.getFluidAmount(),
                    t.tank.getCapacity(),
                    t.energy,
                    TileSteamTurbine.MAX_ENERGY));
        }
        return true;
    }
}
