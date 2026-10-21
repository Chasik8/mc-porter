package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ExampleMod.MODID);

    public static final RegistryObject<SoundEvent> PRESS_RUN = SOUND_EVENTS.register("press_run",
            () -> new SoundEvent(new ResourceLocation(ExampleMod.MODID, "press_run")));
    public static final RegistryObject<SoundEvent> ORPHAN = SOUND_EVENTS.register("orphan",
            () -> new SoundEvent(new ResourceLocation(ExampleMod.MODID, "orphan")));
}
