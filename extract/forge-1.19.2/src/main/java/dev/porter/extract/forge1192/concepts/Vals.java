package dev.porter.extract.forge1192.concepts;

import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Helpers to read interpreter values into IR fields. Every getter returns null for anything not certain. */
public final class Vals {
    private Vals() {
    }

    public static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** How a registered value was constructed: the vanilla class it is (or extends) and the arguments into it. */
    public record Construction(String vanillaType, List<Object> args, String logicClass, Obj obj, VObj vobj) {
    }

    public static Construction construction(Object v) {
        if (v instanceof Obj o) {
            return new Construction(o.vanillaSuper, o.superArgs, o.typeName(), o, null);
        }
        if (v instanceof VObj vo) {
            return new Construction(vo.type, vo.args, null, null, vo);
        }
        return null;
    }

    public static VObj find(List<Object> args, String type) {
        for (Object a : args) {
            if (a instanceof VObj vo && vo.type.equals(type)) {
                return vo;
            }
        }
        return null;
    }

    public static Integer integer(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return (int) (long) l;
        }
        if (v instanceof Character c) {
            return (int) c;
        }
        return null;
    }

    public static Double number(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (v instanceof Float f) {
                // keep the float literal as written (1.5f → 1.5, not 1.5000000xxx)
                return Double.parseDouble(Float.toString(f));
            }
            return d;
        }
        return null;
    }

    public static String string(Object v) {
        return v instanceof String s ? s : null;
    }

    public static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    /** Lower-case name of a symbolic constant (Rarity.EPIC → epic), or of a source enum constant. */
    public static String symbol(Object v) {
        if (v instanceof V.Sym s) {
            return s.name().toLowerCase(Locale.ROOT);
        }
        if (v instanceof Obj o && o.enumName != null) {
            return o.enumName.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    public static String symbolOf(Object v, String ownerSuffix) {
        if (v instanceof V.Sym s && (s.owner().endsWith(ownerSuffix))) {
            return s.name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    public static boolean isResourceId(String s) {
        return s != null && s.matches("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    }

    public static boolean isClassName(String s) {
        return s != null && s.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
    }

    public static String reason(Object v) {
        return v instanceof V.Unknown u ? u.reason() : "not computable: " + Interpreter.describe(v);
    }

    public static JsonNode strings(List<String> l) {
        ArrayNode a = F.arrayNode();
        l.forEach(a::add);
        return a;
    }

    /** Readable JSON form of a recorded builder chain (informative extensions). */
    public static JsonNode calls(List<V.Call> calls) {
        ArrayNode arr = F.arrayNode();
        for (V.Call c : calls) {
            ObjectNode o = F.objectNode();
            o.put("call", c.name());
            ArrayNode args = F.arrayNode();
            for (Object a : c.args()) {
                args.add(describeArg(a));
            }
            if (!args.isEmpty()) {
                o.set("args", args);
            }
            arr.add(o);
        }
        return arr;
    }

    public static String describeArg(Object a) {
        if (a instanceof V.Unknown) {
            return "?";
        }
        if (a instanceof String s) {
            return "\"" + s + "\"";
        }
        if (a instanceof V.Fn) {
            return "<fn>";
        }
        if (a instanceof Obj o) {
            return o.enumName != null ? o.type.getSimpleName() + "." + o.enumName : "<" + o.type.getSimpleName() + ">";
        }
        return String.valueOf(a);
    }

    public static List<Object> flatten(List<Object> args) {
        List<Object> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof V.Seq s && s.kind.equals("array")) {
                out.addAll(s.items);
            } else {
                out.add(a);
            }
        }
        return out;
    }
}
