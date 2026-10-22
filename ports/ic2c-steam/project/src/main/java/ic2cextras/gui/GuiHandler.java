package ic2cextras.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2cextras.IC2CExtras;
import ic2cextras.ported.gen.Ic2cextrasGuiHandler;
import ic2cextras.tile.ReactorHelper;

public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (Ic2cextrasGuiHandler.handles(id))
            return Ic2cextrasGuiHandler.INSTANCE.getServerGuiElement(id, player, world, x, y, z);
        if (ic2cextras.ported.steam.gen.Ic2cextrasGuiHandler.handles(id))
            return ic2cextras.ported.steam.gen.Ic2cextrasGuiHandler.INSTANCE
                .getServerGuiElement(id, player, world, x, y, z);
        if (id == IC2CExtras.GUI_STEAM_REACTOR) {
            TileEntityNuclearReactor reactor = ReactorHelper.asSteamReactor(world.getTileEntity(x, y, z));
            if (reactor != null) return new ContainerSteamReactor(player.inventory, reactor);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (Ic2cextrasGuiHandler.handles(id))
            return Ic2cextrasGuiHandler.INSTANCE.getClientGuiElement(id, player, world, x, y, z);
        if (ic2cextras.ported.steam.gen.Ic2cextrasGuiHandler.handles(id))
            return ic2cextras.ported.steam.gen.Ic2cextrasGuiHandler.INSTANCE
                .getClientGuiElement(id, player, world, x, y, z);
        if (id == IC2CExtras.GUI_STEAM_REACTOR) {
            TileEntityNuclearReactor reactor = ReactorHelper.asSteamReactor(world.getTileEntity(x, y, z));
            if (reactor != null) return new GuiSteamReactor(player.inventory, reactor);
        }
        return null;
    }
}
