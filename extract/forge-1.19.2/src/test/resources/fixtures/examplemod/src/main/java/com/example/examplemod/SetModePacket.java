package com.example.examplemod;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public class SetModePacket {
    private final BlockPos pos;
    private final int mode;
    private final String label;

    public SetModePacket(BlockPos pos, int mode, String label) {
        this.pos = pos;
        this.mode = mode;
        this.label = label;
    }

    public SetModePacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(32));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.mode);
        buf.writeUtf(this.label, 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
    }
}
