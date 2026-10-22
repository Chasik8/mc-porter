package io.github.chasik8.bckinesisfix;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Loading plugin: registers one class transformer that patches
 * {@code buildcraft.core.lib.block.TileBuildCraft#receiveEnergy} at load time.
 * The jar contains no BuildCraft code; it only edits a few instructions of the class BuildCraft itself ships.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("BCKinesisFix")
@IFMLLoadingPlugin.TransformerExclusions("io.github.chasik8.bckinesisfix.")
public class KinesisFixPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {KinesisFixTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return KinesisFixContainer.class.getName();
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
