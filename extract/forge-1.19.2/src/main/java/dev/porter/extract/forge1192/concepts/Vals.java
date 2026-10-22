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

    /** Extension: source arguments of the logic class's constructor call ({@link #sourceConstructorArgs}). */
    public static final String NEW_ARGS = "porter-extract:newArgs";

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

    /**
     * The arguments of the {@code new} expression that created a source object, as Java source with fully qualified
     * names ({@code "cup_tea"}, {@code com.example.mod.Drinks.TEA}), or null when an argument
     * is not a literal, a static field, an enum constant or a nested {@code new} of such arguments (anonymous classes,
     * locals, method calls: the construction cannot be repeated on the target side). Empty for a no-argument call.
     */
    public static List<String> sourceConstructorArgs(Obj o) {
        if (o == null || !(o.site instanceof spoon.reflect.code.CtConstructorCall<?> cc) || cc instanceof spoon.reflect.code.CtNewClass<?>) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (spoon.reflect.code.CtExpression<?> a : cc.getArguments()) {
            String s = sourceExpr(a);
            if (s == null) {
                return null;
            }
            out.add(s);
        }
        return out;
    }

    static String sourceExpr(spoon.reflect.code.CtExpression<?> e) {
        if (!e.getTypeCasts().isEmpty()) {
            return null;
        }
        if (e instanceof spoon.reflect.code.CtLiteral<?> l) {
            Object v = l.getValue();
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) {
                return l.toString();
            }
            return null;
        }
        if (e instanceof spoon.reflect.code.CtFieldRead<?> f && f.getVariable().isStatic() && f.getVariable().getDeclaringType() != null) {
            String owner = f.getVariable().getDeclaringType().getQualifiedName();
            return isClassName(owner) ? owner.replace('$', '.') + "." + f.getVariable().getSimpleName() : null;
        }
        if (e instanceof spoon.reflect.code.CtConstructorCall<?> c && !(c instanceof spoon.reflect.code.CtNewClass<?>)
                && c.getType() != null && c.getType().getActualTypeArguments().isEmpty()) {
            String t = c.getType().getQualifiedName();
            if (!isClassName(t)) {
                return null;
            }
            List<String> args = new ArrayList<>();
            for (spoon.reflect.code.CtExpression<?> a : c.getArguments()) {
                String s = sourceExpr(a);
                if (s == null) {
                    return null;
                }
                args.add(s);
            }
            return "new " + t.replace('$', '.') + "(" + String.join(", ", args) + ")";
        }
        return null;
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
