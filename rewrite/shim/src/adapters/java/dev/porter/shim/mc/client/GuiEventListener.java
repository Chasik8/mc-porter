// porter:adapter (generic, client) - 1.19.2 GuiEventListener.
package dev.porter.shim.mc.client;

public interface GuiEventListener {
    default boolean mouseClicked(double mx, double my, int button) { return false; }

    default boolean mouseReleased(double mx, double my, int button) { return false; }

    default boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return false; }

    default boolean mouseScrolled(double mx, double my, double delta) { return false; }

    default boolean keyPressed(int key, int scan, int mods) { return false; }

    default boolean charTyped(char c, int mods) { return false; }

    default boolean isMouseOver(double mx, double my) { return false; }

    default void mouseMoved(double mx, double my) {}
}
