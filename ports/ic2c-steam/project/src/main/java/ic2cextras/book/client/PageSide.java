package ic2cextras.book.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** One page side of the book (left or right half of a spread). Coordinates are absolute screen coordinates. */
@SideOnly(Side.CLIENT)
public abstract class PageSide {

    public abstract void draw(GuiGuideBook gui, int x, int y, int mx, int my, float pt);

    /** @return true if handled; the side then receives drag/release events until the button goes up. */
    public boolean click(GuiGuideBook gui, int x, int y, int mx, int my, int button) {
        return false;
    }

    public void drag(GuiGuideBook gui, int mx, int my) {}

    public void release(GuiGuideBook gui) {}

    public boolean scroll(GuiGuideBook gui, int x, int y, int mx, int my, int dir) {
        return false;
    }
}
