// porter:adapter (generic, client) - 1.19.2 EditBox (a text field AbstractWidget) over a 1.7.10 GuiTextField.
package dev.porter.shim.mc.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.IChatComponent;

public class EditBox extends AbstractWidget {
    public final GuiTextField field;
    private Consumer<String> responder;
    private String last = "";

    public EditBox(Font font, int x, int y, int w, int h, IChatComponent message) {
        super(x, y, w, h, message);
        this.field = new GuiTextField(font.renderer(), x, y, w, h);
    }

    public void setResponder(Consumer<String> r) { this.responder = r; }

    public void setValue(String s) {
        field.setText(s);
        fire();
    }

    public String getValue() { return field.getText(); }

    public void setMaxLength(int n) { field.setMaxStringLength(n); }

    public void setBordered(boolean b) { field.setEnableBackgroundDrawing(b); }

    public void setTextColor(int c) { field.setTextColor(c); }

    public void setFocus(boolean f) { setFocused(f); }

    // 1.7.10 GuiTextField names (rules that map EditBox to GuiTextField)
    public void setMaxStringLength(int n) { field.setMaxStringLength(n); }

    public void setEnableBackgroundDrawing(boolean b) { field.setEnableBackgroundDrawing(b); }

    public void setCanLoseFocus(boolean b) { field.setCanLoseFocus(b); }

    @Override
    public void setFocused(boolean f) {
        super.setFocused(f);
        field.setFocused(f);
    }

    @Override
    public boolean isFocused() { return field.isFocused(); }

    public void setVisible(boolean v) {
        this.visible = v;
        field.setVisible(v);
    }

    public void setEditable(boolean e) { field.setEnabled(e); }

    public void moveCursorToStart() { field.setCursorPositionZero(); }

    public void moveCursorToEnd() { field.setCursorPositionEnd(); }

    public void tick() { field.updateCursorCounter(); }

    private void fire() {
        if (responder != null && !field.getText().equals(last)) {
            last = field.getText();
            responder.accept(last);
        }
    }

    @Override
    public void render(PoseStack p, int mx, int my, float pt) {
        if (!visible) return;
        field.xPosition = x;
        field.yPosition = y;
        field.drawTextBox();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (!visible) return false;
        field.mouseClicked((int) mx, (int) my, b);
        super.setFocused(field.isFocused());
        return field.isFocused();
    }

    @Override
    public boolean charTyped(char c, int mods) {
        boolean r = field.isFocused() && field.textboxKeyTyped(c, 0);
        fire();
        return r;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean r = field.isFocused() && field.textboxKeyTyped((char) 0, key);
        fire();
        return r;
    }
}
