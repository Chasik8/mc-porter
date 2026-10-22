package ic2cprofile.extract;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.concepts.Vals;
import java.util.List;
import java.util.Map;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IC2C in-game wiki chapters ({@code WikiBuilder#addChapter(item, objects...)}): there is no IR concept for guide
 * pages yet, so each chapter is an {@code unknown} node carrying the page structure in
 * {@code extensions["ic2c:wikiChapter"]}: the chapter item, and per object its type (HeaderObj, TextObj,
 * MultiBlockObj, PageEndObj...), its lang keys and, for multiblock objects, the IR multiblock id. This is the
 * evidence for an IR concept RFC (book/guide pages).
 */
final class Ic2cWiki {
    static final String ID = "wiki/ic2c-chapter";

    private Ic2cWiki() {
    }

    static int extract(ExtractContext ctx, Map<CtExpression<?>, String> multiblockIds) {
        int n = 0;
        for (CtType<?> t : ctx.model.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                String name = inv.getExecutable().getSimpleName();
                if (!(name.equals("addChapter") || name.equals("addBasicChapter")) || inv.getParent(CtType.class) != t
                        || inv.getArguments().isEmpty()) {
                    continue;
                }
                ObjectNode chapter = Vals.F.objectNode();
                chapter.put("call", name);
                CtExpression<?> first = inv.getArguments().get(0);
                Object item = ctx.interp.evalIn(first, t, null);
                String itemId = ctx.itemIdOf(item);
                if (itemId == null) {
                    itemId = ctx.idOf(item);
                }
                if (itemId != null) {
                    chapter.put("item", itemId);
                } else if (first instanceof CtNewArray<?> arr) {
                    ArrayNode items = Vals.F.arrayNode();
                    for (CtExpression<?> e : arr.getElements()) {
                        String id = ctx.itemIdOf(ctx.interp.evalIn(e, t, null));
                        items.add(id == null ? "?" : id);
                    }
                    chapter.set("items", items);
                } else {
                    chapter.put("itemExpression", first.toString());
                }
                ArrayNode objects = Vals.F.arrayNode();
                for (CtExpression<?> a : inv.getArguments().subList(1, inv.getArguments().size())) {
                    objects.add(object(a, multiblockIds));
                }
                chapter.set("objects", objects);
                String id = itemId != null && Vals.isResourceId(itemId) ? itemId : null;
                ctx.sink.add(null, new IrNode.Unknown(id, "no IR concept for guide-book pages (IC2C wiki chapter)",
                        null, null, ctx.prov(inv, ID), Map.<String, JsonNode>of("ic2c:wikiChapter", chapter)));
                n++;
            }
        }
        return n;
    }

    static JsonNode object(CtExpression<?> a, Map<CtExpression<?>, String> multiblockIds) {
        ObjectNode o = Vals.F.objectNode();
        CtConstructorCall<?> cc = a instanceof CtConstructorCall<?> c ? c
                : a instanceof CtInvocation<?> inv && inv.getTarget() instanceof CtConstructorCall<?> tc ? tc : null;
        if (cc == null) {
            o.put("expression", a.toString().length() > 120 ? a.toString().substring(0, 120) : a.toString());
            return o;
        }
        o.put("type", cc.getType().getSimpleName());
        ArrayNode keys = Vals.F.arrayNode();
        for (CtExpression<?> arg : cc.getArguments()) {
            if (arg instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
                keys.add(s);
            } else if (arg instanceof CtLiteral<?> lit && lit.getValue() instanceof Integer i) {
                o.put("number", i);
            }
            String mb = multiblockIds.get(arg);
            if (mb != null) {
                o.put("multiblock", mb);
            }
        }
        if (!keys.isEmpty()) {
            o.set("keys", keys);
        }
        if (a instanceof CtInvocation<?> inv) {
            o.put("modifier", inv.getExecutable().getSimpleName());
        }
        return o;
    }

    @SuppressWarnings("unused")
    private static List<String> none() {
        return List.of();
    }
}
