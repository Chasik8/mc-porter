// porter:adapter (generic) - 1.19.2 PacketDistributor targets on SimpleNetworkWrapper: PLAYER, ALL, SERVER, DIMENSION,
// TRACKING_CHUNK, TRACKING_ENTITY, TRACKING_ENTITY_AND_SELF, NEAR.
package dev.porter.shim.mc;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

public class PacketDistributor<T> {
    interface Sender<T> { void send(SimpleNetworkWrapper w, IMessage m, T arg); }

    /** a resolved target: where one message goes. */
    public static final class PacketTarget {
        private final Sender<Object> sender;
        private final Object arg;

        @SuppressWarnings("unchecked")
        <T> PacketTarget(Sender<T> sender, T arg) {
            this.sender = (Sender<Object>) sender;
            this.arg = arg;
        }

        void send(SimpleNetworkWrapper w, IMessage m) { sender.send(w, m, arg); }
    }

    /** 1.19.2 PacketDistributor.TargetPoint. */
    public static final class TargetPoint {
        public final EntityPlayerMP excluded;
        public final double x, y, z, r2;
        public final ResourceKey<?> dim;

        public TargetPoint(double x, double y, double z, double radius, ResourceKey<?> dim) { this(null, x, y, z, radius, dim); }

        public TargetPoint(EntityPlayerMP excluded, double x, double y, double z, double radius, ResourceKey<?> dim) {
            this.excluded = excluded;
            this.x = x;
            this.y = y;
            this.z = z;
            this.r2 = radius * radius;
            this.dim = dim;
        }

        public static Supplier<TargetPoint> p(double x, double y, double z, double radius, ResourceKey<?> dim) {
            TargetPoint t = new TargetPoint(x, y, z, radius, dim);
            return () -> t;
        }
    }

    private final Sender<T> sender;

    private PacketDistributor(Sender<T> sender) { this.sender = sender; }

    public PacketTarget with(Supplier<T> arg) { return new PacketTarget(sender, arg.get()); }

    public PacketTarget noArg() { return new PacketTarget(sender, null); }

    public static final PacketDistributor<EntityPlayerMP> PLAYER = new PacketDistributor<EntityPlayerMP>((w, m, p) -> w.sendTo(m, p));
    public static final PacketDistributor<Object> ALL = new PacketDistributor<Object>((w, m, a) -> w.sendToAll(m));
    public static final PacketDistributor<Object> SERVER = new PacketDistributor<Object>((w, m, a) -> w.sendToServer(m));
    public static final PacketDistributor<ResourceKey<?>> DIMENSION =
        new PacketDistributor<ResourceKey<?>>((w, m, k) -> w.sendToDimension(m, k.dimensionId()));
    public static final PacketDistributor<Chunk> TRACKING_CHUNK = new PacketDistributor<Chunk>((w, m, c) -> {
        if (!(c.worldObj instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) c.worldObj;
        for (EntityPlayerMP p : players(ws)) {
            if (ws.getPlayerManager().isPlayerWatchingChunk(p, c.xPosition, c.zPosition)) w.sendTo(m, p);
        }
    });
    public static final PacketDistributor<Entity> TRACKING_ENTITY =
        new PacketDistributor<Entity>((w, m, e) -> sendTracking(w, m, e, false));
    public static final PacketDistributor<Entity> TRACKING_ENTITY_AND_SELF =
        new PacketDistributor<Entity>((w, m, e) -> sendTracking(w, m, e, true));
    public static final PacketDistributor<TargetPoint> NEAR = new PacketDistributor<TargetPoint>((w, m, t) -> {
        MinecraftServer srv = MinecraftServer.getServer();
        WorldServer ws = srv == null ? null : srv.worldServerForDimension(t.dim.dimensionId());
        if (ws == null) return;
        for (EntityPlayerMP p : players(ws)) {
            if (p == t.excluded) continue;
            double dx = p.posX - t.x, dy = p.posY - t.y, dz = p.posZ - t.z;
            if (dx * dx + dy * dy + dz * dz < t.r2) w.sendTo(m, p);
        }
    });

    private static List<EntityPlayerMP> players(World ws) {
        List<EntityPlayerMP> out = new ArrayList<EntityPlayerMP>();
        for (Object o : ws.playerEntities) {
            if (o instanceof EntityPlayerMP) out.add((EntityPlayerMP) o);
        }
        return out;
    }

    private static void sendTracking(SimpleNetworkWrapper w, IMessage m, Entity e, boolean self) {
        if (!(e.worldObj instanceof WorldServer)) return;
        for (EntityPlayer p : ((WorldServer) e.worldObj).getEntityTracker().getTrackingPlayers(e)) {
            if (p instanceof EntityPlayerMP) w.sendTo(m, (EntityPlayerMP) p);
        }
        if (self && e instanceof EntityPlayerMP) w.sendTo(m, (EntityPlayerMP) e);
    }
}
