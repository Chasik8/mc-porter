package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared menu analysis: slots added to a constructed menu object, player inventory anchor. */
public final class MenuSupport {
    public static final String INVENTORY = "net.minecraft.world.entity.player.Inventory";
    public static final String PLAYER = "net.minecraft.world.entity.player.Player";
    public static final String SLOT = "net.minecraft.world.inventory.Slot";

    private MenuSupport() {
    }

    /** A Player mock whose getInventory() returns the given inventory marker. */
    public static VObj player(VObj inventory) {
        VObj p = new VObj(PLAYER, "mock", List.of());
        p.fields.put("inventory", inventory);
        inventory.fields.put("player", p);
        return p;
    }

    public static VObj inventory() {
        return new VObj(INVENTORY, "mock", List.of());
    }

    /** One slot as added to the menu: container value, index, x, y (raw values), and the slot value itself. */
    public record RawSlot(Object container, Object index, Object x, Object y, Object slot, int menuIndex) {
    }

    /** Slots added through addSlot on a menu object, in order. */
    public static List<RawSlot> slots(Obj menu) {
        List<RawSlot> out = new ArrayList<>();
        int i = 0;
        for (V.Call c : menu.vanillaCalls) {
            if (!c.name().equals("addSlot") || c.args().size() != 1) {
                continue;
            }
            Object s = c.args().get(0);
            List<Object> a = null;
            if (s instanceof VObj vo && vo.type.equals(SLOT)) {
                a = vo.args;
            } else if (s instanceof Obj so && SLOT.equals(so.vanillaSuper)) {
                a = so.superArgs;
            } else if (s instanceof VObj vo && vo.args.size() >= 4) {
                a = vo.args; // a vanilla Slot subclass (FurnaceResultSlot...): (container/player, ..., index, x, y)
            }
            if (a == null || a.size() < 4) {
                out.add(new RawSlot(null, null, null, null, s, i++));
                continue;
            }
            int n = a.size();
            out.add(new RawSlot(a.get(0), a.get(n - 3), a.get(n - 2), a.get(n - 1), s, i++));
        }
        return out;
    }

    /** Player inventory anchor from the 27 main slots (container indices 9..35) and the hotbar (0..8). */
    public static IrNode.Menu.PlayerInventory playerInventory(List<RawSlot> slots, Object inv) {
        Integer x = null;
        Integer y = null;
        Integer hotbarY = null;
        int count = 0;
        for (RawSlot s : slots) {
            if (s.container() != inv) {
                continue;
            }
            count++;
            Integer idx = Vals.integer(s.index());
            Integer sx = Vals.integer(s.x());
            Integer sy = Vals.integer(s.y());
            if (idx == null || sx == null || sy == null) {
                return null;
            }
            if (idx == 9) {
                x = sx;
                y = sy;
            }
            if (idx == 0) {
                hotbarY = sy;
            }
        }
        if (count != 36 || x == null || y == null) {
            return null;
        }
        return new IrNode.Menu.PlayerInventory(x, y, hotbarY != null && hotbarY == y + 58 ? null : hotbarY);
    }

    /** Value of a (possibly SRG-named) field stored on a source object by its official name. */
    public static Object field(ExtractContext ctx, Obj o, String official) {
        for (Map.Entry<String, Object> e : o.fields.entrySet()) {
            if (ctx.vanilla.official(e.getKey()).equals(official)) {
                return e.getValue();
            }
        }
        return null;
    }
}
