// porter:adapter (IC2C-specific) - IC2.CONFIG: the IC2C 2.1.3.4 config entries the closure reads, with their
// IC2C defaults (ic2.core.utils.config.ic2.IC2Config).
package ic2cextras.ported.compat;

import ic2cextras.ported.shim.lib.ConfigEntry;
import java.util.ArrayList;
import java.util.List;

public class IC2Config {
    public final ConfigEntry.IntValue steamTurbineOutput = new ConfigEntry.IntValue("steamTurbineBase", 64);
    public final ConfigEntry.IntValue steamTunnelOutput = new ConfigEntry.IntValue("steamTunnelBase", 512);
    public final ConfigEntry.BoolValue gassesTravelInfinite = new ConfigEntry.BoolValue("gassesFlowInfinite", false);
    public final ConfigEntry.BoolValue tileProfiler = new ConfigEntry.BoolValue("Enable Tile Profiler", false);
    public final ConfigEntry.BoolValue reducedWhiskyBrewTime = new ConfigEntry.BoolValue("reducedWhiskyBrewtime", false);
    public final ConfigEntry.BoolValue allowMachineDrain = new ConfigEntry.BoolValue("enableMachineDrain", true);
    private final List<Runnable> listeners = new ArrayList<>();

    /** the config is loaded (defaults) as soon as it exists: listeners run immediately. */
    public void addLoadedListener(Runnable r) {
        listeners.add(r);
        r.run();
    }
}
