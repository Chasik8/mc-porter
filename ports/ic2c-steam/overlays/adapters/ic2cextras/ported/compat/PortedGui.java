// porter:adapter (IC2C-specific) - FML GUI handling for ported IC2C tiles (IHasGui): one GUI id from the
// steam range (60-69); server builds the tile's container, client its screen (ITileGui.createGui).
package ic2cextras.ported.compat;

import ic2cextras.ported.core.inventory.base.IHasGui;
import ic2cextras.ported.core.inventory.container.IC2Container;
import ic2cextras.ported.shim.Direction;
import ic2cextras.ported.shim.mc.InteractionHand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public final class PortedGui {
    /** GUI id of every ported IC2C tile GUI (side encoded in nothing: IC2C opens tile GUIs side-independently). */
    public static final int GUI_TILE = 61;
    /** the host mod instance FML opens GUIs for (set by the host's init). */
    public static Object hostMod;

    private PortedGui() {}

    public static boolean handles(int id) { return id == GUI_TILE; }

    static void open(EntityPlayer player, World w, int x, int y, int z, Direction side) {
        if (hostMod != null) player.openGui(hostMod, GUI_TILE, w, x, y, z);
    }

    public static Object server(int id, EntityPlayer player, World w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof IHasGui)) return null;
        IC2Container c = ((IHasGui) te).createContainer(player, InteractionHand.MAIN_HAND, Direction.NORTH, 0);
        if (c instanceof ic2cextras.ported.shim.mc.ModernContainer) ((ic2cextras.ported.shim.mc.ModernContainer) c).bindPlayer(player);
        return c;
    }

    public static Object client(int id, EntityPlayer player, World w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof IHasGui)) return null;
        IHasGui gui = (IHasGui) te;
        IC2Container c = gui.createContainer(player, InteractionHand.MAIN_HAND, Direction.NORTH, 0);
        if (c instanceof ic2cextras.ported.shim.mc.ModernContainer) ((ic2cextras.ported.shim.mc.ModernContainer) c).bindPlayer(player);
        return gui.createGui(player, InteractionHand.MAIN_HAND, Direction.NORTH, c);
    }
}
