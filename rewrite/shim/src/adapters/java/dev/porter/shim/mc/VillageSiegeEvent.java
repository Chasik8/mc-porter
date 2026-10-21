// porter:adapter (generic) - Forge 1.19.2 VillageSiegeEvent. 1.7.10 Forge has no siege hook: the event exists so
// ported subscribers compile; it is never posted (village sieges cannot be blocked on 1.7.10).
package dev.porter.shim.mc;

import cpw.mods.fml.common.eventhandler.Cancelable;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

@Cancelable
@Event.HasResult
public class VillageSiegeEvent extends Event {
    private final World level;
    private final Vec3 attemptedSpawnPos;

    public VillageSiegeEvent(World level, Vec3 pos) {
        this.level = level;
        this.attemptedSpawnPos = pos;
    }

    public World getLevel() { return level; }

    public Vec3 getAttemptedSpawnPos() { return attemptedSpawnPos; }
}
