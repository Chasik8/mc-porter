package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;

public class ModMultiblocks {
    public static void register() {
        PatchouliAPI.get().registerMultiblock(new ResourceLocation(ExampleMod.MODID, "press_frame"),
                PatchouliAPI.get().makeMultiblock(new String[][] {
                        {"TTT", "T T", "TTT"},
                        {"TTT", "T0T", "TTT"}},
                        'T', ModBlocks.TIN_BLOCK.get(), '0', ModBlocks.PRESS.get()));
    }
}
