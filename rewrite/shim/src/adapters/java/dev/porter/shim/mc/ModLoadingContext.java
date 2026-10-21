// porter:adapter (generic) - Forge 1.19.2 ModLoadingContext.registerConfig: the ForgeConfigSpec is loaded at once
// from config/<file name>.cfg (1.7.10 Configuration format; ".toml" is replaced by ".cfg").
package dev.porter.shim.mc;

import java.io.File;

public final class ModLoadingContext {
    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    private ModLoadingContext() {}

    public static ModLoadingContext get() { return INSTANCE; }

    public String getActiveNamespace() { return ModLifecycle.current == null ? "minecraft" : ModLifecycle.current; }

    public void registerConfig(ModConfig.Type type, ForgeConfigSpec spec) {
        registerConfig(type, spec, getActiveNamespace() + "-" + type.extension() + ".toml");
    }

    public void registerConfig(ModConfig.Type type, ForgeConfigSpec spec, String fileName) {
        String n = fileName.endsWith(".toml") ? fileName.substring(0, fileName.length() - 5) + ".cfg" : fileName;
        spec.load(new File(ModLifecycle.configDir, n));
    }
}
