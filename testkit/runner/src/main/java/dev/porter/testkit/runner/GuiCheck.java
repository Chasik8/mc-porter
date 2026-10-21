package dev.porter.testkit.runner;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Compares a dumped GUI (trace {@code gui} entry: size + all container slots, player slots flagged) with the IR
 * {@code menu} node: background size, every machine slot by index, and the player inventory grid
 * (3x9 at playerInventory.x/y, hotbar at hotbarY, 18 px pitch). Coordinates are relative to the GUI's top-left.
 */
public final class GuiCheck {
    private GuiCheck() {
    }

    public static List<String> compare(JsonNode menu, JsonNode dump) {
        List<String> diffs = new ArrayList<>();
        JsonNode size = menu.path("size");
        JsonNode dsize = dump.path("size");
        if (size.path("width").asInt() != dsize.path("width").asInt() || size.path("height").asInt() != dsize.path("height").asInt()) {
            diffs.add("size expected " + size.path("width").asInt() + "x" + size.path("height").asInt()
                    + ", found " + dsize.path("width").asInt() + "x" + dsize.path("height").asInt());
        }
        List<JsonNode> machine = new ArrayList<>();
        List<JsonNode> player = new ArrayList<>();
        for (JsonNode s : dump.path("slots")) {
            (s.path("player").asBoolean(false) ? player : machine).add(s);
        }
        int expectedMachine = 0;
        for (JsonNode s : menu.path("slots")) {
            expectedMachine++;
            int idx = s.path("index").asInt();
            JsonNode found = null;
            for (JsonNode d : machine) {
                if (d.path("index").asInt() == idx) {
                    found = d;
                }
            }
            if (found == null) {
                diffs.add("slot " + idx + " missing");
            } else if (found.path("x").asInt() != s.path("x").asInt() || found.path("y").asInt() != s.path("y").asInt()) {
                diffs.add("slot " + idx + " expected at (" + s.path("x").asInt() + "," + s.path("y").asInt() + "), found ("
                        + found.path("x").asInt() + "," + found.path("y").asInt() + ")");
            }
        }
        if (machine.size() != expectedMachine) {
            diffs.add("machine slot count expected " + expectedMachine + ", found " + machine.size());
        }
        JsonNode pi = menu.path("playerInventory");
        if (pi.isObject()) {
            int px = pi.path("x").asInt();
            int py = pi.path("y").asInt();
            int hy = pi.path("hotbarY").asInt(py + 58);
            if (player.size() != 36) {
                diffs.add("player slots expected 36, found " + player.size());
            } else {
                List<String> bad = new ArrayList<>();
                for (int i = 0; i < 36; i++) {
                    JsonNode s = player.get(i);
                    int ex = px + (i % 9) * 18;
                    int ey = i < 27 ? py + (i / 9) * 18 : hy;
                    if (s.path("x").asInt() != ex || s.path("y").asInt() != ey) {
                        bad.add("#" + s.path("index").asInt() + " (" + s.path("x").asInt() + "," + s.path("y").asInt() + ") != (" + ex + "," + ey + ")");
                    }
                }
                if (!bad.isEmpty()) {
                    diffs.add("player inventory: " + String.join(", ", bad.subList(0, Math.min(4, bad.size())))
                            + (bad.size() > 4 ? " ... (" + bad.size() + " slots)" : ""));
                }
            }
        }
        return diffs;
    }
}
