package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.Fn;
import dev.porter.extract.forge1192.interp.V.MapV;
import dev.porter.extract.forge1192.interp.V.Seq;
import dev.porter.extract.forge1192.interp.V.Unknown;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import spoon.reflect.declaration.CtElement;

/** java.lang / java.util (and guava/fastutil collection look-alikes) for the interpreter. */
public final class JdkBuiltins implements Builtins {

    @Override
    public Object construct(Interpreter in, String type, List<Object> args, CtElement site) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        if (type.startsWith("java.util.") || type.startsWith("it.unimi.dsi.fastutil.")
                || type.startsWith("com.google.common.collect.")) {
            if (simple.contains("Map")) {
                MapV m = new MapV();
                if (!args.isEmpty() && args.get(0) instanceof MapV src) {
                    m.map.putAll(src.map);
                }
                return m;
            }
            if (simple.contains("List") || simple.contains("Set") || simple.contains("Deque")
                    || simple.contains("Queue") || simple.equals("Vector")) {
                List<Object> items = new ArrayList<>();
                if (!args.isEmpty() && args.get(0) instanceof Seq src) {
                    items.addAll(src.items);
                }
                return new Seq(simple.contains("Set") ? "set" : "list", items);
            }
        }
        if (type.equals("java.lang.StringBuilder") || type.equals("java.lang.StringBuffer")) {
            V.VObj sb = new V.VObj("java.lang.StringBuilder", null, List.of());
            String init = args.isEmpty() ? "" : args.get(0) instanceof String s ? s : "";
            if (!args.isEmpty() && !(args.get(0) instanceof String) && !(args.get(0) instanceof Number)) {
                sb.fields.put("value", new Unknown("StringBuilder init"));
            } else {
                sb.fields.put("value", init);
            }
            return sb;
        }
        if (type.equals("java.lang.String") && args.size() == 1 && args.get(0) instanceof String s) {
            return s;
        }
        return NOT_HANDLED;
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        Object t = c.target();
        String n = c.name();
        List<Object> a = c.args();
        if (t == null) {
            return staticCall(in, c);
        }
        if (t instanceof String s) {
            return stringCall(in, s, n, a);
        }
        if (t instanceof V.VObj sb && sb.type.equals("java.lang.StringBuilder")) {
            Object cur = sb.fields.get("value");
            if (n.equals("append") && a.size() == 1) {
                String add = in.str(a.get(0));
                sb.fields.put("value", cur instanceof String cs && add != null ? cs + add
                        : new Unknown("StringBuilder.append of " + Interpreter.describe(a.get(0))));
                return sb;
            }
            if (n.equals("toString")) {
                return cur;
            }
            return NOT_HANDLED;
        }
        if (t instanceof Seq s) {
            return seqCall(in, s, n, a);
        }
        if (t instanceof V.ClassV cv) {
            return switch (n) {
                case "isInstance" -> a.size() == 1 ? in.instanceOf(a.get(0), cv.qualifiedName()) : NOT_HANDLED;
                case "cast" -> a.size() == 1 ? a.get(0) : NOT_HANDLED;
                case "getSimpleName" -> cv.qualifiedName().substring(Math.max(cv.qualifiedName().lastIndexOf('.'),
                        cv.qualifiedName().lastIndexOf('$')) + 1);
                case "getName" -> cv.qualifiedName();
                default -> NOT_HANDLED;
            };
        }
        if (t instanceof MapV m) {
            return mapCall(in, m, n, a);
        }
        if (t instanceof V.Entry e) {
            return switch (n) {
                case "getKey", "getLongKey", "getIntKey" -> e.key;
                case "getValue" -> e.owner.map.getOrDefault(e.key, V.NULL);
                case "setValue" -> {
                    Object old = e.owner.map.put(e.key, a.get(0));
                    yield old == null ? V.NULL : old;
                }
                default -> NOT_HANDLED;
            };
        }
        if (t instanceof V.Iter it) {
            return switch (n) {
                case "hasNext" -> it.index < it.items.size();
                case "next" -> it.index < it.items.size() ? it.items.get(it.index++) : new Unknown("iterator exhausted");
                default -> NOT_HANDLED;
            };
        }
        if (t instanceof V.VObj o && o.type.equals("java.util.Optional")) {
            Object v = o.fields.get("value");
            return switch (n) {
                case "get", "orElseThrow" -> v == null ? new Unknown("Optional.empty().get()") : v;
                case "isPresent" -> v != null;
                case "isEmpty" -> v == null;
                case "orElse" -> v == null ? a.get(0) : v;
                default -> NOT_HANDLED;
            };
        }
        if (t instanceof Number num) {
            return switch (n) {
                case "intValue" -> num.intValue();
                case "longValue" -> num.longValue();
                case "floatValue" -> num.floatValue();
                case "doubleValue" -> num.doubleValue();
                case "equals" -> V.same(num, a.get(0));
                case "toString" -> String.valueOf(num);
                default -> NOT_HANDLED;
            };
        }
        if (t instanceof Boolean b && n.equals("booleanValue")) {
            return b;
        }
        if (t instanceof Character ch) {
            return n.equals("charValue") ? ch : n.equals("toString") ? String.valueOf(ch) : NOT_HANDLED;
        }
        if (n.equals("equals") && a.size() == 1 && !(t instanceof V.Obj) && !(t instanceof Unknown)) {
            Object o = a.get(0);
            if (o instanceof Unknown u) {
                return u;
            }
            return t.equals(o);
        }
        return NOT_HANDLED;
    }

    private Object staticCall(Interpreter in, Call c) {
        String owner = c.owner() == null ? "" : c.owner();
        String n = c.name();
        List<Object> a = c.args();
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        switch (simple) {
            case "Integer", "Short", "Byte" -> {
                if ((n.equals("valueOf") || n.equals("parseInt")) && a.size() == 1) {
                    Object v = a.get(0);
                    if (v instanceof Number num) {
                        return num.intValue();
                    }
                    if (v instanceof String s) {
                        try {
                            return Integer.parseInt(s.trim());
                        } catch (NumberFormatException e) {
                            return new Unknown("parseInt(" + s + ")");
                        }
                    }
                    return passUnknown(v, "Integer." + n);
                }
                if (n.equals("toString") && a.size() == 1 && a.get(0) instanceof Number num) {
                    return Integer.toString(num.intValue());
                }
                if ((n.equals("max") || n.equals("min")) && a.size() == 2) {
                    return mathCall(n, a);
                }
            }
            case "Long" -> {
                if (n.equals("valueOf") && a.size() == 1 && a.get(0) instanceof Number num) {
                    return num.longValue();
                }
            }
            case "Float" -> {
                if (n.equals("valueOf") && a.size() == 1 && a.get(0) instanceof Number num) {
                    return num.floatValue();
                }
            }
            case "Double" -> {
                if (n.equals("valueOf") && a.size() == 1 && a.get(0) instanceof Number num) {
                    return num.doubleValue();
                }
            }
            case "Boolean" -> {
                if (n.equals("valueOf") && a.size() == 1) {
                    return a.get(0) instanceof Boolean ? a.get(0)
                            : a.get(0) instanceof String s ? Boolean.parseBoolean(s) : passUnknown(a.get(0), "Boolean.valueOf");
                }
            }
            case "Character" -> {
                if (n.equals("valueOf") && a.size() == 1) {
                    return a.get(0) instanceof Character ? a.get(0)
                            : a.get(0) instanceof Number num ? (Object) (char) num.intValue()
                            : passUnknown(a.get(0), "Character.valueOf");
                }
            }
            case "String" -> {
                if (n.equals("valueOf") && a.size() == 1) {
                    String s = in.str(a.get(0));
                    return s == null ? passUnknown(a.get(0), "String.valueOf") : s;
                }
                if (n.equals("format") && !a.isEmpty() && a.get(0) instanceof String fmt) {
                    return format(in, fmt, a.subList(1, a.size()));
                }
                if (n.equals("join") && a.size() >= 2 && a.get(0) instanceof String sep) {
                    List<String> parts = new ArrayList<>();
                    List<Object> items = a.size() == 2 && a.get(1) instanceof Seq s ? s.items : a.subList(1, a.size());
                    for (Object o : items) {
                        String ps = in.str(o);
                        if (ps == null) {
                            return passUnknown(o, "String.join");
                        }
                        parts.add(ps);
                    }
                    return String.join(sep, parts);
                }
            }
            case "Math", "Mth" -> {
                if (owner.equals("java.lang.Math") || owner.equals("Math")) {
                    return mathCall(n, a);
                }
                if (owner.equals("net.minecraft.util.Mth")) {
                    if (n.equals("clamp") && a.size() == 3 && V.firstUnknown(a) == null) {
                        Number v = Interpreter.num(a.get(0));
                        Number lo = Interpreter.num(a.get(1));
                        Number hi = Interpreter.num(a.get(2));
                        if (v == null || lo == null || hi == null) {
                            return NOT_HANDLED;
                        }
                        if (a.get(0) instanceof Integer && a.get(1) instanceof Integer && a.get(2) instanceof Integer) {
                            return Math.max(lo.intValue(), Math.min(hi.intValue(), v.intValue()));
                        }
                        double r = Math.max(lo.doubleValue(), Math.min(hi.doubleValue(), v.doubleValue()));
                        return a.get(0) instanceof Float ? (Object) (float) r : (Object) r;
                    }
                    if ((n.equals("floor") || n.equals("ceil")) && a.size() == 1 && Interpreter.num(a.get(0)) != null) {
                        double d = Interpreter.num(a.get(0)).doubleValue();
                        return (int) (n.equals("floor") ? Math.floor(d) : Math.ceil(d));
                    }
                    if (n.equals("abs")) {
                        return mathCall(n, a);
                    }
                }
            }
            case "Objects" -> {
                if (n.equals("requireNonNull") || n.equals("requireNonNullElse") && a.get(0) != V.NULL) {
                    return a.get(0);
                }
                if (n.equals("equals") && a.size() == 2) {
                    return V.same(a.get(0), a.get(1)) || a.get(0).equals(a.get(1));
                }
            }
            case "List", "Set", "ImmutableList", "ImmutableSet", "Lists", "Sets", "Arrays", "Collections",
                    "ObjectLists", "ObjectArrayList", "ObjectSets", "EnumSet", "Stream" -> {
                Object r = collectionFactory(simple, n, a);
                if (r != NOT_HANDLED) {
                    return r;
                }
            }
            case "Map", "ImmutableMap", "Maps" -> {
                if (n.equals("of")) {
                    MapV m = new MapV();
                    for (int i = 0; i + 1 < a.size(); i += 2) {
                        m.map.put(key(a.get(i)), a.get(i + 1));
                    }
                    return m;
                }
                if (n.startsWith("new")) {
                    return new MapV();
                }
            }
            case "Optional" -> {
                V.VObj o = new V.VObj("java.util.Optional", n, a);
                if (n.equals("of") || n.equals("ofNullable") && a.get(0) != V.NULL) {
                    o.fields.put("value", a.get(0));
                    return o;
                }
                if (n.equals("empty") || n.equals("ofNullable")) {
                    return o;
                }
            }
            case "Long2ObjectMaps", "Int2ObjectMaps", "Object2IntMaps", "Object2ObjectMaps" -> {
                if (n.equals("fastIterable") && a.size() == 1 && a.get(0) instanceof MapV m) {
                    return entries(m);
                }
            }
            default -> {
            }
        }
        return NOT_HANDLED;
    }

    private static Object passUnknown(Object v, String what) {
        return v instanceof Unknown u ? u : new Unknown(what + "(" + Interpreter.describe(v) + ")");
    }

    private Object collectionFactory(String simple, String n, List<Object> a) {
        if (n.equals("of") || n.equals("asList") || n.equals("newArrayList") || n.equals("newHashSet")
                || n.equals("newLinkedHashSet") || n.equals("copyOf") || n.equals("unmodifiableList")
                || n.equals("unmodifiableSet") || n.equals("singletonList") || n.equals("singleton")
                || n.equals("wrap") || n.equals("emptyList") || n.equals("emptySet") || n.equals("createList")) {
            List<Object> items = new ArrayList<>();
            if (a.size() == 1 && a.get(0) instanceof Seq s
                    && (n.equals("asList") || n.equals("copyOf") || n.startsWith("unmodifiable") || n.equals("wrap")
                    || n.startsWith("new") || n.equals("of") && s.kind.equals("array"))) {
                items.addAll(s.items);
            } else {
                items.addAll(a);
            }
            boolean set = simple.contains("Set") || n.contains("Set") || n.equals("singleton");
            return new Seq(set ? "set" : "list", items);
        }
        return NOT_HANDLED;
    }

    private static Object key(Object k) {
        return k;
    }

    static Seq entries(MapV m) {
        List<Object> items = new ArrayList<>();
        for (Object k : new ArrayList<>(m.map.keySet())) {
            items.add(new V.Entry(m, k));
        }
        return new Seq("list", items);
    }

    private Object mathCall(String n, List<Object> a) {
        if (V.firstUnknown(a) != null) {
            return V.firstUnknown(a);
        }
        Number x = a.isEmpty() ? null : Interpreter.num(a.get(0));
        Number y = a.size() < 2 ? null : Interpreter.num(a.get(1));
        if (x == null) {
            return NOT_HANDLED;
        }
        boolean fp = x instanceof Double || x instanceof Float || y instanceof Double || y instanceof Float;
        boolean lng = x instanceof Long || y instanceof Long;
        switch (n) {
            case "abs":
                return x instanceof Integer i ? (Object) Math.abs(i) : x instanceof Long l ? (Object) Math.abs(l)
                        : x instanceof Float f ? (Object) Math.abs(f) : Math.abs(x.doubleValue());
            case "max":
            case "min":
                if (y == null) {
                    return NOT_HANDLED;
                }
                if (fp) {
                    double r = n.equals("max") ? Math.max(x.doubleValue(), y.doubleValue())
                            : Math.min(x.doubleValue(), y.doubleValue());
                    return x instanceof Double || y instanceof Double ? (Object) r : (Object) (float) r;
                }
                long r = n.equals("max") ? Math.max(x.longValue(), y.longValue()) : Math.min(x.longValue(), y.longValue());
                return lng ? (Object) r : (Object) (int) r;
            case "floor":
                return Math.floor(x.doubleValue());
            case "ceil":
                return Math.ceil(x.doubleValue());
            case "round":
                return x instanceof Float f ? (Object) Math.round(f) : (Object) Math.round(x.doubleValue());
            case "sqrt":
                return Math.sqrt(x.doubleValue());
            case "pow":
                return y == null ? NOT_HANDLED : Math.pow(x.doubleValue(), y.doubleValue());
            default:
                return NOT_HANDLED;
        }
    }

    private Object stringCall(Interpreter in, String s, String n, List<Object> a) {
        if (V.firstUnknown(a) != null) {
            return V.firstUnknown(a);
        }
        switch (n) {
            case "toLowerCase":
                return s.toLowerCase(Locale.ROOT);
            case "toUpperCase":
                return s.toUpperCase(Locale.ROOT);
            case "trim":
                return s.trim();
            case "length":
                return s.length();
            case "isEmpty":
                return s.isEmpty();
            case "equals":
                return a.get(0) instanceof String o ? s.equals(o) : false;
            case "equalsIgnoreCase":
                return a.get(0) instanceof String o && s.equalsIgnoreCase(o);
            case "startsWith":
                return a.get(0) instanceof String o ? (Object) s.startsWith(o) : NOT_HANDLED;
            case "endsWith":
                return a.get(0) instanceof String o ? (Object) s.endsWith(o) : NOT_HANDLED;
            case "contains":
                return a.get(0) instanceof String o ? (Object) s.contains(o) : NOT_HANDLED;
            case "concat":
                return a.get(0) instanceof String o ? s + o : NOT_HANDLED;
            case "replace":
                if (a.size() == 2) {
                    String x = in.str(a.get(0));
                    String y = in.str(a.get(1));
                    return x == null || y == null ? NOT_HANDLED : s.replace(x, y);
                }
                return NOT_HANDLED;
            case "substring":
                if (a.size() == 1 && a.get(0) instanceof Integer b && b >= 0 && b <= s.length()) {
                    return s.substring(b);
                }
                if (a.size() == 2 && a.get(0) instanceof Integer b && a.get(1) instanceof Integer e && b >= 0
                        && e <= s.length() && b <= e) {
                    return s.substring(b, e);
                }
                return new Unknown("substring bounds");
            case "indexOf":
                return a.get(0) instanceof String o ? (Object) s.indexOf(o)
                        : a.get(0) instanceof Character ch ? (Object) s.indexOf(ch) : NOT_HANDLED;
            case "charAt":
                return a.get(0) instanceof Integer i && i >= 0 && i < s.length() ? (Object) s.charAt(i)
                        : new Unknown("charAt bounds");
            case "split":
                if (a.get(0) instanceof String re) {
                    List<Object> parts = new ArrayList<>(List.of(s.split(re)));
                    return new Seq("array", parts);
                }
                return NOT_HANDLED;
            case "toString", "intern":
                return s;
            case "hashCode":
                return s.hashCode();
            case "toCharArray":
                List<Object> chars = new ArrayList<>();
                for (char ch : s.toCharArray()) {
                    chars.add(ch);
                }
                return new Seq("array", chars);
            case "formatted":
                return format(in, s, a);
            default:
                return NOT_HANDLED;
        }
    }

    private Object format(Interpreter in, String fmt, List<Object> args) {
        List<Object> conv = new ArrayList<>();
        for (Object o : args) {
            if (o instanceof Unknown u) {
                return u;
            }
            if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof String) {
                conv.add(o);
            } else {
                String s = in.str(o);
                if (s == null) {
                    return new Unknown("String.format argument " + Interpreter.describe(o));
                }
                conv.add(s);
            }
        }
        try {
            return String.format(Locale.ROOT, fmt, conv.toArray());
        } catch (RuntimeException e) {
            return new Unknown("String.format: " + e.getMessage());
        }
    }

    private Object seqCall(Interpreter in, Seq s, String n, List<Object> a) {
        switch (n) {
            case "add":
                if (a.size() == 1) {
                    if (s.kind.equals("set") && containsSame(s.items, a.get(0))) {
                        return false;
                    }
                    s.items.add(a.get(0));
                    return true;
                }
                if (a.size() == 2 && a.get(0) instanceof Integer i && i >= 0 && i <= s.items.size()) {
                    s.items.add(i, a.get(1));
                    return V.NULL;
                }
                return NOT_HANDLED;
            case "addAll":
                if (a.size() == 1 && a.get(0) instanceof Seq o) {
                    for (Object x : o.items) {
                        if (!s.kind.equals("set") || !containsSame(s.items, x)) {
                            s.items.add(x);
                        }
                    }
                    return true;
                }
                return NOT_HANDLED;
            case "get":
                if (a.size() == 1 && a.get(0) instanceof Integer i) {
                    return i >= 0 && i < s.items.size() ? s.items.get(i) : new Unknown("index out of bounds");
                }
                return passUnknown(a.isEmpty() ? null : a.get(0), "list.get");
            case "set":
                if (a.size() == 2 && a.get(0) instanceof Integer i && i >= 0 && i < s.items.size()) {
                    return s.items.set(i, a.get(1));
                }
                return NOT_HANDLED;
            case "size":
                return s.items.size();
            case "isEmpty":
                return s.items.isEmpty();
            case "contains":
                return a.get(0) instanceof Unknown u ? u : containsSame(s.items, a.get(0));
            case "indexOf":
                for (int i = 0; i < s.items.size(); i++) {
                    if (V.same(s.items.get(i), a.get(0))) {
                        return i;
                    }
                }
                return -1;
            case "iterator":
                return new V.Iter(new ArrayList<>(s.items));
            case "clear":
                s.items.clear();
                return V.NULL;
            case "remove":
                if (a.size() == 1 && a.get(0) instanceof Integer i && s.kind.equals("list")) {
                    return i >= 0 && i < s.items.size() ? s.items.remove((int) i) : new Unknown("index out of bounds");
                }
                for (int i = 0; i < s.items.size(); i++) {
                    if (V.same(s.items.get(i), a.get(0))) {
                        s.items.remove(i);
                        return true;
                    }
                }
                return false;
            case "forEach":
                if (a.size() == 1 && a.get(0) instanceof Fn fn) {
                    for (Object x : new ArrayList<>(s.items)) {
                        in.callFn(fn, List.of(x));
                    }
                    return V.NULL;
                }
                return NOT_HANDLED;
            case "toArray":
                return new Seq("array", new ArrayList<>(s.items));
            case "clone":
                return new Seq(s.kind, new ArrayList<>(s.items));
            case "getFirst":
                return s.items.isEmpty() ? new Unknown("empty list") : s.items.get(0);
            default:
                return NOT_HANDLED;
        }
    }

    private static boolean containsSame(List<Object> items, Object x) {
        for (Object o : items) {
            if (V.same(o, x) || o == x) {
                return true;
            }
        }
        return false;
    }

    private Object findKey(MapV m, Object k) {
        if (m.map.containsKey(k)) {
            return k;
        }
        for (Object existing : m.map.keySet()) {
            if (V.same(existing, k)) {
                return existing;
            }
        }
        return null;
    }

    private Object mapCall(Interpreter in, MapV m, String n, List<Object> a) {
        if (!a.isEmpty() && a.get(0) instanceof Unknown u && !n.equals("put")) {
            return u;
        }
        switch (n) {
            case "put": {
                if (a.get(0) instanceof Unknown u) {
                    // an unknown key may overwrite anything: the whole map becomes unreliable
                    m.map.replaceAll((k, v) -> u);
                    return u;
                }
                Object k = findKey(m, a.get(0));
                Object old = m.map.put(k == null ? a.get(0) : k, a.get(1));
                return old == null ? m.defaultValue : old;
            }
            case "putIfAbsent": {
                Object k = findKey(m, a.get(0));
                if (k != null) {
                    return m.map.get(k);
                }
                m.map.put(a.get(0), a.get(1));
                return m.defaultValue;
            }
            case "get": {
                Object k = findKey(m, a.get(0));
                return k == null ? m.defaultValue : m.map.get(k);
            }
            case "getOrDefault": {
                Object k = findKey(m, a.get(0));
                return k == null ? a.get(1) : m.map.get(k);
            }
            case "containsKey":
                return findKey(m, a.get(0)) != null;
            case "computeIfAbsent": {
                Object k = findKey(m, a.get(0));
                if (k != null) {
                    return m.map.get(k);
                }
                if (a.get(1) instanceof Fn fn) {
                    Object v = in.callFn(fn, List.of(a.get(0)));
                    m.map.put(a.get(0), v);
                    return v;
                }
                return NOT_HANDLED;
            }
            case "remove": {
                Object k = findKey(m, a.get(0));
                return k == null ? m.defaultValue : m.map.remove(k);
            }
            case "size":
                return m.map.size();
            case "isEmpty":
                return m.map.isEmpty();
            case "keySet":
                return new Seq("set", new ArrayList<>(m.map.keySet()));
            case "values":
                return new Seq("list", new ArrayList<>(m.map.values()));
            case "entrySet", "long2ObjectEntrySet", "object2IntEntrySet", "int2ObjectEntrySet", "object2ObjectEntrySet":
                return entries(m);
            case "defaultReturnValue":
                if (a.size() == 1) {
                    m.defaultValue = a.get(0);
                    return V.NULL;
                }
                return m.defaultValue;
            case "forEach":
                if (a.size() == 1 && a.get(0) instanceof Fn fn) {
                    for (Map.Entry<Object, Object> e : new ArrayList<>(m.map.entrySet())) {
                        in.callFn(fn, List.of(e.getKey(), e.getValue()));
                    }
                    return V.NULL;
                }
                return NOT_HANDLED;
            case "putAll":
                if (a.get(0) instanceof MapV o) {
                    m.map.putAll(o.map);
                    return V.NULL;
                }
                return NOT_HANDLED;
            default:
                return NOT_HANDLED;
        }
    }
}
