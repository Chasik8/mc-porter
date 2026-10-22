package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.PatternPack;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code item}: every entry of the item registry recorded while running registration code (DeferredRegister,
 * IForgeRegistry#register, RegisterEvent, and mod helpers that end in one of these). Fields come from the
 * Item.Properties recorder (stacksTo, durability, rarity, tab, craftRemainder, food) and the class hierarchy
 * (BlockItem → placesBlock).
 */
public final class ItemExtractor {
    public static final String ID = "item/registry";
    static final String ITEM_PROPS = "net.minecraft.world.item.Item$Properties";

    private ItemExtractor() {
    }

    public static void extract(ExtractContext ctx, List<PatternPack> packs) {
        Map<String, String> blockItems = new LinkedHashMap<>();
        List<Object[]> drafts = new ArrayList<>();
        for (Facts.Registration r : ctx.facts.in("item")) {
            if (r.id() == null) {
                ctx.sink.unknown("item", null, "item registered with unknown id: " + r.idUnknown(),
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            Object v = r.value();
            Vals.Construction c = Vals.construction(v);
            if (c == null || c.vanillaType() == null && c.obj() == null) {
                ctx.sink.unknown("item", r.id(), "item value not computable: " + Vals.reason(v),
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), ID));
                continue;
            }
            Drafts.Item d = new Drafts.Item();
            d.id = r.id();
            d.provenance = ctx.prov(r.site(), ID);
            if (c.obj() != null && Vals.isClassName(c.logicClass())) {
                d.logicClass = c.logicClass();
                List<String> newArgs = Vals.sourceConstructorArgs(c.obj());
                if (newArgs != null && !newArgs.isEmpty()) {
                    d.extensions.put(Vals.NEW_ARGS, Vals.strings(newArgs));
                }
            }
            ctx.putHolders(d.extensions, v);
            if (!r.conditions().isEmpty()) {
                d.extensions.put("porter-extract:conditions", Vals.strings(r.conditions()));
            }
            if (c.obj() != null && c.obj().taint != null) {
                d.confidence = 0.8;
                d.extensions.put("porter-extract:partial", Vals.F.stringNode(c.obj().taint));
            }
            String vt = c.vanillaType();
            boolean blockItem = vt != null && (vt.endsWith(".BlockItem") || vt.endsWith(".ItemNameBlockItem")
                    || vt.endsWith(".StandingAndWallBlockItem") || vt.endsWith(".DoubleHighBlockItem")
                    || vt.endsWith(".SignItem") || vt.endsWith(".BedItem") || vt.endsWith(".ScaffoldingBlockItem")
                    || vt.endsWith(".WaterLilyBlockItem") || vt.endsWith(".GameMasterBlockItem"));
            if (blockItem && !c.args().isEmpty()) {
                String bid = ctx.idOf(c.args().get(0));
                if (bid != null) {
                    d.placesBlock = bid;
                    blockItems.putIfAbsent(bid, d.id);
                }
            }
            VObj props = Vals.find(c.args(), ITEM_PROPS);
            if (props != null) {
                applyProperties(ctx, props, d);
            } else if (vt != null) {
                d.extensions.put("porter-extract:vanillaClass", Vals.F.stringNode(vt));
            }
            for (PatternPack p : packs) {
                p.enrichItem(ctx, v, d);
            }
            drafts.add(new Object[] {d, v, blockItem});
        }
        ctx.setBlockItems(blockItems);
        for (Object[] o : drafts) {
            Drafts.Item d = (Drafts.Item) o[0];
            if (d.translationKey == null) {
                d.translationKey = translationKey(ctx, o[1], d, (Boolean) o[2]);
            }
            if (d.translationKey == null && d.placesBlock != null && (Boolean) o[2]) {
                Facts.Registration br = ctx.facts.lookup("block", d.placesBlock);
                if (br != null) {
                    d.translationKey = BlockExtractor.translationKey(ctx, br.value(), d.placesBlock);
                }
            }
            ctx.sink.add(d.build());
        }
    }

    static void applyProperties(ExtractContext ctx, VObj props, Drafts.Item d) {
        for (V.Call call : props.calls) {
            List<Object> a = call.args();
            Object a0 = a.isEmpty() ? null : a.get(0);
            switch (call.name()) {
                case "stacksTo" -> {
                    Integer n = Vals.integer(a0);
                    if (n != null && n >= 1 && n <= 64) {
                        d.maxStackSize = n;
                    }
                }
                case "durability" -> {
                    Integer n = Vals.integer(a0);
                    if (n != null && n >= 0) {
                        d.maxDamage = n;
                        d.maxStackSize = 1; // Item.Properties#durability also sets the stack size to 1
                    }
                }
                case "defaultDurability" -> {
                    Integer n = Vals.integer(a0);
                    if (n != null && n >= 0 && d.maxDamage == null) {
                        d.maxDamage = n;
                    }
                }
                case "rarity" -> {
                    String s = Vals.symbolOf(a0, "Rarity");
                    if (s != null && List.of("common", "uncommon", "rare", "epic").contains(s)) {
                        d.rarity = s;
                    }
                }
                case "tab" -> d.creativeTab = tabName(a0);
                case "craftRemainder" -> d.containerItem = ctx.itemIdOf(a0);
                case "food" -> d.food = food(ctx, a0);
                case "fireResistant", "setNoRepair" -> d.extensions.put("porter-extract:" + call.name(),
                        Vals.F.booleanNode(true));
                default -> {
                }
            }
        }
        if (!props.calls.isEmpty()) {
            d.extensions.put("porter-extract:properties", Vals.calls(props.calls));
        }
    }

    /** Creative tab: label of a mod tab (CreativeModeTab subclass constructed with a label), or vanilla tab field. */
    public static String tabName(Object tab) {
        if (tab instanceof V.Sym s) {
            return s.toString();
        }
        if (tab instanceof Obj o) {
            if (!o.superArgs.isEmpty() && o.superArgs.get(0) instanceof String label) {
                return label;
            }
            return o.typeName();
        }
        return null;
    }

    static IrNode.Item.Food food(ExtractContext ctx, Object foodValue) {
        if (!(foodValue instanceof VObj f) || f.args.isEmpty() || !(f.args.get(0) instanceof VObj b)) {
            return null;
        }
        Integer nutrition = null;
        Double saturation = null;
        Boolean always = null;
        List<IrNode.Item.FoodEffect> effects = new ArrayList<>();
        for (V.Call c : b.calls) {
            Object a0 = c.args().isEmpty() ? null : c.args().get(0);
            switch (c.name()) {
                case "nutrition" -> nutrition = Vals.integer(a0);
                case "saturationMod" -> saturation = Vals.number(a0);
                case "alwaysEat" -> always = true;
                case "effect" -> {
                    Object inst = a0 instanceof V.Fn fn ? ctx.interp.callFn(fn, List.of()) : a0;
                    Double prob = c.args().size() > 1 ? Vals.number(c.args().get(1)) : null;
                    IrNode.Item.FoodEffect fe = effect(ctx, inst, prob);
                    if (fe == null) {
                        return null; // an effect we cannot read: the food is not certain
                    }
                    effects.add(fe);
                }
                default -> {
                }
            }
        }
        if (nutrition == null || saturation == null) {
            return null;
        }
        return new IrNode.Item.Food(nutrition, saturation, always, null, effects.isEmpty() ? null : effects);
    }

    static IrNode.Item.FoodEffect effect(ExtractContext ctx, Object inst, Double prob) {
        if (!(inst instanceof VObj mi) || !mi.type.endsWith("MobEffectInstance") || mi.args.size() < 2) {
            return null;
        }
        String eid = ctx.idOf(mi.args.get(0));
        Integer dur = Vals.integer(mi.args.get(1));
        Integer amp = mi.args.size() > 2 ? Vals.integer(mi.args.get(2)) : null;
        if (eid == null || dur == null || mi.args.size() > 2 && amp == null) {
            return null;
        }
        return new IrNode.Item.FoodEffect(eid, dur, amp, prob);
    }

    static String translationKey(ExtractContext ctx, Object v, Drafts.Item d, boolean blockItem) {
        if (v instanceof Obj o) {
            Object r = ctx.interp.callIfDefined(o, "getDescriptionId", List.of());
            if (r != null) {
                return r instanceof String s && !s.isEmpty() ? s : null;
            }
            if (blockItem || MBlockItems.isBlockItemClass(ctx, o)) {
                return null; // BlockItem uses the block's key; filled by the block extractor
            }
        }
        if (blockItem) {
            return null;
        }
        // Item#getDescriptionId → Util.makeDescriptionId("item", registry name)
        return "item." + d.id.replace(':', '.');
    }

    /** Small helper namespace to keep BlockItem detection in one place. */
    static final class MBlockItems {
        static boolean isBlockItemClass(ExtractContext ctx, Obj o) {
            for (String s : ctx.interp.supertypeNames(o.type)) {
                if (s.equals("net.minecraft.world.item.BlockItem")) {
                    return true;
                }
            }
            return false;
        }
    }
}
