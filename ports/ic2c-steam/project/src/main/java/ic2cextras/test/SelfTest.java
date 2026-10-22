package ic2cextras.test;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ic2.api.reactor.ISteamReactor;
import ic2.api.tile.IEnergyStorage;
import ic2.api.tile.IWrenchable;
import ic2.core.Ic2Items;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2.core.item.reactor.ItemReactorVentSteam;
import ic2cextras.IC2CExtras;
import ic2cextras.asm.Hooks;
import ic2cextras.gui.ContainerSteamReactor;
import ic2cextras.tile.TileReactorFluidPort;
import ic2cextras.tile.TileSteamTurbine;

/**
 * Dev-only check (config debug.selfTestOnServerStart): builds steam reactor + fluid port + 2 steam turbines + MFE in
 * the sky above spawn, runs it for a minute, logs the numbers and stops the server.
 */
public class SelfTest {

    private static final int START = 40, DURATION = 1200;

    private int ticks;
    private int bx, by, bz;
    private TileEntityNuclearReactor reactor;
    private TileReactorFluidPort port;
    private TileSteamTurbine turbine1, turbine2;
    private TileEntity mfe1, mfe2;
    private int maxOutput;
    private boolean failed;
    private boolean guiSyncOk;

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new SelfTest());
        IC2CExtras.LOG.warn("[selftest] enabled, the server will stop after the test");
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failed) return;
        ticks++;
        try {
            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(0);
            if (ticks == START) build(world);
            else if (ticks > START && ticks <= START + DURATION) step(world, ticks - START);
            else if (ticks == START + DURATION + 1) finish();
        } catch (Throwable t) {
            failed = true;
            IC2CExtras.LOG.error("[selftest] FAIL: exception", t);
            MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    private void build(WorldServer world) {
        ChunkCoordinates spawn = world.getSpawnPoint();
        bx = spawn.posX;
        by = 200;
        bz = spawn.posZ;
        IC2CExtras.LOG.info(
            "[selftest] vent patch applied: {}, divisor {}, steam vent self={}",
            Hooks.ventPatched,
            Hooks.ventDivisor,
            ((ItemReactorVentSteam) Ic2Items.reactorSteamVent.getItem()).selfVent);

        place(world, bx, by, bz, Ic2Items.steamReactor);
        world.setBlock(bx - 1, by, bz, Blocks.redstone_block, 0, 3);
        world.setBlock(bx + 1, by, bz, IC2CExtras.reactorFluidPort, 0, 3);
        world.setBlock(bx + 2, by, bz, IC2CExtras.steamTurbine, 5, 3);
        world.setBlock(bx + 1, by + 1, bz, IC2CExtras.steamTurbine, 5, 3);
        // each turbine feeds its own MFE; MFEs face up so their output side touches nothing
        place(world, bx + 3, by, bz, Ic2Items.mfeUnit);
        place(world, bx + 1, by + 2, bz, Ic2Items.mfeUnit);

        reactor = (TileEntityNuclearReactor) world.getTileEntity(bx, by, bz);
        port = (TileReactorFluidPort) world.getTileEntity(bx + 1, by, bz);
        turbine1 = (TileSteamTurbine) world.getTileEntity(bx + 2, by, bz);
        turbine2 = (TileSteamTurbine) world.getTileEntity(bx + 1, by + 1, bz);
        mfe1 = world.getTileEntity(bx + 3, by, bz);
        mfe2 = world.getTileEntity(bx + 1, by + 2, bz);
        ((IWrenchable) mfe1).setFacing((short) 1);
        ((IWrenchable) mfe2).setFacing((short) 1);
        IC2CExtras.LOG.info(
            "[selftest] reactor={} port.reactor={} mfe={}",
            reactor.getClass()
                .getSimpleName(),
            port.getReactor() == reactor,
            mfe1);

        // dual uranium in the middle of the 3-wide grid, steam vents on all four sides
        reactor.setMatrixCoord(1, 1, Ic2Items.reactorUraniumDual.copy());
        reactor.setMatrixCoord(0, 1, Ic2Items.reactorSteamVent.copy());
        reactor.setMatrixCoord(2, 1, Ic2Items.reactorSteamVent.copy());
        reactor.setMatrixCoord(1, 0, Ic2Items.reactorSteamVent.copy());
        reactor.setMatrixCoord(1, 2, Ic2Items.reactorSteamVent.copy());
    }

    private static void place(WorldServer world, int x, int y, int z, ItemStack stack) {
        world.setBlock(x, y, z, Block.getBlockFromItem(stack.getItem()), stack.getItemDamage(), 3);
    }

    private void step(WorldServer world, int t) {
        // water supply through the port's fluid handler side
        port.fill(ForgeDirection.UNKNOWN, new FluidStack(FluidRegistry.WATER, 1000), true);
        maxOutput = Math.max(maxOutput, reactor.output);
        if (t == 600) checkGuiSync(world);
        if (t % 100 == 0) {
            ISteamReactor r = (ISteamReactor) reactor;
            ItemStack vent = reactor.getMatrixCoord(0, 1);
            IC2CExtras.LOG.info(
                "[selftest] t={} hull={} water={} steam={} steamOut={}mB/t ventHeat={} | T1 speed={} {}EU/t buf={} | T2 speed={} {}EU/t buf={} | MFE={}",
                t,
                reactor.getHeat(),
                r.getWaterTank()
                    .getFluidAmount(),
                r.getSteamTank()
                    .getFluidAmount(),
                Math.round(reactor.output * 3.2),
                vent == null ? "gone" : vent.getItemDamage(),
                turbine1.speed,
                turbine1.lastProduction,
                turbine1.energy,
                turbine2.speed,
                turbine2.lastProduction,
                turbine2.energy,
                stored());
        }
    }

    /** Server container -> progress bar shorts -> second container decoding, like the real client would. */
    private void checkGuiSync(WorldServer world) {
        final ContainerSteamReactor server = new ContainerSteamReactor(
            FakePlayerFactory.getMinecraft(world).inventory,
            reactor);
        final ContainerSteamReactor client = new ContainerSteamReactor(
            FakePlayerFactory.getMinecraft(world).inventory,
            reactor);
        server.addCraftingToCrafters(new ICrafting() {

            @Override
            public void sendContainerAndContentsToPlayer(Container c, List l) {}

            @Override
            public void sendSlotContents(Container c, int slot, ItemStack s) {}

            @Override
            public void sendProgressBarUpdate(Container c, int id, int value) {
                client.updateProgressBar(id, (short) value);
            }
        });
        ISteamReactor r = (ISteamReactor) reactor;
        guiSyncOk = client.water() == r.getWaterTank()
            .getFluidAmount() && client.steam()
                == r.getSteamTank()
                    .getFluidAmount()
            && client.heat() == reactor.getHeat()
            && client.maxHeat() == reactor.getMaxHeat()
            && server.inventorySlots.size() == 6 * server.size + 36;
        IC2CExtras.LOG.info(
            "[selftest] gui sync water={} steam={} heat={}/{} out={} slots={} -> {}",
            client.water(),
            client.steam(),
            client.heat(),
            client.maxHeat(),
            client.output(),
            server.inventorySlots.size(),
            guiSyncOk);
    }

    private int stored() {
        return stored(mfe1) + stored(mfe2);
    }

    private static int stored(TileEntity te) {
        return te instanceof IEnergyStorage ? ((IEnergyStorage) te).getStored() : 0;
    }

    private void finish() {
        int mfeStored = stored();
        boolean steamOk = maxOutput > 0;
        boolean turbinesOk = turbine1.lastProduction > 0 && turbine2.lastProduction > 0;
        boolean energyOk = stored(mfe1) > 0 && stored(mfe2) > 0;
        IC2CExtras.LOG.info(
            "[selftest] RESULT steam={} (max steamOut {} mB/t) turbines={} ({} + {} EU/t) energyNet={} (MFE {} EU) ventPatch={}",
            steamOk,
            Math.round(maxOutput * 3.2),
            turbinesOk,
            turbine1.lastProduction,
            turbine2.lastProduction,
            energyOk,
            mfeStored,
            Hooks.ventPatched);
        IC2CExtras.LOG.info(
            "[selftest] {}",
            steamOk && turbinesOk && energyOk && guiSyncOk && Hooks.ventPatched ? "PASS" : "FAIL");
        MinecraftServer.getServer()
            .initiateShutdown();
    }
}
