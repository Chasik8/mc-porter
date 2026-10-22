package io.github.chasik8.bckinesisfix;

import com.google.common.eventbus.EventBus;
import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.ModMetadata;

import java.util.Arrays;

/** Makes the fix visible in the mod list. It has no content of its own. */
public class KinesisFixContainer extends DummyModContainer {

    public static final String MODID = "bckinesisfix";
    public static final String VERSION = "1.0.0";

    public KinesisFixContainer() {
        super(new ModMetadata());
        ModMetadata meta = getMetadata();
        meta.modId = MODID;
        meta.name = "BuildCraft Kinesis Fix";
        meta.version = VERSION;
        meta.description = "Unofficial bug fix for BuildCraft 7.1.23: kinesis pipes deliver power every tick "
                + "instead of every other tick. Contains no BuildCraft code.";
        meta.authorList = Arrays.asList("Chasik8");
        meta.url = "https://github.com/Chasik8/mc-porter/tree/main/fixes/buildcraft-kinesis";
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        return true;
    }
}
