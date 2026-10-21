package com.example.examplemod;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "examplemod";

    public ExampleMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(bus);
        ModMenus.MENU_TYPES.register(bus);
        ModFluids.FLUID_TYPES.register(bus);
        ModFluids.FLUIDS.register(bus);
        ModEffects.MOB_EFFECTS.register(bus);
        ModSounds.SOUND_EVENTS.register(bus);
        ModNetwork.register();
        ModMultiblocks.register();
    }
}
