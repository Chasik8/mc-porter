package com.example.examplemod;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    static ForgeConfigSpec.ConfigValue<List<String>> COLOURS;

    static List<String> colours() {
        return COLOURS.get();
    }
}
