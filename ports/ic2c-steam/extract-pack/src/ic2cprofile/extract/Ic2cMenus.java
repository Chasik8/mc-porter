package ic2cprofile.extract;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.concepts.MenuSupport;
import dev.porter.extract.forge1192.concepts.Vals;
import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * IC2C GUIs: a block entity implementing IHasGui creates its container in {@code createContainer(player, hand, side,
 * windowId)}; containers extend ContainerComponent: slots via addSlot, the player inventory via
 * addPlayerInventory, the background via getTexture(), size changes in onGuiLoaded(screen), and gauges as GUI
 * components (TankComponent, FuelComponent, ProgressComponent, ChargeBarComponent: Box2i target rect + Vec2i
 * sprite offset). The menu id is the block entity id.
 */
final class Ic2cMenus {
    static final String ID = "menu/ic2c-container";

    private Ic2cMenus() {
    }

    /** Container of a block entity instance, or null if it has no IC2C GUI. */
    static Object container(ExtractContext ctx, Obj be, VObj inv) {
        CtMethod<?> m = ctx.interp.sourceMethod(be.type, "createContainer", 4);
        if (m == null) {
            return null;
        }
        VObj player = MenuSupport.player(inv);
        return ctx.interp.call(m, be, List.of(player, new V.Sym("net.minecraft.world.InteractionHand", "MAIN_HAND"),
                new V.Unknown("side"), 0));
    }

    static void extract(ExtractContext ctx, Map<String, Obj> beInstances) {
        int w = ctx.vanilla.constants().getOrDefault(
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageWidth", 176);
        int h = ctx.vanilla.constants().getOrDefault(
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageHeight", 166);
        for (Map.Entry<String, Obj> e : beInstances.entrySet()) {
            String beId = e.getKey();
            Obj be = e.getValue();
            VObj inv = MenuSupport.inventory();
            Object c = container(ctx, be, inv);
            if (c == null) {
                continue;
            }
            Facts.Registration reg = ctx.facts.lookup("block_entity_type", beId);
            Provenance prov = ctx.prov(be.type, ID);
            if (!(c instanceof Obj menu)) {
                ctx.sink.unknown("menu", beId, "container construction failed: " + Vals.reason(c), be.typeName(), prov);
                continue;
            }
            prov = ctx.prov(menu.type, ID);
            Map<String, JsonNode> ext = new LinkedHashMap<>();
            boolean partial = menu.taint != null;
            List<IrNode.Menu.Slot> slots = new ArrayList<>();
            int filterSlots = 0;
            for (MenuSupport.RawSlot s : MenuSupport.slots(menu)) {
                if (s.container() == inv) {
                    continue;
                }
                if (s.slot() instanceof Obj so && so.type.getSimpleName().equals("SettingsSlot")) {
                    filterSlots++;
                    continue;
                }
                Integer idx = Vals.integer(s.index());
                Integer x = Vals.integer(s.x());
                Integer y = Vals.integer(s.y());
                if (idx == null || x == null || y == null || idx < 0) {
                    partial = true;
                    continue;
                }
                slots.add(new IrNode.Menu.Slot(idx, x, y, role(ctx, s.slot()), null));
            }
            if (filterSlots > 0) {
                ext.put("ic2c:settingsSlots", Vals.F.numberNode(filterSlots));
            }
            ext.put("porter:titleAlign", Vals.F.stringNode("center")); // IC2Screen draws the title centred
            IrNode.Menu.PlayerInventory pinv = MenuSupport.playerInventory(MenuSupport.slots(menu), inv);
            IrNode.Menu.Texture tex = null;
            Object t = ctx.interp.callIfDefined(menu, "getTexture", List.of());
            if (t instanceof V.ResLoc rl && Vals.isResourceId(rl.toString())) {
                tex = new IrNode.Menu.Texture(rl.toString(), null, null, null, null);
            }
            int[] size = {w, h};
            VObj screen = DataBuiltins.recorder();
            if (ctx.interp.callIfDefined(menu, "onGuiLoaded", List.of(screen)) instanceof V.Unknown u) {
                partial = true;
                ext.put("ic2c:onGuiLoaded", Vals.F.stringNode(u.reason()));
            }
            for (V.Call call : screen.calls) {
                Integer a0 = call.args().isEmpty() ? null : Vals.integer(call.args().get(0));
                Integer a1 = call.args().size() < 2 ? null : Vals.integer(call.args().get(1));
                switch (call.name()) {
                    case "setMaxSize" -> {
                        if (a0 != null && a1 != null) {
                            size[0] = a0;
                            size[1] = a1;
                        } else {
                            partial = true;
                        }
                    }
                    case "setXSize" -> {
                        if (a0 != null) {
                            size[0] = a0;
                        }
                    }
                    case "setYSize" -> {
                        if (a0 != null) {
                            size[1] = a0;
                        }
                    }
                    case "modifySize" -> {
                        if (a0 != null && a1 != null) {
                            size[0] += a0;
                            size[1] += a1;
                        } else {
                            partial = true;
                        }
                    }
                    default -> {
                    }
                }
            }
            List<IrNode.Menu.Bar> bars = new ArrayList<>();
            ArrayNode other = Vals.F.arrayNode();
            Object comps = MenuFields.field(ctx, menu, "components");
            if (comps instanceof V.Seq seq) {
                List<Object> ordered = new ArrayList<>(seq.items);
                java.util.Collections.reverse(ordered); // addComponent inserts at index 0
                Set<String> ids = new HashSet<>();
                for (Object comp : ordered) {
                    IrNode.Menu.Bar bar = comp instanceof Obj co ? bar(ctx, co, ids) : null;
                    if (bar != null) {
                        bars.add(bar);
                    } else if (comp instanceof Obj co) {
                        other.add(co.type.getSimpleName());
                    }
                }
            } else {
                partial = true;
            }
            if (!other.isEmpty()) {
                ext.put("ic2c:components", other);
            }
            if (partial && menu.taint != null) {
                ext.put("porter-extract:partial", Vals.F.stringNode(menu.taint));
            }
            ctx.sink.add(new IrNode.Menu(beId, null, menu.typeName(), null, beId, new IrNode.Menu.Size(size[0], size[1]),
                    tex, slots, pinv, null, bars.isEmpty() ? null : bars, null, prov, partial ? 0.8 : null,
                    ext.isEmpty() ? null : ext));
            if (reg == null) {
                ctx.note(ID, beId + ": no registration found for the block entity");
            }
        }
    }

    static String role(ExtractContext ctx, Object slot) {
        if (!(slot instanceof Obj so)) {
            return null;
        }
        if (so.type.getSimpleName().equals("UpgradeSlot")) {
            return "upgrade";
        }
        // FilterSlot factories: the enclosing method of the `new` names the role
        if (so.site instanceof CtConstructorCall<?> cc) {
            CtMethod<?> m = cc.getParent(CtMethod.class);
            if (m != null) {
                String n = m.getSimpleName();
                if (n.equals("createOutputSlot") || n.equals("createFluidOutputSlot")) {
                    return "output";
                }
                if (n.startsWith("createCharge") || n.startsWith("createDischarge") || n.contains("Battery")) {
                    return "battery";
                }
            }
        }
        return null;
    }

    /** Gauge components → bars; null for anything else. */
    static IrNode.Menu.Bar bar(ExtractContext ctx, Obj comp, Set<String> ids) {
        String simple = comp.type.getSimpleName();
        String kind = switch (simple) {
            case "TankComponent" -> "tank";
            case "FuelComponent" -> "fuel";
            case "ProgressComponent" -> "progress";
            case "ChargeBarComponent" -> "energy";
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        Object box = MenuFields.field(ctx, comp, "box");
        Object sprite = MenuFields.field(ctx, comp, kind.equals("tank") ? "pos" : "offset");
        int[] r = box instanceof Obj b ? ints(ctx, b, "xCoord", "yCoord", "width", "height") : null;
        int[] uv = sprite instanceof Obj s ? ints(ctx, s, "x", "y") : null;
        if (r == null || uv == null || r[2] < 1 || r[3] < 1 || uv[0] < 0 || uv[1] < 0) {
            return null;
        }
        String direction;
        String value = kind;
        if (kind.equals("tank")) {
            direction = "up";
            String tank = argName(comp, 2);
            if (tank == null) {
                tank = argName(comp, 1);
            }
            if (tank != null) {
                value = tank;
            }
        } else {
            Object vertical = MenuFields.field(ctx, comp, "vertical");
            if (!(vertical instanceof Boolean v)) {
                return null;
            }
            direction = v ? "up" : "right";
        }
        String id = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        String base = id;
        for (int i = 2; !ids.add(id); i++) {
            id = base + "_" + i;
        }
        return new IrNode.Menu.Bar(id, r[0], r[1], r[2], r[3], direction, uv[0], uv[1], value.matches("^[A-Za-z0-9_]+$")
                ? value : id, null);
    }

    /** Field name of the i-th constructor argument at the component's creation site (key.waterTank → waterTank). */
    static String argName(Obj comp, int i) {
        if (comp.site instanceof CtConstructorCall<?> cc && cc.getArguments().size() > i) {
            CtExpression<?> e = cc.getArguments().get(i);
            if (e instanceof CtFieldAccess<?> fa) {
                return fa.getVariable().getSimpleName();
            }
            if (e instanceof CtInvocation<?> inv && inv.getArguments().isEmpty()) {
                String n = inv.getExecutable().getSimpleName();
                return n.startsWith("get") && n.length() > 3 ? Character.toLowerCase(n.charAt(3)) + n.substring(4) : n;
            }
        }
        return null;
    }

    static int[] ints(ExtractContext ctx, Obj o, String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            Integer v = Vals.integer(MenuFields.field(ctx, o, names[i]));
            if (v == null) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }
}
