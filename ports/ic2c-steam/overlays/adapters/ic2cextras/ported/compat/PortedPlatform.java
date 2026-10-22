// porter:adapter (IC2C-specific) - IC2.PLATFORM for the ported IC2C 2.x framework on 1.7.10: side checks
// (thread based, like 1.19.2 EffectiveSide), worlds by dimension key, GUIs through the host's FML GUI handler.
package ic2cextras.ported.compat;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import ic2cextras.ported.core.inventory.base.IHasCustomGui;
import ic2cextras.ported.core.inventory.base.IHasGui;
import ic2cextras.ported.shim.BlockPos;
import ic2cextras.ported.shim.Direction;
import ic2cextras.ported.shim.mc.InteractionHand;
import ic2cextras.ported.shim.mc.ResourceKey;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import org.apache.logging.log4j.LogManager;

public class PortedPlatform {
    public boolean isSimulating() { return FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER; }

    public boolean isRendering() { return !isSimulating(); }

    /** IC2C resets the time since the player last slept (phantoms); 1.7.10 has no such statistic. */
    public void resetSleep(EntityPlayer player) {}

    public World getWorld(ResourceKey<World> dim) {
        return dim == null ? null : DimensionManager.getWorld(dim.dimensionId());
    }

    public MinecraftServer getServer() { return MinecraftServer.getServer(); }

    public void displayError(String s) { LogManager.getLogger("ic2cextras-ported").error(s); }

    /** server side: open the GUI of a ported tile through the host's FML GUI handler (PortedGui ids). */
    public boolean launchGui(EntityPlayer player, InteractionHand hand, Direction side, IHasGui gui) {
        if (!(gui instanceof TileEntity) || player.worldObj.isRemote) return false;
        TileEntity te = (TileEntity) gui;
        if (!gui.hasGui(player, hand, side)) return false;
        PortedGui.open(player, te.getWorldObj(), te.xCoord, te.yCoord, te.zCoord, side);
        // IC2C's own open path (NetworkManager.sendUIPacket) also sends the GUI fields and tracks the tile for updates
        if (te instanceof ic2cextras.ported.api.network.tile.INetworkFieldProvider) {
            ic2cextras.ported.core.networking.NetworkManager nm = ic2cextras.ported.core.IC2.NETWORKING.get(true);
            nm.sendInitialGuiData((ic2cextras.ported.api.network.tile.INetworkFieldProvider) te, player);
            nm.startGuiTracking(te, player);
        }
        return true;
    }

    public boolean launchGui(EntityPlayer player, IHasCustomGui gui) { return false; }

    /** client side opening is done by FML (getClientGuiElement); kept for the ported call sites. */
    public boolean launchGuiClient(EntityPlayer player, InteractionHand hand, Direction side, IHasGui gui, int windowId) { return false; }

    public boolean launchGuiClient(EntityPlayer player, IHasCustomGui gui, int windowId) { return false; }

    public void markBlockForRenderUpdate(BlockPos pos) {
        if (isRendering()) ClientHooks.markForRenderUpdate(pos);
    }
}
