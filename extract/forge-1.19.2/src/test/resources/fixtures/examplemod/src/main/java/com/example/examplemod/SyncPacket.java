package com.example.examplemod;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public class SyncPacket {
    private final long energy;
    private final boolean active;

    public SyncPacket(long energy, boolean active) {
        this.energy = energy;
        this.active = active;
    }

    public SyncPacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readBoolean());
    }

    public static void write(SyncPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.energy);
        buf.writeBoolean(msg.active);
    }

    public static void handle(SyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
    }
}
