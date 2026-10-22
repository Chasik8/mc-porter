// porter:adapter (IC2C-specific) - transport for the ported IC2C packets (IC2Packet) on an FML 1.7.10 event
// channel: payload = varint packet index + the packet's own write(PacketBuffer). Received packets run on the main
// thread (queued, drained by the server/client tick) like 1.19.2's enqueueWork.
package ic2cextras.ported.compat;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import ic2cextras.ported.core.networking.packets.IC2Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;

public final class PortedNet {
    public static final String CHANNEL = "ic2cx-ported";
    /** deterministic packet index table (the ported IC2C packets). */
    static final List<String> PACKETS = Arrays.asList(
        "ic2cextras.ported.core.networking.packets.client.TileDataBufferEventPacket",
        "ic2cextras.ported.core.networking.packets.client.custom.FilterComponentPacket",
        "ic2cextras.ported.core.networking.packets.common.ItemDataBufferPacket",
        "ic2cextras.ported.core.networking.packets.common.ItemEventPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.open.OpenCustomGuiPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.open.OpenEntityGuiPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.open.OpenItemGuiPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.open.OpenTileGuiPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.sync.CustomNetworkPacket",
        "ic2cextras.ported.core.networking.packets.server.gui.sync.GuiFieldUpdatePacket",
        "ic2cextras.ported.core.networking.packets.server.gui.sync.NetworkFieldUpdatePacket",
        "ic2cextras.ported.core.networking.packets.server.gui.sync.ServerTileEventPacket");
    private static FMLEventChannel channel;
    private static final ConcurrentLinkedQueue<Runnable> SERVER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> CLIENT = new ConcurrentLinkedQueue<>();

    private PortedNet() {}

    /** called by the host's init (FML requires channels before the server starts). */
    public static synchronized void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        PortedNet h = new PortedNet();
        channel.register(h);
        FMLCommonHandler.instance().bus().register(h);
    }

    static FMLProxyPacket encode(IC2Packet p) {
        int id = PACKETS.indexOf(p.getClass().getName());
        if (id < 0) throw new IllegalArgumentException("unregistered ported packet " + p.getClass());
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeVarIntToBuffer(id);
        p.write(buf);
        return new FMLProxyPacket(buf, CHANNEL);
    }

    static IC2Packet decode(ByteBuf data) throws Exception {
        PacketBuffer buf = new PacketBuffer(data);
        int id = buf.readVarIntFromBuffer();
        IC2Packet p = (IC2Packet) Class.forName(PACKETS.get(id)).newInstance();
        p.read(buf);
        return p;
    }

    public static void sendToServer(IC2Packet p) {
        if (channel != null) channel.sendToServer(encode(p));
    }

    public static void sendToPlayer(IC2Packet p, EntityPlayer player) {
        if (channel != null && player instanceof EntityPlayerMP) channel.sendTo(encode(p), (EntityPlayerMP) player);
    }

    public static void sendToAll(IC2Packet p) {
        if (channel != null) channel.sendToAll(encode(p));
    }

    public static void sendToDimension(IC2Packet p, World w) {
        if (channel != null) channel.sendToDimension(encode(p), w.provider.dimensionId);
    }

    public static void sendToNearby(IC2Packet p, World w, double x, double y, double z, double range) {
        if (channel != null) channel.sendToAllAround(encode(p), new NetworkRegistry.TargetPoint(w.provider.dimensionId, x, y, z, range));
    }

    @SubscribeEvent
    public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent e) throws Exception {
        final IC2Packet p = decode(e.packet.payload());
        final EntityPlayer player = ((NetHandlerPlayServer) e.handler).playerEntity;
        SERVER.add(() -> p.handlePacket(player));
    }

    @SubscribeEvent
    public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent e) throws Exception {
        final IC2Packet p = decode(e.packet.payload());
        CLIENT.add(() -> p.handlePacket(ClientPlayer.get()));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.START) drain(SERVER);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.START) drain(CLIENT);
    }

    private static void drain(ConcurrentLinkedQueue<Runnable> q) {
        Runnable r;
        while ((r = q.poll()) != null) r.run();
    }

    /** client player accessor kept out of the common class (client classes load only on the client). */
    static final class ClientPlayer {
        static EntityPlayer get() { return net.minecraft.client.Minecraft.getMinecraft().thePlayer; }
    }
}
