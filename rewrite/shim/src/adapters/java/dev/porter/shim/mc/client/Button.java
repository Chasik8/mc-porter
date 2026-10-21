// porter:adapter (generic, client) - 1.19.2 Button (+ Forge ExtendedButton) on the AbstractWidget adapter.
package dev.porter.shim.mc.client;

import net.minecraft.util.IChatComponent;

public class Button extends AbstractWidget {
    protected final OnPress onPress;

    public Button(int x, int y, int w, int h, IChatComponent message, OnPress onPress) {
        super(x, y, w, h, message);
        this.onPress = onPress;
    }

    public void onPress() { onPress.onPress(this); }

    @Override
    public void onClick(double mx, double my) { onPress(); }

    @Override
    public boolean keyPressed(int key, int scan, int mods) { return false; }

    public interface OnPress {
        void onPress(Button b);
    }

    /** net.minecraftforge.client.gui.widget.ExtendedButton */
    public static class ExtendedButton extends Button {
        public ExtendedButton(int x, int y, int w, int h, IChatComponent message, OnPress onPress) { super(x, y, w, h, message, onPress); }
    }
}
