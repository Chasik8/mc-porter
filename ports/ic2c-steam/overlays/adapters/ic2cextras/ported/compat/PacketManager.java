// porter:adapter (IC2C-specific) - ic2.core.networking.PacketManager on 1.7.10 (the hub is redirected here by a type rule): ic2.core.networking.PacketManager on the 1.7.10 channel adapter (compat.PortedNet).
package ic2cextras.ported.compat;

import ic2cextras.ported.core.networking.packets.IC2Packet;
import ic2cextras.ported.shim.BlockPos;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

public class PacketManager {
    public static final PacketManager INSTANCE = new PacketManager();

    public void init() { PortedNet.init(); }

    public void sendToServer(IC2Packet packet) { PortedNet.sendToServer(packet); }

    public void sendToPlayer(IC2Packet packet, EntityPlayer player) { PortedNet.sendToPlayer(packet, player); }

    public void sendToAllPlayers(IC2Packet packet) { PortedNet.sendToAll(packet); }

    public void sendToAllDim(World level, IC2Packet packet) { PortedNet.sendToDimension(packet, level); }

    public void sendToAllChunkWatchers(Chunk chunk, IC2Packet packet) {
        PortedNet.sendToNearby(packet, chunk.worldObj, chunk.xPosition * 16 + 8, 128, chunk.zPosition * 16 + 8, 160);
    }

    public void sendToNearby(World level, BlockPos pos, int radius, IC2Packet packet) {
        PortedNet.sendToNearby(packet, level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, radius);
    }
}
