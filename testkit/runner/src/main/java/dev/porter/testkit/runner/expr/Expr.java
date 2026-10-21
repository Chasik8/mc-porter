package dev.porter.testkit.runner.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Assert expressions of testkit scenarios (vocabulary owned by testkit, see testkit/README.md).
 *
 * <pre>
 * expr    := or
 * or      := and ('||' and)*
 * and     := not ('&amp;&amp;' not)*
 * not     := '!' not | cmp
 * cmp     := sum (('=='|'!='|'&lt;'|'&lt;='|'&gt;'|'&gt;=') sum)?
 * sum     := prod (('+'|'-') prod)*
 * prod    := unary (('*'|'/'|'%') unary)*
 * unary   := '-' unary | postfix
 * postfix := primary ('.' name | '[' int ']')*
 * primary := number | 'string' | "string" | true | false | null | tick | '(' expr ')' | call | ref
 * call    := name '(' (arg (',' arg)*)? ')'
 * ref     := sample key: probe ':' target ['/' path]   (e.g. nbt:[0,64,0]/CookTime, item:[0,64,0]#2)
 *          | probe name without target (energy_out) = the only sampled key with that probe
 *          | `any key in backticks`
 * </pre>
 *
 * A key ends at whitespace or at one of {@code ( ) = ! < > & | + * %} outside brackets; a {@code .} outside brackets
 * starts an accessor ({@code item:[0,64,0]#2.count}). Put spaces around binary {@code -}.
 */
public final class Expr {
    private Expr() {
    }

    /** Parsed expression node. */
    public sealed interface Node permits Lit, Ref, Unary, Binary, Call, TickVar {
    }

    public record Lit(Object value) implements Node {
    }

    /** Sample key reference with accessors (String = field, Integer = index). */
    public record Ref(String key, List<Object> path) implements Node {
    }

    public record Unary(String op, Node operand) implements Node {
    }

    public record Binary(String op, Node left, Node right) implements Node {
    }

    public record Call(String name, List<Node> args) implements Node {
    }

    public record TickVar() implements Node {
    }

    /** Thrown on syntax errors and on evaluation errors (unknown key, type mismatch, ...). */
    public static final class ExprException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ExprException(String message) {
            super(message);
        }
    }

    /** Evaluation context: sampled values of a trace (keys are canonical scenario keys). */
    public interface Context {
        /** Latest value sampled for the key at or before {@link #now()}, or {@code null} if never sampled. */
        JsonNode latest(String key);

        /** All samples of a key in tick order. */
        List<TimedValue> series(String key);

        /** Resolves a bare probe name or exact key to a sampled key (or returns the input). */
        String resolve(String keyOrProbe);

        long now();
    }

    public record TimedValue(long tick, JsonNode value) {
    }

    // ------------------------------------------------------------------ parsing

    public static Node parse(String text) {
        Parser p = new Parser(text);
        Node n = p.or();
        p.ws();
        if (p.pos < text.length()) {
            throw new ExprException("unexpected '" + text.substring(p.pos) + "' at " + p.pos);
        }
        return n;
    }

    /** All sample keys referenced directly (not inside functions that take series) — they are sampled implicitly. */
    public static List<String> directKeys(Node n) {
        List<String> out = new ArrayList<>();
        collect(n, out, true);
        return out;
    }

    /** All keys referenced anywhere. */
    public static List<String> allKeys(Node n) {
        List<String> out = new ArrayList<>();
        collect(n, out, false);
        return out;
    }

    private static void collect(Node n, List<String> out, boolean directOnly) {
        switch (n) {
            case Ref r -> {
                if (!out.contains(r.key())) {
                    out.add(r.key());
                }
            }
            case Unary u -> collect(u.operand(), out, directOnly);
            case Binary b -> {
                collect(b.left(), out, directOnly);
                collect(b.right(), out, directOnly);
            }
            case Call c -> {
                boolean series = SERIES_FUNCS.contains(c.name());
                for (int i = 0; i < c.args().size(); i++) {
                    if (series && i == 0 && directOnly) {
                        continue;
                    }
                    collect(c.args().get(i), out, directOnly);
                }
            }
            default -> {
            }
        }
    }

    private static final List<String> SERIES_FUNCS = List.of("avg", "min", "max", "sum", "at", "delta", "count", "first", "last");

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean eat(String tok) {
            ws();
            if (s.startsWith(tok, pos)) {
                pos += tok.length();
                return true;
            }
            return false;
        }

        Node or() {
            Node l = and();
            while (eat("||")) {
                l = new Binary("||", l, and());
            }
            return l;
        }

        Node and() {
            Node l = not();
            while (eat("&&")) {
                l = new Binary("&&", l, not());
            }
            return l;
        }

        Node not() {
            ws();
            if (pos < s.length() && s.charAt(pos) == '!' && !s.startsWith("!=", pos)) {
                pos++;
                return new Unary("!", not());
            }
            return cmp();
        }

        Node cmp() {
            Node l = sum();
            for (String op : List.of("==", "!=", "<=", ">=", "<", ">")) {
                if (eat(op)) {
                    return new Binary(op, l, sum());
                }
            }
            return l;
        }

        Node sum() {
            Node l = prod();
            while (true) {
                if (eat("+")) {
                    l = new Binary("+", l, prod());
                } else if (peekBinaryMinus() && eat("-")) {
                    l = new Binary("-", l, prod());
                } else {
                    return l;
                }
            }
        }

        boolean peekBinaryMinus() {
            ws();
            return pos < s.length() && s.charAt(pos) == '-';
        }

        Node prod() {
            Node l = unary();
            while (true) {
                if (eat("*")) {
                    l = new Binary("*", l, unary());
                } else if (eat("/")) {
                    l = new Binary("/", l, unary());
                } else if (eat("%")) {
                    l = new Binary("%", l, unary());
                } else {
                    return l;
                }
            }
        }

        Node unary() {
            ws();
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                ws();
                if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    Node n = primary();
                    if (n instanceof Lit lit && lit.value() instanceof Double d) {
                        return postfix(new Lit(-d));
                    }
                    return new Unary("-", postfix(n));
                }
                return new Unary("-", unary());
            }
            return postfix(primary());
        }

        Node postfix(Node n) {
            if (n instanceof Ref r) {
                // accessors already consumed by the key reader
                return r;
            }
            return n;
        }

        Node primary() {
            ws();
            if (pos >= s.length()) {
                throw new ExprException("unexpected end of expression");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = or();
                if (!eat(")")) {
                    throw new ExprException("missing ')' at " + pos);
                }
                return n;
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'
                        || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                        || ((s.charAt(pos) == '-' || s.charAt(pos) == '+') && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                    pos++;
                }
                try {
                    return new Lit(Double.parseDouble(s.substring(start, pos)));
                } catch (NumberFormatException e) {
                    throw new ExprException("bad number '" + s.substring(start, pos) + "'");
                }
            }
            if (c == '\'' || c == '"') {
                int end = s.indexOf(c, pos + 1);
                if (end < 0) {
                    throw new ExprException("unterminated string at " + pos);
                }
                String v = s.substring(pos + 1, end);
                pos = end + 1;
                return new Lit(v);
            }
            if (c == '`') {
                int end = s.indexOf('`', pos + 1);
                if (end < 0) {
                    throw new ExprException("unterminated `key` at " + pos);
                }
                String key = s.substring(pos + 1, end);
                pos = end + 1;
                return new Ref(key, accessors());
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                    pos++;
                }
                String name = s.substring(start, pos);
                if (pos < s.length() && s.charAt(pos) == ':') {
                    pos = start;
                    return new Ref(readKey(), accessors());
                }
                if (pos < s.length() && s.charAt(pos) == '(') {
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(or());
                        } while (eat(","));
                        if (!eat(")")) {
                            throw new ExprException("missing ')' after arguments of " + name);
                        }
                    }
                    return new Call(name.toLowerCase(Locale.ROOT), args);
                }
                switch (name) {
                    case "true":
                        return new Lit(Boolean.TRUE);
                    case "false":
                        return new Lit(Boolean.FALSE);
                    case "null":
                        return new Lit(null);
                    case "tick":
                        return new TickVar();
                    default:
                        return new Ref(name, accessors());
                }
            }
            throw new ExprException("unexpected '" + c + "' at " + pos);
        }

        String readKey() {
            int start = pos;
            int depth = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                } else if (depth == 0 && (Character.isWhitespace(c) || "()=!<>&|+*%.,".indexOf(c) >= 0)) {
                    break;
                }
                pos++;
            }
            return s.substring(start, pos);
        }

        List<Object> accessors() {
            List<Object> path = new ArrayList<>();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.') {
                    pos++;
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                        pos++;
                    }
                    if (start == pos) {
                        throw new ExprException("empty accessor at " + pos);
                    }
                    path.add(s.substring(start, pos));
                } else if (c == '[') {
                    int end = s.indexOf(']', pos);
                    if (end < 0) {
                        throw new ExprException("missing ']' at " + pos);
                    }
                    try {
                        path.add(Integer.parseInt(s.substring(pos + 1, end).trim()));
                    } catch (NumberFormatException e) {
                        throw new ExprException("index must be an integer at " + pos);
                    }
                    pos = end + 1;
                } else {
                    break;
                }
            }
            return path;
        }
    }

    // ------------------------------------------------------------------ evaluation

    /** Evaluates to a Java value: Double, String, Boolean, JsonNode (objects/arrays) or null. */
    public static Object eval(Node n, Context ctx) {
        return switch (n) {
            case Lit l -> l.value();
            case TickVar t -> (double) ctx.now();
            case Ref r -> unwrap(access(ctx.latest(ctx.resolve(r.key())), r.path()));
            case Unary u -> unary(u, ctx);
            case Binary b -> binary(b, ctx);
            case Call c -> call(c, ctx);
        };
    }

    /** Evaluates a boolean assertion. */
    public static boolean test(Node n, Context ctx) {
        Object v = eval(n, ctx);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExprException("expression is not boolean (value " + render(v) + ")");
    }

    static JsonNode access(JsonNode v, List<Object> path) {
        JsonNode cur = v;
        for (Object p : path) {
            if (cur == null || cur.isNull() || cur.isMissingNode()) {
                return null;
            }
            cur = p instanceof Integer i ? cur.get(i) : cur.get((String) p);
        }
        return cur;
    }

    /** JSON value to evaluator value. */
    public static Object unwrap(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            return v.asString();
        }
        return v;
    }

    private static Object unary(Unary u, Context ctx) {
        Object v = eval(u.operand(), ctx);
        if (u.op().equals("!")) {
            if (v instanceof Boolean b) {
                return !b;
            }
            throw new ExprException("'!' needs a boolean, got " + render(v));
        }
        return -num(v, "-");
    }

    private static Object binary(Binary b, Context ctx) {
        String op = b.op();
        if (op.equals("&&") || op.equals("||")) {
            Object l = eval(b.left(), ctx);
            if (!(l instanceof Boolean lb)) {
                throw new ExprException("'" + op + "' needs booleans, got " + render(l));
            }
            if (op.equals("&&") && !lb) {
                return false;
            }
            if (op.equals("||") && lb) {
                return true;
            }
            Object r = eval(b.right(), ctx);
            if (!(r instanceof Boolean rb)) {
                throw new ExprException("'" + op + "' needs booleans, got " + render(r));
            }
            return rb;
        }
        Object l = eval(b.left(), ctx);
        Object r = eval(b.right(), ctx);
        switch (op) {
            case "==":
                return equalValues(l, r);
            case "!=":
                return !equalValues(l, r);
            case "<", "<=", ">", ">=": {
                if (l == null || r == null) {
                    return false;
                }
                if (l instanceof String ls && r instanceof String rs) {
                    int c = ls.compareTo(rs);
                    return cmp(op, c);
                }
                return cmp(op, Double.compare(num(l, op), num(r, op)));
            }
            case "+":
                if (l instanceof String || r instanceof String) {
                    return render(l) + render(r);
                }
                return num(l, op) + num(r, op);
            case "-":
                return num(l, op) - num(r, op);
            case "*":
                return num(l, op) * num(r, op);
            case "/":
                return num(l, op) / num(r, op);
            case "%":
                return num(l, op) % num(r, op);
            default:
                throw new ExprException("unknown operator " + op);
        }
    }

    private static boolean cmp(String op, int c) {
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        };
    }

    static boolean equalValues(Object l, Object r) {
        if (l instanceof Double a && r instanceof Double b) {
            return a.doubleValue() == b.doubleValue();
        }
        return Objects.equals(l, r);
    }

    private static double num(Object v, String op) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        throw new ExprException("'" + op + "' needs numbers, got " + render(v));
    }

    private static Object call(Call c, Context ctx) {
        String f = c.name();
        List<Node> a = c.args();
        if (SERIES_FUNCS.contains(f)) {
            if (a.isEmpty() || !(a.get(0) instanceof Ref ref)) {
                throw new ExprException(f + "() needs a sample key as first argument");
            }
            List<TimedValue> series = ctx.series(ctx.resolve(ref.key()));
            long from = a.size() > 1 ? (long) num(eval(a.get(1), ctx), f) : Long.MIN_VALUE;
            long to = a.size() > 2 ? (long) num(eval(a.get(2), ctx), f) : ctx.now();
            List<Double> vals = new ArrayList<>();
            List<TimedValue> window = new ArrayList<>();
            for (TimedValue tv : series) {
                if (tv.tick() >= from && tv.tick() <= to) {
                    window.add(tv);
                    Object v = unwrap(access(tv.value(), ref.path()));
                    if (v instanceof Double d) {
                        vals.add(d);
                    }
                }
            }
            switch (f) {
                case "count":
                    return (double) window.size();
                case "first":
                    return window.isEmpty() ? null : unwrap(access(window.get(0).value(), ref.path()));
                case "last":
                    return window.isEmpty() ? null : unwrap(access(window.get(window.size() - 1).value(), ref.path()));
                case "at": {
                    if (a.size() != 2) {
                        throw new ExprException("at(key, tick) takes 2 arguments");
                    }
                    for (TimedValue tv : series) {
                        if (tv.tick() == from) {
                            return unwrap(access(tv.value(), ref.path()));
                        }
                    }
                    throw new ExprException("no sample of " + ref.key() + " at tick " + from);
                }
                case "delta": {
                    if (vals.size() < 1) {
                        throw new ExprException("delta(): no numeric samples of " + ref.key() + " in [" + from + "," + to + "]");
                    }
                    return vals.get(vals.size() - 1) - vals.get(0);
                }
                default:
                    break;
            }
            if (vals.isEmpty()) {
                throw new ExprException(f + "(): no numeric samples of " + ref.key() + " in [" + from + "," + to + "]");
            }
            return switch (f) {
                case "avg" -> vals.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                case "min" -> vals.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                case "max" -> vals.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                default -> vals.stream().mapToDouble(Double::doubleValue).sum();
            };
        }
        switch (f) {
            case "abs":
                return Math.abs(num(one(c, ctx), f));
            case "round":
                return (double) Math.round(num(one(c, ctx), f));
            case "floor":
                return Math.floor(num(one(c, ctx), f));
            case "len": {
                Object v = one(c, ctx);
                if (v == null) {
                    return 0.0;
                }
                if (v instanceof String str) {
                    return (double) str.length();
                }
                if (v instanceof JsonNode node) {
                    return (double) node.size();
                }
                throw new ExprException("len() of " + render(v));
            }
            case "exists":
                return one(c, ctx) != null;
            case "contains": {
                if (a.size() != 2) {
                    throw new ExprException("contains(haystack, needle) takes 2 arguments");
                }
                Object h = eval(a.get(0), ctx);
                Object n = eval(a.get(1), ctx);
                return h != null && render(h).contains(render(n));
            }
            default:
                throw new ExprException("unknown function " + f + "()");
        }
    }

    private static Object one(Call c, Context ctx) {
        if (c.args().size() != 1) {
            throw new ExprException(c.name() + "() takes 1 argument");
        }
        return eval(c.args().get(0), ctx);
    }

    /** Human-readable rendering of an evaluator value. */
    public static String render(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : d.toString();
        }
        return v.toString();
    }
}
