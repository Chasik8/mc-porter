package com.example.examplemod;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ExampleMod.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SetModePacket.class, SetModePacket::encode, SetModePacket::new,
                SetModePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.messageBuilder(SyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncPacket::write).decoder(SyncPacket::new).consumerMainThread(SyncPacket::handle).add();
    }
}
