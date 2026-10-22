// porter:adapter (IC2C-specific) - IC2.TICK_HANDLER: per-world callbacks run at the end of the world tick
// (callback returns the delay for the next run; <= 0 removes it), driven by the FML WorldTickEvent.
package ic2cextras.ported.compat;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.world.World;

public class TickScheduler {
    private final Map<World, List<Entry>> callbacks = new IdentityHashMap<>();
    private boolean registered;

    private static final class Entry {
        final ToIntFunction<World> ticket;
        int delay;

        Entry(ToIntFunction<World> t, int d) {
            ticket = t;
            delay = d;
        }
    }

    public synchronized void addWorldCallback(World world, ToIntFunction<World> ticket) { addWorldCallback(world, ticket, 0); }

    public synchronized void addWorldCallback(World world, ToIntFunction<World> ticket, int delay) {
        if (world == null) return;
        if (!registered) {
            registered = true;
            FMLCommonHandler.instance().bus().register(this);
        }
        callbacks.computeIfAbsent(world, k -> new ArrayList<>()).add(new Entry(ticket, delay));
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase == TickEvent.Phase.END) run(e.world);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        List<World> worlds;
        synchronized (this) {
            worlds = new ArrayList<>(callbacks.keySet());
        }
        for (World w : worlds) if (w.isRemote) run(w);
    }

    private void run(World w) {
        List<Entry> list;
        synchronized (this) {
            list = callbacks.remove(w);
        }
        if (list == null) return;
        List<Entry> keep = new ArrayList<>();
        for (Entry en : list) {
            if (en.delay > 0) {
                en.delay--;
                keep.add(en);
                continue;
            }
            int next = en.ticket.applyAsInt(w);
            if (next > 0) {
                en.delay = next;
                keep.add(en);
            }
        }
        synchronized (this) {
            if (!keep.isEmpty()) callbacks.computeIfAbsent(w, k -> new ArrayList<>()).addAll(0, keep);
        }
    }
}
