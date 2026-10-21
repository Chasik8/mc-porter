package dev.porter.testkit.mc1710;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/**
 * Loading plugin (no transformers) whose only job is to keep GUI-scenario clients off the user's desktop: FML
 * instantiates coremods before Minecraft creates its window, so a {@code Display.setLocation} here decides where the
 * LWJGL 2 window appears. Active only with {@code -Dporter.window=offscreen} (set by the testkit runner for client
 * runs); inert on servers and in normal games.
 */
@IFMLLoadingPlugin.Name("porter-testkit-window")
@IFMLLoadingPlugin.MCVersion("1.7.10")
public final class EarlyWindow implements IFMLLoadingPlugin {
    public EarlyWindow() {
        if ("offscreen".equals(System.getProperty("porter.window"))) {
            try {
                org.lwjgl.opengl.Display.setLocation(-12000, -12000);
            } catch (Throwable t) {
                System.err.println("[porter-testkit] cannot move the window off-screen: " + t);
            }
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
