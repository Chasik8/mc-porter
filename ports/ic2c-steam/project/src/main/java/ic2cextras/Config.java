package ic2cextras;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import ic2cextras.asm.Hooks;

public final class Config {

    public static boolean replaceReactorGui = true;
    public static int portSteamPerTick = 20000;
    public static int portWaterPerTick = 1000;

    public static int turbineBaseOutput = 64;

    public static boolean newVentStats = true;
    public static double steamReactorMultiplier = 2.0;

    public static boolean selfTestOnServerStart = false;

    public static boolean guideHologram = true;

    private Config() {}

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();

        replaceReactorGui = cfg.getBoolean(
            "replaceSteamReactorGui",
            "reactor",
            true,
            "Right-clicking a steam reactor / steam chamber with an empty hand or a reactor component opens the new GUI with water, steam and heat gauges.");
        portSteamPerTick = cfg.getInt(
            "portSteamPerTick",
            "reactor",
            20000,
            0,
            1000000,
            "Max mB of steam a Reactor Fluid Port pushes into neighbours per tick (1.19.2: 20000).");
        portWaterPerTick = cfg.getInt(
            "portWaterPerTick",
            "reactor",
            1000,
            0,
            1000000,
            "Max mB of water a Reactor Fluid Port pulls from each neighbour per tick.");

        newVentStats = cfg.getBoolean(
            "useNewSteamVentStats",
            "balance",
            true,
            "Use IC2 Classic 1.19.2 steam vent stats (self/reactor vent: 12/0, 10/10, 40/72, 24/0).");
        steamReactorMultiplier = cfg.getFloat(
            "steamReactorMultiplier",
            "balance",
            2.0F,
            0.1F,
            16.0F,
            "Steam per heat unit multiplier like 1.19.2 (1 heat = 4*mult mB steam). 1.0 = original 1.7.10 behaviour. Needs the coremod patch (always present).");

        turbineBaseOutput = cfg.getInt(
            "steamTurbineOutput",
            "turbine",
            64,
            1,
            128,
            "EU/t of the Steam Turbine at full speed (1.19.2: 64). It eats up to 128 mB steam per tick.");

        selfTestOnServerStart = cfg.getBoolean(
            "selfTestOnServerStart",
            "debug",
            false,
            "DEV ONLY: builds a test reactor setup at spawn when a server starts, logs results and stops the server.");

        guideHologram = cfg.getBoolean(
            "guideHologram",
            "guide",
            true,
            "Shift + right click a multiblock controller with the IC2 Guide to project the missing blocks of the structure (client side only).");

        if (cfg.hasChanged()) cfg.save();

        Hooks.ventDivisor = 40.0 / steamReactorMultiplier;
    }
}
