// porter:adapter (generic) - the one 1.7.10 IMessage that carries every message of a 1.19.2 SimpleChannel:
// a varint message id followed by the bytes the 1.19.2 encoder wrote. Decoding happens in the channel handler.
package dev.porter.shim.mc;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;

public class NetBridgeMessage implements IMessage {
    public int id;
    public byte[] data = new byte[0];

    public NetBridgeMessage() {}

    public NetBridgeMessage(int id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer pb = new PacketBuffer(buf);
        id = pb.readVarIntFromBuffer();
        data = new byte[buf.readableBytes()];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        new PacketBuffer(buf).writeVarIntToBuffer(id);
        buf.writeBytes(data);
    }
}
