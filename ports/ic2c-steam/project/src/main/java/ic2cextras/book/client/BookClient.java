package ic2cextras.book.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2cextras.book.MultiblockDef;

/** Client entry points of the guide. Only call on the client. */
@SideOnly(Side.CLIENT)
public final class BookClient {

    private BookClient() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(Hologram.INSTANCE);
    }

    public static void open() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiGuideBook());
    }

    public static void toggleHologram(MultiblockDef def, int x, int y, int z, int facing) {
        Hologram.INSTANCE.toggle(def, x, y, z, facing);
    }
}
