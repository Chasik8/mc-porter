// porter:adapter (the mod-specific, PRIVATE) - focus handling of the 1.19.2 widget API on 1.7.10.
// 1.7.10 screens do not track a focused widget; focus is a flag on each text field.
package dev.porter.shim.mc;

import dev.porter.shim.mc.client.AbstractWidget;

public final class Widgets {
    private Widgets() {}

    /** AbstractWidget#changeFocus(boolean): the widget becomes focused or not. */
    public static void changeFocus(Object widget, boolean focus) {
        if (widget instanceof AbstractWidget) {
            ((AbstractWidget) widget).setFocused(focus);
        }
    }

    /** Screen#getFocused(): no focus tracking on 1.7.10 screens, so nothing reports as focused. */
    public static Object getFocused(Object screen) {
        return null;
    }

    /** Screen#setInitialFocus(GuiEventListener): focus the widget. */
    public static void setInitialFocus(Object screen, Object widget) {
        changeFocus(widget, true);
    }
}
