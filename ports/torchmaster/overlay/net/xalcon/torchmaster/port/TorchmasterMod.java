// Unofficial 1.7.10 port of Torchmaster by Xalcon (MIT). Wiring written for the port (porter demo).
package net.xalcon.torchmaster.port;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import net.xalcon.torchmaster.shim.mc.ModLifecycle;
import net.xalcon.torchmaster.Torchmaster;
import net.xalcon.torchmaster.gen.recipe.TorchmasterRecipes;

/** 1.7.10 entry point: runs the ported 1.19.2 mod constructor and bridges the FML lifecycle to its listeners. */
@Mod(modid = "torchmaster", name = "Torchmaster (unofficial 1.7.10 port)", version = "19.2.93-port.1")
public class TorchmasterMod {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        ModLifecycle.construct("torchmaster", e, Torchmaster::new);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ModLifecycle.init("torchmaster", e);
        TorchmasterRecipes.registerOreDictionary();
        TorchmasterRecipes.register();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        TorchmasterRecipes.postInit();
        ModLifecycle.postInit("torchmaster", e);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        ModLifecycle.serverStarting(e);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent e) {
        ModLifecycle.serverStarted(e);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent e) {
        ModLifecycle.serverStopped(e);
    }
}
