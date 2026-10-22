package ic2cextras.ported;

import net.minecraftforge.common.MinecraftForge;

import ic2cextras.ported.compat.PortedGui;
import ic2cextras.ported.compat.PortedNet;
import ic2cextras.ported.core.platform.events.MultiBlockManager;
import ic2cextras.ported.core.platform.events.StructureManager;
import ic2cextras.ported.shim.mc.ForgeEvents;

/**
 * porter closure port (agent, wiring): what the cut IC2C main class / platform did for the ported steam closure -
 * network channel, GUI host, 1.19.2-only Forge events and the multiblock/structure event handlers. The host mod calls
 * {@link #init(Object)} from its init.
 */
public final class ClosurePort {

    private ClosurePort() {}

    public static void init(Object mod) {
        PortedNet.init();
        PortedGui.hostMod = mod;
        ForgeEvents.init();
        MinecraftForge.EVENT_BUS.register(MultiBlockManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(StructureManager.INSTANCE);
        ic2cextras.ported.compat.PortedNetTicks.register(); // network/GUI field sync (cut IC2EventHandler ticks)
        if (cpw.mods.fml.common.FMLCommonHandler.instance()
            .getSide()
            .isClient()) {
            ic2cextras.ported.compat.ClientHooks.registerTextures(); // IC2Textures sprite maps (models, GUI icons)
        }
    }

    public static void serverStopped() {
        MultiBlockManager.INSTANCE.onServerStopped();
    }
}
