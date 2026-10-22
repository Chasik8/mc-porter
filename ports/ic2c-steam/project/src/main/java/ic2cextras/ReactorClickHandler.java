package ic2cextras;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ic2.api.reactor.IReactorComponent;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2cextras.tile.ReactorHelper;

/** Opens the gauge GUI instead of IC2's bare one when a steam reactor or steam chamber is right-clicked. */
public class ReactorClickHandler {

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (!Config.replaceReactorGui || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (event.entityPlayer.isSneaking()) return;
        ItemStack held = event.entityPlayer.getCurrentEquippedItem();
        if (held != null && !isReactorItem(held.getItem())) return;
        TileEntityNuclearReactor reactor = ReactorHelper
            .asSteamReactor(event.world.getTileEntity(event.x, event.y, event.z));
        if (reactor == null) return;
        // Cancelling on the client would stop the click packet from ever reaching the server.
        if (event.world.isRemote) return;
        event.setCanceled(true);
        event.entityPlayer.openGui(
            IC2CExtras.instance,
            IC2CExtras.GUI_STEAM_REACTOR,
            event.world,
            reactor.xCoord,
            reactor.yCoord,
            reactor.zCoord);
    }

    private static boolean isReactorItem(Item item) {
        return item instanceof IReactorComponent;
    }
}
