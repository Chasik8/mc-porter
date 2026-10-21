// porter:adapter (generic) - Forge 1.19.2 ModConfig.Type (1.7.10 has one config file per spec).
package dev.porter.shim.mc;

public final class ModConfig {
    private ModConfig() {}

    public enum Type {
        COMMON, CLIENT, SERVER;

        public String extension() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
}
