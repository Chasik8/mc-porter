package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import tools.jackson.databind.JsonNode;

/**
 * {@code menu} (standard Forge): entries of the menu registry (IForgeMenuType.create / new MenuType). The menu is
 * constructed through its factory on a mock player inventory; slots come from addSlot, the player inventory anchor
 * from the 36 player slots, data slots from addDataSlots, and size/texture from the screen bound with
 * MenuScreens.register.
 */
public final class MenuExtractor {
    public static final String ID = "menu/menu-type";

    private MenuExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        for (Facts.Registration r : ctx.facts.in("menu")) {
            Provenance prov = ctx.prov(r.site(), ID);
            if (r.id() == null) {
                ctx.sink.unknown("menu", null, "menu type registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            Object factory = r.value() instanceof VObj mt && !mt.args.isEmpty() ? mt.args.get(0) : null;
            if (!(factory instanceof V.Fn fn)) {
                ctx.sink.unknown("menu", r.id(), "menu factory not computable", Interpreter.snippet(r.site()), prov);
                continue;
            }
            VObj inv = MenuSupport.inventory();
            MenuSupport.player(inv);
            Object menu = ctx.interp.callFn(fn, List.of(0, inv, new V.Unknown("network buffer")));
            if (!(menu instanceof Obj)) {
                menu = ctx.interp.callFn(fn, List.of(0, inv));
            }
            if (!(menu instanceof Obj m)) {
                ctx.sink.unknown("menu", r.id(), "menu construction failed: " + Vals.reason(menu),
                        Interpreter.snippet(r.site()), prov);
                continue;
            }
            Map<String, JsonNode> ext = new LinkedHashMap<>();
            List<MenuSupport.RawSlot> raw = MenuSupport.slots(m);
            List<IrNode.Menu.Slot> slots = new ArrayList<>();
            boolean partial = m.taint != null;
            for (MenuSupport.RawSlot s : raw) {
                if (s.container() == inv) {
                    continue;
                }
                Integer idx = Vals.integer(s.index());
                Integer x = Vals.integer(s.x());
                Integer y = Vals.integer(s.y());
                if (idx == null || x == null || y == null || idx < 0) {
                    partial = true;
                    continue;
                }
                slots.add(new IrNode.Menu.Slot(idx, x, y, roleOf(ctx, s.slot()), null));
            }
            IrNode.Menu.PlayerInventory pinv = MenuSupport.playerInventory(raw, inv);
            List<IrNode.Menu.DataSlot> data = dataSlots(ctx, m);
            Screen screen = screen(ctx, r.value());
            if (partial) {
                ext.put("porter-extract:partial", Vals.F.stringNode(m.taint == null ? "slot coordinates not computable"
                        : m.taint));
            }
            ctx.sink.add(new IrNode.Menu(r.id(), null, m.typeName(), screen.cls, null,
                    new IrNode.Menu.Size(screen.width, screen.height), screen.texture, slots, pinv,
                    data.isEmpty() ? null : data, null, null, prov, partial ? 0.8 : null, ext.isEmpty() ? null : ext));
        }
    }

    /** Vanilla slot subclasses with a clear role. */
    static String roleOf(ExtractContext ctx, Object slot) {
        String type = slot instanceof VObj vo ? vo.type : slot instanceof Obj o ? o.vanillaSuper : null;
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "net.minecraft.world.inventory.FurnaceResultSlot", "net.minecraft.world.inventory.ResultSlot" -> "output";
            case "net.minecraft.world.inventory.FurnaceFuelSlot" -> "fuel";
            default -> null;
        };
    }

    static List<IrNode.Menu.DataSlot> dataSlots(ExtractContext ctx, Obj m) {
        List<IrNode.Menu.DataSlot> out = new ArrayList<>();
        for (V.Call c : m.vanillaCalls) {
            if (!c.name().equals("addDataSlots") || c.args().size() != 1) {
                continue;
            }
            Object data = c.args().get(0);
            if (data instanceof VObj sd && sd.type.endsWith("SimpleContainerData") && !sd.args.isEmpty()) {
                Integer n = Vals.integer(sd.args.get(0));
                if (n != null) {
                    for (int i = 0; i < n; i++) {
                        out.add(new IrNode.Menu.DataSlot("data_" + i, out.size()));
                    }
                }
            } else if (data instanceof Obj d) {
                Map<Integer, String> names = caseFieldNames(ctx, d.type);
                Object count = ctx.interp.callIfDefined(d, "getCount", List.of());
                Integer n = Vals.integer(count);
                if (n == null) {
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    out.add(new IrNode.Menu.DataSlot(names.getOrDefault(i, "data_" + i), out.size()));
                }
            }
        }
        return out;
    }

    /** case i -> return this.field / field  in ContainerData#get(int): index → field name. */
    static Map<Integer, String> caseFieldNames(ExtractContext ctx, CtType<?> t) {
        Map<Integer, String> names = new LinkedHashMap<>();
        CtMethod<?> get = ctx.interp.sourceMethod(t, "get", 1);
        if (get == null || get.getBody() == null) {
            return names;
        }
        for (CtSwitch<?> sw : get.getBody().getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtSwitch.class))) {
            for (CtCase<?> cs : sw.getCases()) {
                if (cs.getCaseExpressions().size() != 1 || !(cs.getCaseExpressions().get(0) instanceof CtLiteral<?> lit)
                        || !(lit.getValue() instanceof Integer idx)) {
                    continue;
                }
                for (CtStatement st : cs.getStatements()) {
                    if (st instanceof CtReturn<?> ret && ret.getReturnedExpression() instanceof CtFieldAccess<?> fa) {
                        String name = ctx.vanilla.official(fa.getVariable().getSimpleName());
                        if (name.matches("^[a-zA-Z0-9_]+$")) {
                            names.put(idx, name);
                        }
                    }
                }
            }
        }
        return names;
    }

    record Screen(String cls, int width, int height, IrNode.Menu.Texture texture) {
    }

    static Screen screen(ExtractContext ctx, Object menuType) {
        int w = ctx.vanilla.constants().getOrDefault(
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageWidth", 176);
        int h = ctx.vanilla.constants().getOrDefault(
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageHeight", 166);
        for (Facts.ScreenBinding b : ctx.facts.screens) {
            if (b.menuType() != menuType || !(b.screenFactory() instanceof V.Fn fn)) {
                continue;
            }
            VObj inv = MenuSupport.inventory();
            Object screen = ctx.interp.callFn(fn, List.of(new V.Unknown("menu"), inv, new V.Unknown("title")));
            if (!(screen instanceof Obj s)) {
                return new Screen(null, w, h, null);
            }
            Integer sw = Vals.integer(MenuSupport.field(ctx, s, "imageWidth"));
            Integer sh = Vals.integer(MenuSupport.field(ctx, s, "imageHeight"));
            return new Screen(Vals.isClassName(s.typeName()) ? s.typeName() : null, sw != null && sw > 0 ? sw : w,
                    sh != null && sh > 0 ? sh : h, texture(ctx, s.type));
        }
        return new Screen(null, w, h, null);
    }

    /** The single static ResourceLocation of the screen class pointing into textures/gui. */
    static IrNode.Menu.Texture texture(ExtractContext ctx, CtType<?> t) {
        IrNode.Menu.Texture found = null;
        for (CtField<?> f : t.getFields()) {
            if (!f.isStatic() || f.getDefaultExpression() == null) {
                continue;
            }
            Object v = ctx.interp.getStatic(t.getQualifiedName(), f.getSimpleName());
            if (v instanceof V.ResLoc rl && rl.path().startsWith("textures/gui/")) {
                if (found != null) {
                    return null; // several candidates: not certain
                }
                found = new IrNode.Menu.Texture(rl.toString(), null, null, null, null);
            }
        }
        return found;
    }

    @SuppressWarnings("unused")
    private static boolean isExpr(CtExpression<?> e) {
        return e != null;
    }
}
