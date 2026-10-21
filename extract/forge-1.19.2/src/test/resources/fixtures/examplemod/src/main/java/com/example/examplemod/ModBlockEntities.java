package com.example.examplemod;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ExampleMod.MODID);

    public static final RegistryObject<BlockEntityType<PressBlockEntity>> PRESS = BLOCK_ENTITY_TYPES.register("press",
            () -> BlockEntityType.Builder.of(PressBlockEntity::new, ModBlocks.PRESS.get()).build(null));
}
