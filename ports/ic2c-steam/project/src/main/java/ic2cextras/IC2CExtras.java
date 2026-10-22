package ic2cextras;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.oredict.ShapedOreRecipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import ic2.core.Ic2Items;
import ic2.core.item.reactor.ItemReactorVentSteam;
import ic2cextras.asm.Hooks;
import ic2cextras.block.BlockReactorFluidPort;
import ic2cextras.block.BlockSteamTurbine;
import ic2cextras.gui.GuiHandler;
import ic2cextras.ported.gen.Ic2cextrasContent;
import ic2cextras.test.SelfTest;
import ic2cextras.tile.TileReactorFluidPort;
import ic2cextras.tile.TileSteamTurbine;

@Mod(
    modid = IC2CExtras.MODID,
    name = IC2CExtras.NAME,
    version = Tags.VERSION,
    dependencies = "required-after:IC2",
    acceptedMinecraftVersions = "[1.7.10]")
public class IC2CExtras {

    public static final String MODID = "ic2cextras";
    public static final String NAME = "IC2 Classic Extras";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public static final int GUI_STEAM_REACTOR = 0;

    @Mod.Instance(MODID)
    public static IC2CExtras instance;

    public static Block reactorFluidPort;
    public static Block steamTurbine;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());

        reactorFluidPort = new BlockReactorFluidPort();
        GameRegistry.registerBlock(reactorFluidPort, "reactorFluidPort");
        GameRegistry.registerTileEntity(TileReactorFluidPort.class, MODID + ":reactorFluidPort");

        steamTurbine = new BlockSteamTurbine();
        GameRegistry.registerBlock(steamTurbine, "steamTurbine");
        GameRegistry.registerTileEntity(TileSteamTurbine.class, MODID + ":steamTurbine");
        Ic2cextrasContent.preInit(event, this); // porter: generated content (barrels)
        ic2cextras.ported.steam.gen.Ic2cextrasContent.preInit(event, this); // porter: generated content (steam)
        ic2cextras.book.GuideBook.preInit(); // guide book (carried over from the hand port)
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        Ic2cextrasContent.init(event); // porter
        ic2cextras.ported.steam.gen.Ic2cextrasContent.init(event); // porter (steam)
        ic2cextras.ported.BarrelsPort.init(); // porter: ported-subsystem integration (agent)
        ic2cextras.ported.ClosurePort.init(this); // porter: steam closure wiring (agent)
        ic2cextras.book.GuideBook.init(event); // guide book
        MinecraftForge.EVENT_BUS.register(new ReactorClickHandler());
        if (Config.newVentStats) applyVentStats();
        if (!Hooks.ventPatched) LOG.warn("Steam vent class was not patched, steamReactorMultiplier has no effect");
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        Ic2cextrasContent.postInit(event); // porter
        ic2cextras.ported.steam.gen.Ic2cextrasContent.postInit(event); // porter (steam)
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                reactorFluidPort,
                " C ",
                "CRC",
                " P ",
                'C',
                Ic2Items.cell,
                'R',
                Ic2Items.steamReactorChamber,
                'P',
                Ic2Items.pump));
        // 1.19.2: circuit / turbine blade, generator, water mill / insulated cable; the 1.7.10 basic turbine stands in
        // for the blade
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                steamTurbine,
                " A ",
                "XYC",
                " B ",
                'A',
                Ic2Items.electronicCircuit,
                'X',
                Ic2Items.basicTurbine,
                'Y',
                Ic2Items.generator,
                'C',
                Ic2Items.waterMill,
                'B',
                Ic2Items.insulatedGoldCableItem));
        ic2cextras.book.GuideBook.addRecipes(); // guide book
    }

    @Mod.EventHandler
    public void serverStopped(cpw.mods.fml.common.event.FMLServerStoppedEvent event) {
        ic2cextras.ported.ClosurePort.serverStopped(); // porter: steam closure wiring (agent)
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (Config.selfTestOnServerStart) SelfTest.register();
        if (Boolean.getBoolean("porter.nei.report")) { // porter: what the generated NEI pages resolve in this pack
            LOG.info("porter NEI report (barrels):\n" + ic2cextras.ported.gen.nei.Ic2cextrasNeiData.report());
            LOG.info("porter NEI report (steam):\n" + ic2cextras.ported.steam.gen.nei.Ic2cextrasNeiData.report());
        }
    }

    /** 1.19.2 vent stats (IC2Items.java: STEAM 12/0, CORE 10/10, OVERCLOCKED 40/72, ADVANCED 24/0). */
    private static void applyVentStats() {
        setVent(Ic2Items.reactorSteamVent, 12, 0);
        setVent(Ic2Items.reactorSteamVentCore, 10, 10);
        setVent(Ic2Items.reactorSteamVentGold, 40, 72);
        setVent(Ic2Items.reactorSteamVentDiamond, 24, 0);
    }

    private static void setVent(ItemStack stack, int self, int reactor) {
        if (stack == null || !(stack.getItem() instanceof ItemReactorVentSteam)) return;
        ItemReactorVentSteam vent = (ItemReactorVentSteam) stack.getItem();
        vent.selfVent = self;
        vent.reactorVent = reactor;
    }
}
