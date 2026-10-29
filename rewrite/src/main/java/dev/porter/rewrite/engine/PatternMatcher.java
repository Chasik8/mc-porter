package dev.porter.rewrite.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

/**
 * Structural matching of a {@link CodePattern} against target code (RFC 006). Type names compare by simple name
 * (generics erased), member names through the host's alias check (SRG), casts only when the pattern writes them,
 * parentheses never (they are not in the AST).
 */
public final class PatternMatcher {

    /** What the matcher needs from the rewriter. */
    public interface Host {
        /** pattern member name (mojmap) vs written target name (maybe SRG); kind = method | field. */
        boolean sameMember(String patternName, String targetName, String kind);

        /** whitespace-normalised source text of a target element. */
        String text(CtElement e);

        /** the element has a usable source range in the file being rewritten. */
        boolean positioned(CtElement e);

        /** source text of a type reference as written in the target (null when it has no position). */
        default String typeText(CtTypeReference<?> r) {
            return null;
        }
    }

    /** Bindings of one match: placeholder -> target element (expression, statement or the local's declaration). */
    public static final class Match {
        final Map<String, CtElement> bound = new LinkedHashMap<>();
        final Map<String, String> localNames = new LinkedHashMap<>();
        /** type metavariable ($T in a cast or a declared type) -> the type as written in the target. */
        final Map<String, String> types = new LinkedHashMap<>();
        final Map<String, String> typeSimple = new LinkedHashMap<>();
        /** holes whose pattern wrote casts: the binding excludes the target's casts (the pattern consumed them). */
        final java.util.Set<String> castFree = new java.util.LinkedHashSet<>();

        public Map<String, String> types() {
            return types;
        }

        public java.util.Set<String> castFree() {
            return castFree;
        }

        public Map<String, CtElement> bound() {
            return bound;
        }

        public Map<String, String> localNames() {
            return localNames;
        }
    }

    private static final java.util.regex.Pattern TYPE_TEXT = java.util.regex.Pattern.compile("[A-Za-z0-9_.$<>,? ]+");

    private final CodePattern pattern;
    private final Host host;

    public PatternMatcher(CodePattern pattern, Host host) {
        this.pattern = pattern;
        this.host = host;
    }

    /** Expression pattern against one expression. */
    public Match matchExpression(CtExpression<?> target) {
        Match m = new Match();
        return eq(pattern.expression(), target, m) ? m : null;
    }

    /** Statement pattern against target statements [from, from + n). */
    public Match matchStatements(List<CtStatement> target, int from) {
        List<CtStatement> ps = pattern.stmts();
        if (from + ps.size() > target.size()) {
            return null;
        }
        Match m = new Match();
        for (int i = 0; i < ps.size(); i++) {
            if (!eq(ps.get(i), target.get(from + i), m)) {
                return null;
            }
        }
        return m;
    }

    private static String simpleType(CtTypeReference<?> r) {
        if (r == null) {
            return null;
        }
        String s = r.getSimpleName();
        int lt = s.indexOf('<');
        s = lt < 0 ? s : s.substring(0, lt);
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf('$'));
        return i < 0 ? s : s.substring(i + 1);
    }

    private boolean castsOk(CtExpression<?> p, CtExpression<?> t, Match m) {
        List<CtTypeReference<?>> pc = p.getTypeCasts();
        if (pc.isEmpty()) {
            return true;
        }
        List<CtTypeReference<?>> tc = t.getTypeCasts();
        if (pc.size() != tc.size()) {
            return false;
        }
        for (int i = 0; i < pc.size(); i++) {
            if (!typeEq(pc.get(i), tc.get(i), m)) {
                return false;
            }
        }
        return true;
    }

    /** Type comparison by simple name; a pattern type {@code $T} (a placeholder) binds to any type, consistently. */
    private boolean typeEq(CtTypeReference<?> p, CtTypeReference<?> t, Match m) {
        String pn = p == null ? null : p.getSimpleName();
        if (pn != null && pn.startsWith("$") && pattern.expressionHole(pn)) {
            if (t == null) {
                return false;
            }
            String text = host.typeText(t);
            if (text == null || !TYPE_TEXT.matcher(text).matches()) {
                text = simpleType(t);
            }
            String simple = simpleType(t);
            String prev = m.typeSimple.putIfAbsent(pn, simple);
            if (prev == null) {
                m.types.put(pn, text);
                return true;
            }
            return Objects.equals(prev, simple);
        }
        return Objects.equals(simpleType(p), simpleType(t));
    }

    private static String lastName(String s) {
        int lt = s.indexOf('<');
        s = lt < 0 ? s : s.substring(0, lt);
        return s.substring(Math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1).trim();
    }

    private String holeName(CtElement p) {
        if (p instanceof CtVariableAccess<?> va && !(p instanceof CtFieldAccess<?>)) {
            String n = va.getVariable().getSimpleName();
            if (pattern.expressionHole(n)) {
                return n;
            }
        }
        return null;
    }

    private boolean bind(String name, CtElement t, Match m) {
        if (!host.positioned(t)) {
            return false;
        }
        CtElement prev = m.bound.get(name);
        if (prev == null) {
            m.bound.put(name, t);
            return true;
        }
        return host.text(prev).equals(host.text(t));
    }

    private boolean statementHole(CtElement p) {
        return p instanceof CtInvocation<?> inv && inv.getArguments().isEmpty()
                && (inv.getTarget() == null || inv.getTarget().isImplicit())
                && pattern.statementHole(inv.getExecutable().getSimpleName());
    }

    boolean eq(CtElement p, CtElement t, Match m) {
        if (p == null || t == null) {
            return p == t;
        }
        if (p instanceof CtStatement && statementHole(p) && p.getParent() instanceof CtBlock<?>) {
            return t instanceof CtStatement && bind(((CtInvocation<?>) p).getExecutable().getSimpleName(), t, m);
        }
        String hole = holeName(p);
        if (hole != null) {
            CtExpression<?> pe = (CtExpression<?>) p;
            if (!(t instanceof CtExpression<?> te) || !castsOk(pe, te, m) || !bind(hole, t, m)) {
                return false;
            }
            if (!pe.getTypeCasts().isEmpty()) {
                m.castFree.add(hole);
            }
            return true;
        }
        if (p instanceof CtExpression<?> pe) {
            if (!(t instanceof CtExpression<?> te) || !castsOk(pe, te, m)) {
                return false;
            }
        }
        // locals of the pattern: reads/writes must use the bound name
        if (p instanceof CtVariableAccess<?> pv && !(p instanceof CtFieldAccess<?>)) {
            String n = pv.getVariable().getSimpleName();
            if (pattern.localHole(n)) {
                String bound = m.localNames.get(n);
                return bound != null && t instanceof CtVariableAccess<?> tv && !(t instanceof CtFieldAccess<?>)
                        && bound.equals(tv.getVariable().getSimpleName());
            }
        }
        if (nameLike(p) && nameLike(t)) {
            return nameOf(p).equals(nameOf(t));
        }
        if (p.getClass() != t.getClass()) {
            return false;
        }
        if (p instanceof CtLiteral<?> pl) {
            return Objects.equals(String.valueOf(pl.getValue()), String.valueOf(((CtLiteral<?>) t).getValue()));
        }
        if (p instanceof CtThisAccess<?> || p instanceof CtSuperAccess<?>) {
            return true;
        }
        if (p instanceof CtTypeAccess<?> pt) {
            return Objects.equals(simpleType(pt.getAccessedType()), simpleType(((CtTypeAccess<?>) t).getAccessedType()));
        }
        if (p instanceof CtFieldAccess<?> pf) {
            CtFieldAccess<?> tf = (CtFieldAccess<?>) t;
            return host.sameMember(pf.getVariable().getSimpleName(), tf.getVariable().getSimpleName(), "field")
                    && target(pf.getTarget(), tf.getTarget(), m);
        }
        if (p instanceof CtVariableAccess<?> pv) {
            return pv.getVariable().getSimpleName().equals(((CtVariableAccess<?>) t).getVariable().getSimpleName());
        }
        if (p instanceof CtInvocation<?> pi) {
            CtInvocation<?> ti = (CtInvocation<?>) t;
            return host.sameMember(pi.getExecutable().getSimpleName(), ti.getExecutable().getSimpleName(), "method")
                    && target(pi.getTarget(), ti.getTarget(), m) && list(pi.getArguments(), ti.getArguments(), m);
        }
        if (p instanceof CtNewClass<?>) {
            return false;
        }
        if (p instanceof CtConstructorCall<?> pc) {
            CtConstructorCall<?> tc = (CtConstructorCall<?>) t;
            return Objects.equals(simpleType(pc.getType()), simpleType(tc.getType())) && list(pc.getArguments(), tc.getArguments(), m);
        }
        if (p instanceof CtBinaryOperator<?> pb) {
            CtBinaryOperator<?> tb = (CtBinaryOperator<?>) t;
            return pb.getKind() == tb.getKind() && eq(pb.getLeftHandOperand(), tb.getLeftHandOperand(), m)
                    && eq(pb.getRightHandOperand(), tb.getRightHandOperand(), m);
        }
        if (p instanceof CtUnaryOperator<?> pu) {
            CtUnaryOperator<?> tu = (CtUnaryOperator<?>) t;
            return pu.getKind() == tu.getKind() && eq(pu.getOperand(), tu.getOperand(), m);
        }
        if (p instanceof CtOperatorAssignment<?, ?> po && po.getKind() != ((CtOperatorAssignment<?, ?>) t).getKind()) {
            return false;
        }
        if (p instanceof CtAssignment<?, ?> pa) {
            CtAssignment<?, ?> ta = (CtAssignment<?, ?>) t;
            return eq(pa.getAssigned(), ta.getAssigned(), m) && eq(pa.getAssignment(), ta.getAssignment(), m);
        }
        if (p instanceof CtConditional<?> pc) {
            CtConditional<?> tc = (CtConditional<?>) t;
            return eq(pc.getCondition(), tc.getCondition(), m) && eq(pc.getThenExpression(), tc.getThenExpression(), m)
                    && eq(pc.getElseExpression(), tc.getElseExpression(), m);
        }
        if (p instanceof CtArrayAccess<?, ?> pa) {
            CtArrayAccess<?, ?> ta = (CtArrayAccess<?, ?>) t;
            return eq(pa.getTarget(), ta.getTarget(), m) && eq(pa.getIndexExpression(), ta.getIndexExpression(), m);
        }
        if (p instanceof CtLocalVariable<?> pl) {
            CtLocalVariable<?> tl = (CtLocalVariable<?>) t;
            if (!typeEq(pl.getType(), tl.getType(), m)) {
                return false;
            }
            if (pattern.localHole(pl.getSimpleName())) {
                if (m.localNames.containsKey(pl.getSimpleName()) || !host.positioned(tl)) {
                    return false;
                }
                m.localNames.put(pl.getSimpleName(), tl.getSimpleName());
                m.bound.put(pl.getSimpleName(), tl);
            } else if (!pl.getSimpleName().equals(tl.getSimpleName())) {
                return false;
            }
            return eq(pl.getDefaultExpression(), tl.getDefaultExpression(), m);
        }
        if (p instanceof CtIf pi) {
            CtIf ti = (CtIf) t;
            return eq(pi.getCondition(), ti.getCondition(), m) && branch(pi.getThenStatement(), ti.getThenStatement(), m)
                    && branch(pi.getElseStatement(), ti.getElseStatement(), m);
        }
        if (p instanceof CtBlock<?> pb) {
            return list(pb.getStatements(), ((CtBlock<?>) t).getStatements(), m);
        }
        if (p instanceof CtReturn<?> pr) {
            return eq(pr.getReturnedExpression(), ((CtReturn<?>) t).getReturnedExpression(), m);
        }
        if (p instanceof CtThrow pt) {
            return eq(pt.getThrownExpression(), ((CtThrow) t).getThrownExpression(), m);
        }
        return false; // not supported in patterns (lambdas, loops, switch, ...)
    }

    /** if/else branches: a single "$s;" in an (implicit) block matches the whole target branch. */
    private boolean branch(CtStatement p, CtStatement t, Match m) {
        if (p instanceof CtBlock<?> pb && pb.getStatements().size() == 1 && statementHole(pb.getStatements().get(0))) {
            return t != null && bind(((CtInvocation<?>) pb.getStatements().get(0)).getExecutable().getSimpleName(), t, m);
        }
        if (p instanceof CtBlock<?> pb && t != null && !(t instanceof CtBlock<?>)) {
            return pb.getStatements().size() == 1 && eq(pb.getStatements().get(0), t, m);
        }
        return eq(p, t, m);
    }

    /** Receiver: an absent/implicit pattern receiver matches an absent or implicit this/type receiver. */
    private boolean target(CtExpression<?> p, CtExpression<?> t, Match m) {
        boolean pNone = p == null || p.isImplicit();
        boolean tNone = t == null || t.isImplicit();
        if (pNone || tNone) {
            if (pNone && tNone) {
                return true;
            }
            // "this.x" in the pattern also matches an implicit "this" receiver
            return (p instanceof CtThisAccess<?> && (t == null || t instanceof CtThisAccess<?>))
                    || (t instanceof CtThisAccess<?> && (p == null || p instanceof CtThisAccess<?>));
        }
        return eq(p, t, m);
    }

    private boolean list(List<? extends CtElement> p, List<? extends CtElement> t, Match m) {
        if (p.size() != t.size()) {
            return false;
        }
        for (int i = 0; i < p.size(); i++) {
            if (!eq(p.get(i), t.get(i), m)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Without a classpath Spoon may read a bare name as a type access, a field read or a variable read: a lone name
     * (no receiver) compares by the name itself.
     */
    private static boolean nameLike(CtElement e) {
        if (e instanceof CtTypeAccess<?> ta) {
            return ta.getAccessedType() != null && !ta.isImplicit();
        }
        if (e instanceof CtFieldAccess<?> fa) {
            return fa.getTarget() == null || fa.getTarget().isImplicit();
        }
        return false;
    }

    private static String nameOf(CtElement e) {
        if (e instanceof CtTypeAccess<?> ta) {
            return simpleType(ta.getAccessedType());
        }
        return ((CtFieldAccess<?>) e).getVariable().getSimpleName();
    }
}
