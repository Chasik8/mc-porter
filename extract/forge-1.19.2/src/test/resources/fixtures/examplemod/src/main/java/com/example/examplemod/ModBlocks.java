package com.example.examplemod;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ExampleMod.MODID);

    public static final RegistryObject<Block> PRESS = BLOCKS.register("press", () -> new PressBlock(
            BlockBehaviour.Properties.of(Material.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().lightLevel(state -> 7)));

    public static final RegistryObject<Block> TIN_BLOCK = BLOCKS.register("tin_block",
            () -> new Block(BlockBehaviour.Properties.of(Material.STONE).strength(2.0F)));
}
