// porter:adapter (IC2C-specific) - the network ticks of the cut IC2EventHandler hub: GUI field updates of the
// tile a player has open (PlayerTickEvent -> NetworkManager.updateGuiData), network field packets per world
// (WorldTickEvent END -> onWorldTick), the per-tick GUI field reset (ServerTickEvent END -> onServerTick) and the end of
// GUI tracking when the container closes (1.7.10 has no PlayerContainerEvent.Close: openContainer back to the
// inventory). Registered on the FML bus by ClosurePort. p5/guifix.
package ic2cextras.ported.compat;

import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ic2cextras.ported.core.IC2;
import ic2cextras.ported.core.platform.player.PlayerHandler;

public final class PortedNetTicks {

    private static boolean registered;

    private PortedNetTicks() {}

    public static void register() {
        if (!registered) {
            registered = true;
            FMLCommonHandler.instance()
                .bus()
                .register(new PortedNetTicks());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.player.worldObj.isRemote) return;
        EntityPlayer p = e.player;
        PlayerHandler handler = PlayerHandler.getHandler(p);
        if (handler.trackedTile == null) return;
        if (p.openContainer == null || p.openContainer == p.inventoryContainer) {
            handler.trackedTile = null;
            return;
        }
        IC2.NETWORKING.get(true)
            .updateGuiData(handler.trackedTile, p);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase == TickEvent.Phase.END && !e.world.isRemote) IC2.NETWORKING.get(true)
            .onWorldTick(e.world);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) IC2.NETWORKING.get(true)
            .onServerTick();
    }
}
