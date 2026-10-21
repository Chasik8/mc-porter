package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.Fn;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.Seq;
import dev.porter.extract.forge1192.interp.V.Unknown;
import dev.porter.extract.forge1192.model.SourceModel;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtArrayRead;
import spoon.reflect.code.CtArrayWrite;
import spoon.reflect.code.CtAssert;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCodeSnippetExpression;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtContinue;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTry;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.code.CtWhile;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * A small, deterministic interpreter over the Spoon model of the mod. It executes the mod's own code (registration
 * methods, constructors, container constructors, structure builders) with Java semantics as far as values are
 * computable; non-source API is delegated to {@link Builtins}. Whatever cannot be computed becomes
 * {@link Unknown} with a reason. Undecidable control flow (an unknown condition) never picks a branch: the
 * statement is skipped and everything it could have assigned becomes unknown (or, for registration code under
 * {@link #bothBranches}, both branches run and the facts are flagged as conditional).
 */
public final class Interpreter {
    public static final int MAX_DEPTH = 96;
    public static final int MAX_LOOP = 20_000;

    private final SourceModel src;
    private final VanillaIndex vi;
    private final List<Builtins> builtins = new ArrayList<>();
    private final Map<String, Object> statics = new HashMap<>();
    private final Set<String> classInitDone = new HashSet<>();
    private final Set<String> staticInProgress = new HashSet<>();
    private final Map<CtType<?>, Map<String, List<CtMethod<?>>>> methodCache = new IdentityHashMap<>();
    private final Set<CtElement> reached = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<CtElement, String> skipped = new IdentityHashMap<>();
    private final Deque<String> conditions = new ArrayDeque<>();
    private Consumer<String> missingStaticHook = k -> { };

    private long steps;
    private long budget = 50_000_000L;
    private int depth;
    /** Constructor resolved by Spoon for the next construct() call (overloads Spoon could decide). */
    private CtConstructor<?> nextCtor;
    private final Deque<CtElement> callStack = new ArrayDeque<>();
    /** Registration root currently running ("a.b.C#method"), set by the Registrar. */
    public String currentRoot;
    /** Policy for unknown if-conditions: run both branches (registration code) instead of skipping. */
    public boolean bothBranches;

    public Interpreter(SourceModel src, VanillaIndex vi) {
        this.src = src;
        this.vi = vi;
    }

    /**
     * Binary name of a type: noclasspath Spoon spells unresolved nested types with dots
     * ({@code net.minecraft.world.item.Item.Properties}); builtins use {@code Item$Properties}.
     */
    public String bin(String qn) {
        if (qn == null || src.type(qn) != null) {
            return qn;
        }
        if (!qn.isEmpty() && Character.isUpperCase(qn.charAt(0))) {
            // unqualified (noclasspath could not resolve the import): Outer.Inner or Simple
            int dot = qn.indexOf('.');
            String head = dot < 0 ? qn : qn.substring(0, dot);
            String q = src.importedType(head);
            if (q != null) {
                qn = q + (dot < 0 ? "" : qn.substring(dot));
                if (src.type(qn) != null) {
                    return qn;
                }
            }
        }
        if (qn.indexOf('$') >= 0) {
            return qn;
        }
        int lt = qn.indexOf('<');
        String base = lt < 0 ? qn : qn.substring(0, lt);
        String[] parts = base.split("\\.");
        StringBuilder sb = new StringBuilder();
        boolean inType = false;
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(inType ? '$' : '.');
            }
            sb.append(parts[i]);
            if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                inType = true;
            }
        }
        return sb.toString();
    }

    /**
     * Qualified type name if the expression actually denotes a type (noclasspath may parse {@code A.B} of an
     * unresolved nested type as a variable or field read), else null.
     */
    String typeNameOf(CtExpression<?> e, Frame f) {
        if (e instanceof CtTypeAccess<?> ta) {
            return qn(ta.getAccessedType());
        }
        if (e instanceof CtFieldAccess<?> fa) {
            String name = fa.getVariable().getSimpleName();
            if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) {
                return null;
            }
            String owner = fa.getTarget() == null ? null : typeNameOf(fa.getTarget(), f);
            if (owner == null) {
                return null;
            }
            CtType<?> ot = src.type(owner);
            if (ot != null && findField(ot, name) != null) {
                return null; // a real static field
            }
            if (ot == null && name.equals(name.toUpperCase(java.util.Locale.ROOT)) && name.length() > 1) {
                return null; // CONSTANT_CASE: a vanilla static field
            }
            return bin(owner + "." + name);
        }
        if (e instanceof CtVariableRead<?> vr && !(e instanceof CtFieldAccess<?>) && !(e instanceof CtThisAccess<?>)) {
            String name = vr.getVariable().getSimpleName();
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !f.scope.has(name)) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String last = name.substring(dot + 1);
                    String head = bin(name.substring(0, dot));
                    CtType<?> ht = head == null ? null : src.type(head);
                    if (ht != null && findField(ht, last) != null
                            || ht == null && last.length() > 1 && last.equals(last.toUpperCase(java.util.Locale.ROOT))) {
                        return null; // Type.FIELD, not a nested type
                    }
                }
                String q = bin(name);
                return q != null && (q.contains(".") || src.type(q) != null) ? q : null;
            }
        }
        return null;
    }

    /**
     * Name of an explicit type access. noclasspath resolution can bind {@code Tags.Items} to an imported
     * {@code Items}; the source text is authoritative when it is a dotted type name.
     */
    /** Original source text of an element, or null. */
    static String sourceText(CtElement e) {
        try {
            var pos = e.getPosition();
            if (pos != null && pos.isValidPosition()) {
                return pos.getCompilationUnit().getOriginalSourceCode().substring(pos.getSourceStart(),
                        pos.getSourceEnd() + 1).strip();
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    String typeAccessName(CtTypeAccess<?> ta) {
        String fromRef = qn(ta.getAccessedType());
        String text = null;
        try {
            var pos = ta.getPosition();
            if (pos != null && pos.isValidPosition()) {
                text = pos.getCompilationUnit().getOriginalSourceCode().substring(pos.getSourceStart(),
                        pos.getSourceEnd() + 1).strip();
            }
        } catch (RuntimeException e) {
            text = null;
        }
        if (text == null || text.indexOf('.') < 0) {
            return fromRef;
        }
        for (char c : text.toCharArray()) {
            if (!(Character.isJavaIdentifierPart(c) || c == '.')) {
                return fromRef;
            }
        }
        int dot = text.indexOf('.');
        String head = src.importedType(text.substring(0, dot), ta);
        String fromText = head == null ? bin(text) : bin(head + text.substring(dot));
        if (fromText == null || fromRef != null && fromRef.replace('$', '.').endsWith(text)) {
            return fromRef;
        }
        return fromText;
    }

    /** Binary name of a type reference, using its declaring type when Spoon kept one (Tags.Items → Tags$Items). */
    public String qn(CtTypeReference<?> r) {
        if (r == null) {
            return null;
        }
        CtTypeReference<?> d = r.getDeclaringType();
        if (d != null) {
            String outer = qn(d);
            if (outer != null) {
                return outer + "$" + r.getSimpleName();
            }
        }
        return bin(r.getQualifiedName());
    }

    public SourceModel source() {
        return src;
    }

    public VanillaIndex vanilla() {
        return vi;
    }

    public void addBuiltins(Builtins b) {
        builtins.add(b);
    }

    public List<Builtins> builtins() {
        return builtins;
    }

    /** Called with "owner#field" when a static field is read that only gets assigned by code not run yet. */
    public void setMissingStaticHook(Consumer<String> hook) {
        this.missingStaticHook = hook;
    }

    public void resetBudget(long steps) {
        this.steps = 0;
        this.budget = steps;
    }

    public boolean wasReached(CtElement site) {
        return reached.contains(site);
    }

    /** Reason why a call site was skipped (undecidable control flow), if known. */
    public String skipReason(CtElement site) {
        return skipped.get(site);
    }

    /** Non-empty while executing a branch whose condition is unknown (bothBranches mode). */
    public List<String> activeConditions() {
        return List.copyOf(conditions);
    }

    public Map<String, Object> statics() {
        return statics;
    }

    // ---- signals -------------------------------------------------------------------------------------------

    static final class ReturnSig extends RuntimeException {
        final Object value;

        ReturnSig(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    static final class BreakSig extends RuntimeException {
        final String label;

        BreakSig(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    static final class ContinueSig extends RuntimeException {
        final String label;

        ContinueSig(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    /** Aborts the current method: its result is unknown. */
    public static final class Abort extends RuntimeException {
        public Abort(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Step budget exhausted: aborts the whole query. */
    public static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super("step budget exceeded", null, false, false);
        }
    }

    /** Execution context of one method activation. */
    public static final class Frame {
        final Scope scope;
        final Object thiz;
        final CtType<?> cls;

        Frame(Scope scope, Object thiz, CtType<?> cls) {
            this.scope = scope;
            this.thiz = thiz;
            this.cls = cls;
        }
    }

    private void step() {
        if (++steps > budget) {
            throw new BudgetExceeded();
        }
    }

    // ---- public entry points -------------------------------------------------------------------------------

    /** Runs a source method; returns its value (V.NULL for void) or Unknown. */
    public Object call(CtExecutable<?> m, Object thiz, List<Object> args) {
        CtType<?> cls = m.getParent(CtType.class);
        return topLevel(() -> callMethod(m, thiz, cls, args));
    }

    /** Top-level queries (depth 0) get a fresh step count and never throw on budget exhaustion. */
    public Object topLevel(java.util.function.Supplier<Object> body) {
        if (depth != 0) {
            return body.get();
        }
        steps = 0;
        try {
            return body.get();
        } catch (BudgetExceeded e) {
            depth = 0;
            return new Unknown("step budget exceeded");
        } catch (Abort a) {
            depth = 0;
            return new Unknown(a.getMessage());
        } catch (ReturnSig | BreakSig | ContinueSig sig) {
            depth = 0;
            return new Unknown("control flow escaped");
        } catch (StackOverflowError so) {
            depth = 0;
            return new Unknown("stack overflow");
        }
    }

    /** Evaluates an expression in a fresh static context of {@code cls}. */
    public Object evalIn(CtExpression<?> e, CtType<?> cls, Object thiz) {
        return topLevel(() -> eval(e, new Frame(new Scope(null), thiz, cls)));
    }

    public long budget() {
        return budget;
    }

    /** Instantiates a source class. */
    public Object newInstance(CtType<?> cls, List<Object> args) {
        return topLevel(() -> construct(cls, args, null, null, null));
    }

    // ---- statements ----------------------------------------------------------------------------------------

    void execBlock(List<CtStatement> stmts, Frame f) {
        int mark = conditions.size();
        try {
            for (CtStatement s : stmts) {
                exec(s, f);
            }
        } finally {
            while (conditions.size() > mark) {
                conditions.pop();
            }
        }
    }

    void exec(CtStatement s, Frame f) {
        step();
        if (s == null || s instanceof CtComment) {
            return;
        }
        String label = s.getLabel();
        try {
            execInner(s, f);
        } catch (BreakSig b) {
            if (label != null && label.equals(b.label) && !(s instanceof CtLoop)) {
                return;
            }
            throw b;
        }
    }

    private void execInner(CtStatement s, Frame f) {
        if (s instanceof CtBlock<?> b) {
            Frame inner = new Frame(new Scope(f.scope), f.thiz, f.cls);
            execBlock(b.getStatements(), inner);
        } else if (s instanceof CtLocalVariable<?> lv) {
            Object v = lv.getDefaultExpression() == null ? defaultFor(lv.getType())
                    : coerce(eval(lv.getDefaultExpression(), f), lv.getType());
            f.scope.declare(lv.getSimpleName(), v);
        } else if (s instanceof CtIf iff) {
            Object c = eval(iff.getCondition(), f);
            if (c instanceof Boolean bool) {
                CtStatement branch = bool ? iff.getThenStatement() : iff.getElseStatement();
                if (branch != null) {
                    exec(branch, f);
                }
            } else {
                undecidableIf(iff, f, reasonOf(c, iff.getCondition()));
            }
        } else if (s instanceof CtFor loop) {
            execFor(loop, f);
        } else if (s instanceof CtForEach loop) {
            execForEach(loop, f);
        } else if (s instanceof CtWhile loop) {
            int n = 0;
            while (true) {
                Object c = eval(loop.getLoopingExpression(), f);
                if (!(c instanceof Boolean)) {
                    skipStatement(loop, f, reasonOf(c, loop.getLoopingExpression()));
                    return;
                }
                if (!(Boolean) c) {
                    break;
                }
                if (++n > MAX_LOOP) {
                    skipStatement(loop, f, "loop iteration limit");
                    return;
                }
                if (!loopBody(loop, loop.getBody(), f)) {
                    break;
                }
            }
        } else if (s instanceof CtDo loop) {
            int n = 0;
            while (true) {
                if (++n > MAX_LOOP) {
                    skipStatement(loop, f, "loop iteration limit");
                    return;
                }
                if (!loopBody(loop, loop.getBody(), f)) {
                    break;
                }
                Object c = eval(loop.getLoopingExpression(), f);
                if (!(c instanceof Boolean)) {
                    skipStatement(loop, f, reasonOf(c, loop.getLoopingExpression()));
                    return;
                }
                if (!(Boolean) c) {
                    break;
                }
            }
        } else if (s instanceof CtReturn<?> r) {
            Object v = r.getReturnedExpression() == null ? V.NULL : eval(r.getReturnedExpression(), f);
            throw new ReturnSig(v);
        } else if (s instanceof CtBreak br) {
            throw new BreakSig(br.getTargetLabel());
        } else if (s instanceof CtContinue co) {
            throw new ContinueSig(co.getTargetLabel());
        } else if (s instanceof CtThrow) {
            throw new Abort("throws at " + where(s));
        } else if (s instanceof CtTry t) {
            exec(t.getBody(), f);
            if (t.getFinalizer() != null) {
                exec(t.getFinalizer(), f);
            }
        } else if (s instanceof CtSynchronized sy) {
            exec(sy.getBlock(), f);
        } else if (s instanceof CtSwitch<?> sw) {
            execSwitch(sw, f);
        } else if (s instanceof CtAssert<?>) {
            // assertions have no effect on facts
        } else if (s instanceof CtType<?>) {
            // local class declaration: nothing to execute
        } else if (s instanceof CtExpression<?> e) {
            eval(e, f);
        } else {
            skipStatement(s, f, "unsupported statement " + s.getClass().getSimpleName());
        }
    }

    /** @return false if the loop was left with break */
    private boolean loopBody(CtLoop loop, CtStatement body, Frame f) {
        try {
            exec(body, new Frame(new Scope(f.scope), f.thiz, f.cls));
        } catch (BreakSig b) {
            if (b.label == null || b.label.equals(loop.getLabel())) {
                return false;
            }
            throw b;
        } catch (ContinueSig c) {
            if (c.label != null && !c.label.equals(loop.getLabel())) {
                throw c;
            }
        }
        return true;
    }

    private void execFor(CtFor loop, Frame outer) {
        Frame f = new Frame(new Scope(outer.scope), outer.thiz, outer.cls);
        for (CtStatement init : loop.getForInit()) {
            exec(init, f);
        }
        int n = 0;
        while (true) {
            if (loop.getExpression() != null) {
                Object c = eval(loop.getExpression(), f);
                if (!(c instanceof Boolean)) {
                    skipStatement(loop, outer, reasonOf(c, loop.getExpression()));
                    return;
                }
                if (!(Boolean) c) {
                    break;
                }
            }
            if (++n > MAX_LOOP) {
                skipStatement(loop, outer, "loop iteration limit");
                return;
            }
            if (!loopBody(loop, loop.getBody(), f)) {
                break;
            }
            for (CtStatement u : loop.getForUpdate()) {
                exec(u, f);
            }
        }
    }

    private void execForEach(CtForEach loop, Frame outer) {
        Object it = eval(loop.getExpression(), outer);
        Object iterator = sourceIterator(it);
        if (iterator instanceof Obj iter) {
            // lazy: iterators may hand out the same mutable object each time (MutableBlockPos)
            String var = loop.getVariable().getSimpleName();
            for (int n = 0; ; n++) {
                if (n > MAX_LOOP) {
                    skipStatement(loop, outer, "loop iteration limit");
                    return;
                }
                Object has = invokeAny(iter, "hasNext", List.of(), null);
                if (!(has instanceof Boolean)) {
                    skipStatement(loop, outer, "iterator hasNext not computable: " + describe(has));
                    return;
                }
                if (!(Boolean) has) {
                    return;
                }
                Object item = invokeAny(iter, "next", List.of(), null);
                Frame f = new Frame(new Scope(outer.scope), outer.thiz, outer.cls);
                f.scope.declare(var, item);
                if (!loopBody(loop, loop.getBody(), f)) {
                    return;
                }
            }
        }
        List<Object> items = iterate(it);
        if (items == null) {
            skipStatement(loop, outer, it instanceof Unknown u ? "loop over unknown values: " + u.reason()
                    : "loop over unsupported iterable " + describe(it));
            return;
        }
        String var = loop.getVariable().getSimpleName();
        for (Object item : items) {
            Frame f = new Frame(new Scope(outer.scope), outer.thiz, outer.cls);
            f.scope.declare(var, item);
            if (!loopBody(loop, loop.getBody(), f)) {
                break;
            }
        }
    }

    /** Source iterator (an interpreted object with hasNext/next) for an Iterable value, or null. */
    private Object sourceIterator(Object it) {
        Object cur = it;
        for (int i = 0; i < 3; i++) {
            if (cur instanceof Fn fn) {
                cur = callFn(fn, List.of()); // Iterable as a lambda: () -> iterator
                continue;
            }
            if (cur instanceof Obj o) {
                if (findMethod(o.type, "hasNext", List.of(), null) != null) {
                    return o;
                }
                if (findMethod(o.type, "iterator", List.of(), null) != null) {
                    cur = invokeVirtual(o, "iterator", List.of(), null);
                    continue;
                }
            }
            return null;
        }
        return null;
    }

    /** Snapshot of the elements of an iterable value, or null if not iterable/unknown. */
    public List<Object> iterate(Object it) {
        if (it instanceof Seq seq) {
            return new ArrayList<>(seq.items);
        }
        if (it instanceof V.MapV) {
            return null;
        }
        if (it instanceof V.Iter iter) {
            return new ArrayList<>(iter.items.subList(iter.index, iter.items.size()));
        }
        if (it instanceof Obj o) {
            Object iterator = invokeVirtual(o, "iterator", List.of(), null);
            List<Object> out = new ArrayList<>();
            if (iterator instanceof V.Iter iter) {
                return new ArrayList<>(iter.items);
            }
            if (!(iterator instanceof Obj)) {
                return null;
            }
            for (int i = 0; i < MAX_LOOP; i++) {
                Object has = invokeAny(iterator, "hasNext", List.of(), null);
                if (!(has instanceof Boolean)) {
                    return null;
                }
                if (!(Boolean) has) {
                    return out;
                }
                out.add(invokeAny(iterator, "next", List.of(), null));
            }
            return null;
        }
        return null;
    }

    private void execSwitch(CtSwitch<?> sw, Frame f) {
        Object sel = eval(sw.getSelector(), f);
        if (sel instanceof Unknown u) {
            skipStatement(sw, f, "switch on unknown value: " + u.reason());
            return;
        }
        boolean matched = false;
        try {
            for (CtCase<?> c : sw.getCases()) {
                if (!matched) {
                    if (c.getCaseExpressions().isEmpty()) {
                        matched = true; // default
                    } else {
                        for (CtExpression<?> ce : c.getCaseExpressions()) {
                            Object cv = ce instanceof CtFieldAccess<?> fa && fa.getTarget() == null
                                    || ce instanceof CtFieldAccess<?> fa2 && fa2.getTarget() instanceof CtTypeAccess<?>
                                            && ((CtTypeAccess<?>) fa2.getTarget()).isImplicit()
                                    ? enumCaseName(ce) : eval(ce, f);
                            if (sameCase(sel, cv)) {
                                matched = true;
                                break;
                            }
                        }
                    }
                }
                if (matched) {
                    Frame inner = new Frame(new Scope(f.scope), f.thiz, f.cls);
                    execBlock(c.getStatements(), inner);
                }
            }
        } catch (BreakSig b) {
            if (b.label != null) {
                throw b;
            }
        }
        // a default case textually before the matching case is not modelled precisely; good enough for data code
    }

    private Object enumCaseName(CtExpression<?> ce) {
        return new V.Sym("?", ((CtFieldAccess<?>) ce).getVariable().getSimpleName());
    }

    private boolean sameCase(Object sel, Object cv) {
        if (cv instanceof V.Sym s && s.owner().equals("?")) {
            if (sel instanceof Obj o && o.enumName != null) {
                return o.enumName.equals(s.name());
            }
            if (sel instanceof V.Sym ss) {
                return ss.name().equals(s.name());
            }
        }
        return V.same(sel, cv) || sel instanceof String && sel.equals(cv);
    }

    private void undecidableIf(CtIf iff, Frame f, String reason) {
        boolean thenEscapes = escapes(iff.getThenStatement());
        boolean elseEscapes = escapes(iff.getElseStatement());
        if (bothBranches && !thenEscapes && !elseEscapes) {
            conditions.push(snippet(iff.getCondition()));
            try {
                if (iff.getThenStatement() != null) {
                    exec(iff.getThenStatement(), f);
                }
                if (iff.getElseStatement() != null) {
                    exec(iff.getElseStatement(), f);
                }
            } finally {
                conditions.pop();
            }
            return;
        }
        if (bothBranches && iff.getElseStatement() == null && isBareJump(iff.getThenStatement())) {
            // "if (config) continue/return;" in registration code: the rest may or may not run; run it, flagged
            conditions.push("!(" + snippet(iff.getCondition()) + ")");
            return; // popped when the enclosing block ends
        }
        skipStatement(iff, f, "undecidable condition " + snippet(iff.getCondition()) + " (" + reason + ")");
    }

    /** continue or a value-less return (possibly in a block of its own). */
    private static boolean isBareJump(CtStatement s) {
        if (s instanceof CtBlock<?> b) {
            return b.getStatements().size() == 1 && isBareJump(b.getStatements().get(0));
        }
        return s instanceof CtContinue || s instanceof CtReturn<?> r && r.getReturnedExpression() == null;
    }

    /** True if the statement contains return/throw/break/continue that leaves it (lambdas and classes excluded). */
    private static boolean escapes(CtStatement s) {
        if (s == null) {
            return false;
        }
        for (CtElement e : s.getElements(new TypeFilter<>(CtStatement.class))) {
            if (e instanceof CtReturn<?> || e instanceof CtThrow || e instanceof CtBreak || e instanceof CtContinue) {
                if (insideNested(e, s)) {
                    continue;
                }
                if (e instanceof CtBreak || e instanceof CtContinue) {
                    // break/continue of a loop or switch nested inside s does not escape
                    CtElement p = e.getParent();
                    boolean inner = false;
                    while (p != null && p != s) {
                        if (p instanceof CtLoop || e instanceof CtBreak && p instanceof CtSwitch<?>) {
                            inner = true;
                            break;
                        }
                        p = p.getParent();
                    }
                    if (inner) {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean insideNested(CtElement e, CtElement root) {
        CtElement p = e.getParent();
        while (p != null && p != root) {
            if (p instanceof CtLambda<?> || p instanceof CtType<?>) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /**
     * Skips a statement whose control flow cannot be decided: everything it could assign becomes unknown, objects
     * it calls methods on are tainted, tracked call sites inside remember the reason. A statement that could
     * return/throw aborts the method (its result would otherwise be a guess).
     */
    private void skipStatement(CtStatement s, Frame f, String reason) {
        for (CtInvocation<?> inv : s.getElements(new TypeFilter<>(CtInvocation.class))) {
            skipped.putIfAbsent(inv, reason);
        }
        for (CtConstructorCall<?> cc : s.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            skipped.putIfAbsent(cc, reason);
        }
        Unknown u = new Unknown(reason);
        for (CtElement e : s.getElements(new TypeFilter<>(CtElement.class))) {
            if (insideNested(e, s)) {
                continue;
            }
            if (e instanceof CtVariableWrite<?> w && !(w instanceof CtFieldWrite<?>)) {
                if (f.scope.has(w.getVariable().getSimpleName())) {
                    f.scope.set(w.getVariable().getSimpleName(), u);
                }
            } else if (e instanceof CtFieldWrite<?> fw) {
                taintFieldWrite(fw, f, u);
            } else if (e instanceof CtInvocation<?> inv) {
                CtExpression<?> t = inv.getTarget();
                if (t instanceof CtVariableRead<?> vr && !(vr instanceof CtFieldAccess<?>)
                        && f.scope.has(vr.getVariable().getSimpleName())) {
                    Object cur = f.scope.get(vr.getVariable().getSimpleName());
                    if (cur instanceof Obj o) {
                        o.taint = reason;
                    } else {
                        f.scope.set(vr.getVariable().getSimpleName(), u);
                    }
                } else if ((t == null || t instanceof CtThisAccess<?>) && f.thiz instanceof Obj o) {
                    o.taint = reason;
                }
            }
        }
        if (escapes(s)) {
            throw new Abort(reason);
        }
    }

    private void taintFieldWrite(CtFieldWrite<?> fw, Frame f, Unknown u) {
        CtFieldReference<?> ref = fw.getVariable();
        CtExpression<?> t = fw.getTarget();
        if (t instanceof CtTypeAccess<?> ta) {
            statics.put(qn(ta.getAccessedType()) + "#" + ref.getSimpleName(), u);
        } else if ((t == null || t instanceof CtThisAccess<?>) && f.thiz instanceof Obj o) {
            if (isStaticField(ref, f.cls)) {
                statics.put(staticKey(ref, f.cls), u);
            } else {
                o.fields.put(ref.getSimpleName(), u);
            }
        }
    }

    // ---- expressions ---------------------------------------------------------------------------------------

    public Object eval(CtExpression<?> e, Frame f) {
        step();
        Object v = evalInner(e, f);
        List<CtTypeReference<?>> casts = e.getTypeCasts();
        for (int i = casts.size() - 1; i >= 0; i--) {
            v = cast(v, casts.get(i));
        }
        return v;
    }

    private Object evalInner(CtExpression<?> e, Frame f) {
        if (e instanceof CtLiteral<?> lit) {
            Object val = lit.getValue();
            return val == null ? V.NULL : val;
        }
        if (e instanceof CtFieldAccess<?> fa) {
            return readField(fa, f);
        }
        if (e instanceof CtSuperAccess<?> || e instanceof CtThisAccess<?>) {
            if (e instanceof CtThisAccess<?> ta && ta.getTarget() instanceof CtTypeAccess<?> tt && f.thiz instanceof Obj o
                    && !isSameOrSub(o.type, qn(tt.getAccessedType()))) {
                return outerOf(o, qn(tt.getAccessedType()));
            }
            return f.thiz == null ? new Unknown("no 'this' in static context") : f.thiz;
        }
        if (e instanceof CtVariableAccess<?> vr) {
            String name = vr.getVariable().getSimpleName();
            if (f.scope.has(name)) {
                return f.scope.get(name);
            }
            if (f.thiz instanceof Obj o && o.captured != null && o.captured.has(name)) {
                return o.captured.get(name);
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                // noclasspath: Type.CONSTANT of an unresolved type parsed as one variable name
                String type = bin(name.substring(0, dot));
                if (type != null && (type.contains(".") || src.type(type) != null)) {
                    return getStatic(type, name.substring(dot + 1));
                }
            }
            return new Unknown("unbound variable " + name);
        }
        if (e instanceof CtInvocation<?> inv) {
            return evalInvocation(inv, f);
        }
        if (e instanceof CtConstructorCall<?> cc) {
            return evalNew(cc, f);
        }
        if (e instanceof CtBinaryOperator<?> bo) {
            return evalBinary(bo, f);
        }
        if (e instanceof CtUnaryOperator<?> uo) {
            return evalUnary(uo, f);
        }
        if (e instanceof CtConditional<?> c) {
            Object cond = eval(c.getCondition(), f);
            if (cond instanceof Boolean b) {
                return eval(b ? c.getThenExpression() : c.getElseExpression(), f);
            }
            return new Unknown("undecidable ?: " + snippet(c.getCondition()) + " (" + reasonOf(cond, c) + ")");
        }
        if (e instanceof CtAssignment<?, ?> as) {
            return evalAssignment(as, f);
        }
        if (e instanceof CtLambda<?> lam) {
            return new Fn(lam, null, null, f.scope, f.thiz, f.cls);
        }
        if (e instanceof CtExecutableReferenceExpression<?, ?> ref) {
            Object recv = null;
            CtExpression<?> t = ref.getTarget();
            if (t != null && !(t instanceof CtTypeAccess<?>)) {
                recv = eval(t, f);
            }
            return new Fn(null, ref, recv, f.scope, f.thiz, f.cls);
        }
        if (e instanceof CtNewArray<?> na) {
            return evalNewArray(na, f);
        }
        if (e instanceof CtArrayAccess<?, ?> ar) {
            Object arr = eval(ar.getTarget(), f);
            Object idx = eval(ar.getIndexExpression(), f);
            if (arr instanceof Seq s && idx instanceof Number n) {
                int i = n.intValue();
                return i >= 0 && i < s.items.size() ? s.items.get(i) : new Unknown("array index out of bounds");
            }
            return firstUnknownOr(new Unknown("array read on " + describe(arr)), arr, idx);
        }
        if (e instanceof CtTypeAccess<?> ta) {
            return new V.ClassV(qn(ta.getAccessedType()));
        }
        if (e instanceof CtCodeSnippetExpression<?>) {
            return new Unknown("code snippet");
        }
        return new Unknown("unsupported expression " + e.getClass().getSimpleName());
    }

    private Object outerOf(Obj o, String outerType) {
        Object cur = o.outer;
        while (cur instanceof Obj oo) {
            if (isSameOrSub(oo.type, outerType)) {
                return oo;
            }
            cur = oo.outer;
        }
        return new Unknown("no enclosing instance of " + outerType);
    }

    private Object firstUnknownOr(Unknown fallback, Object... vals) {
        for (Object v : vals) {
            if (v instanceof Unknown u) {
                return u;
            }
        }
        return fallback;
    }

    private Object evalNewArray(CtNewArray<?> na, Frame f) {
        if (!na.getElements().isEmpty() || na.getDimensionExpressions().isEmpty()) {
            List<Object> items = new ArrayList<>();
            for (CtExpression<?> el : na.getElements()) {
                items.add(eval(el, f));
            }
            return new Seq("array", items);
        }
        Object dim = eval(na.getDimensionExpressions().get(0), f);
        if (!(dim instanceof Number n) || n.intValue() < 0 || n.intValue() > MAX_LOOP) {
            return firstUnknownOr(new Unknown("array size"), dim);
        }
        CtTypeReference<?> comp = na.getType() instanceof spoon.reflect.reference.CtArrayTypeReference<?> at
                ? at.getComponentType() : null;
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < n.intValue(); i++) {
            items.add(na.getDimensionExpressions().size() > 1 ? new Unknown("multi-dim array") : defaultFor(comp));
        }
        return new Seq("array", items);
    }

    // ---- fields --------------------------------------------------------------------------------------------

    private Object readField(CtFieldAccess<?> fa, Frame f) {
        CtFieldReference<?> ref = fa.getVariable();
        String name = ref.getSimpleName();
        CtExpression<?> t = fa.getTarget();
        if (name.equals("class") && t != null) {
            String tn = typeNameOf(t, f);
            return tn == null ? new Unknown("class literal") : new V.ClassV(tn);
        }
        if (t instanceof CtTypeAccess<?> ta && !ta.isImplicit()) {
            String owner = typeAccessName(ta);
            String text = sourceText(fa);
            while (text != null && text.startsWith("(") && text.indexOf(')') > 0) {
                text = text.substring(text.indexOf(')') + 1).strip(); // a cast in front of the access
            }
            if (text != null && text.endsWith("." + name) && text.indexOf('.') < text.length() - name.length() - 1) {
                // "Outer.Inner.FIELD" in the source: trust the text over the noclasspath binding
                String prefix = text.substring(0, text.length() - name.length() - 1);
                boolean plain = true;
                for (char c : prefix.toCharArray()) {
                    plain &= Character.isJavaIdentifierPart(c) || c == '.';
                }
                if (plain) {
                    int dot = prefix.indexOf('.');
                    String head = src.importedType(prefix.substring(0, dot), fa);
                    String q = head == null ? bin(prefix) : bin(head + prefix.substring(dot));
                    if (q != null && !q.replace('$', '.').endsWith(prefix.replace('$', '.')) == false
                            && !q.equals(owner)) {
                        owner = q;
                    }
                }
            }
            return getStatic(owner, name);
        }
        if (t != null && !(t instanceof CtThisAccess<?>) && !(t instanceof CtSuperAccess<?>)) {
            String tn = typeNameOf(t, f);
            if (tn != null) {
                return getStatic(tn, name);
            }
        }
        if (t == null || t instanceof CtThisAccess<?> && ((CtThisAccess<?>) t).isImplicit()
                || t instanceof CtTypeAccess<?>) {
            // implicit: local field of this (or outer), or a static of the current class hierarchy
            if (isStaticField(ref, f.cls)) {
                return getStatic(staticOwner(ref, f.cls), name);
            }
            if (f.thiz instanceof Obj o) {
                Object v = instanceField(o, name);
                if (v != null) {
                    return v;
                }
                Object cur = o.outer;
                while (cur instanceof Obj oo) {
                    v = instanceField(oo, name);
                    if (v != null) {
                        return v;
                    }
                    cur = oo.outer;
                }
            }
            if (f.thiz instanceof Obj o && findField(o.type, name) == null) {
                Object r = vanillaField(o, name);
                if (r != null) {
                    return r;
                }
            }
            if (ref.getDeclaringType() != null) {
                return getStatic(qn(ref.getDeclaringType()), name);
            }
            return new Unknown("field " + name + " not found");
        }
        Object target = t instanceof CtThisAccess<?> || t instanceof CtSuperAccess<?> ? (f.thiz == null
                ? new Unknown("no this") : eval(t, f)) : eval(t, f);
        if (target instanceof Obj o) {
            Object v = instanceField(o, name);
            if (v != null) {
                return v;
            }
            if (isStaticField(ref, o.type)) {
                return getStatic(staticOwner(ref, o.type), name);
            }
            if (findField(o.type, name) == null) {
                Object r = vanillaField(o, name);
                if (r != null) {
                    return r;
                }
            }
            return new Unknown("field " + name + " of " + o.type.getSimpleName() + " not set");
        }
        if (target instanceof Seq s && name.equals("length")) {
            return s.items.size();
        }
        if (target instanceof V.VObj vo && vo.fields.containsKey(name)) {
            return vo.fields.get(name);
        }
        if (target instanceof Unknown u) {
            return u;
        }
        String off = vi.official(name);
        for (Builtins b : builtins) {
            Object r = b.invoke(this, new Builtins.Call(null, "$field:" + off, target, List.of(), null));
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return new Unknown("field " + off + " of " + describe(target));
    }

    /** A field inherited from a vanilla superclass (stateDefinition...), via builtins; null if not modelled. */
    private Object vanillaField(Obj o, String name) {
        String off = vi.official(name);
        for (Builtins b : builtins) {
            Object r = b.invoke(this, new Builtins.Call(o.vanillaSuper, "$field:" + off, o, List.of(), null));
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return null;
    }

    private Object instanceField(Obj o, String name) {
        if (o.fields.containsKey(name)) {
            return o.fields.get(name);
        }
        return null;
    }

    private boolean isStaticField(CtFieldReference<?> ref, CtType<?> ctx) {
        CtField<?> decl = findField(ctx, ref.getSimpleName());
        if (decl != null) {
            return decl.isStatic() || decl.getDeclaringType() != null && decl.getDeclaringType().isInterface();
        }
        return ref.isStatic();
    }

    private String staticOwner(CtFieldReference<?> ref, CtType<?> ctx) {
        CtField<?> decl = findField(ctx, ref.getSimpleName());
        if (decl != null) {
            return bin(decl.getDeclaringType().getQualifiedName());
        }
        return ref.getDeclaringType() == null ? (ctx == null ? "?" : ctx.getQualifiedName())
                : qn(ref.getDeclaringType());
    }

    private String staticKey(CtFieldReference<?> ref, CtType<?> ctx) {
        return staticOwner(ref, ctx) + "#" + ref.getSimpleName();
    }

    /** Field declared in t, its superclasses, interfaces or enclosing types. */
    private final Map<String, Boolean> shadowedFields = new HashMap<>();

    /** Whether two classes of t's source superclass chain declare an instance field with this name. */
    boolean shadowed(CtType<?> t, String name) {
        return shadowedFields.computeIfAbsent(t.getQualifiedName() + "#" + name, k -> {
            int n = 0;
            for (CtType<?> cur = t; cur != null; cur = superType(cur)) {
                CtField<?> fd = cur.getField(name);
                if (fd != null && !fd.isStatic()) {
                    n++;
                }
            }
            return n > 1;
        });
    }

    public CtField<?> findField(CtType<?> t, String name) {
        Set<CtType<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CtType<?> cur = t; cur != null; cur = cur.getDeclaringType()) {
            CtField<?> fd = findFieldInHierarchy(cur, name, seen);
            if (fd != null) {
                return fd;
            }
        }
        return null;
    }

    private CtField<?> findFieldInHierarchy(CtType<?> t, String name, Set<CtType<?>> seen) {
        if (t == null || !seen.add(t)) {
            return null;
        }
        CtField<?> fd = t.getField(name);
        if (fd != null) {
            return fd;
        }
        CtType<?> sup = superType(t);
        CtField<?> r = findFieldInHierarchy(sup, name, seen);
        if (r != null) {
            return r;
        }
        for (CtTypeReference<?> i : t.getSuperInterfaces()) {
            r = findFieldInHierarchy(src.type(i.getQualifiedName()), name, seen);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    public CtType<?> superType(CtType<?> t) {
        CtTypeReference<?> s = t.getSuperclass();
        return s == null ? null : src.type(s.getQualifiedName());
    }

    /** Reads a static field; source statics run their class initialiser, others go to builtins. */
    public Object getStatic(String owner, String name) {
        String key = owner + "#" + name;
        if (statics.containsKey(key)) {
            return statics.get(key);
        }
        CtType<?> t = src.type(owner);
        if (t != null) {
            CtField<?> decl = findField(t, name);
            if (decl == null) {
                return new Unknown("static field " + key + " not found");
            }
            CtType<?> declType = decl.getDeclaringType();
            String dkey = declType.getQualifiedName() + "#" + name;
            classInit(declType);
            if (statics.containsKey(dkey)) {
                Object v = statics.get(dkey);
                if (v == V.NULL && decl.getDefaultExpression() == null && !decl.isFinal()) {
                    missingStaticHook.accept(dkey);
                    v = statics.get(dkey);
                }
                return v;
            }
            return new Unknown("static field " + dkey + " not initialised");
        }
        String off = vi.official(name);
        for (Builtins b : builtins) {
            Object r = b.getStatic(this, owner, off);
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return new V.Sym(owner, off);
    }

    public void putStatic(String key, Object value) {
        statics.put(key, value);
    }

    /** Runs static field initialisers and static blocks of a source type once, in textual order. */
    public void classInit(CtType<?> t) {
        String qn = t.getQualifiedName();
        if (!classInitDone.add(qn)) {
            return;
        }
        Frame f = new Frame(new Scope(null), null, t);
        if (t instanceof CtEnum<?> en) {
            int ord = 0;
            for (CtEnumValue<?> ev : en.getEnumValues()) {
                Obj o = enumConstant(en, ev, ord++, f);
                statics.put(qn + "#" + ev.getSimpleName(), o);
            }
        }
        for (CtTypeMember m : t.getTypeMembers()) {
            if (m instanceof CtEnumValue<?>) {
                continue;
            }
            if (m instanceof CtField<?> fd && fd.isStatic() || m instanceof CtField<?> fd2 && t.isInterface()) {
                CtField<?> field = (CtField<?>) m;
                String key = qn + "#" + field.getSimpleName();
                if (field.getDefaultExpression() == null) {
                    statics.putIfAbsent(key, defaultFor(field.getType()));
                    continue;
                }
                statics.put(key, new Unknown("cyclic static initialisation of " + key));
                Object v;
                try {
                    v = coerce(eval(field.getDefaultExpression(), f), field.getType());
                } catch (Abort a) {
                    v = new Unknown(a.getMessage());
                }
                statics.put(key, v);
            } else if (m instanceof CtAnonymousExecutable ae && ae.isStatic()) {
                try {
                    exec(ae.getBody(), f);
                } catch (Abort | ReturnSig a) {
                    // a failing static block leaves what it did not assign at its default
                }
            }
        }
    }

    private Obj enumConstant(CtEnum<?> en, CtEnumValue<?> ev, int ordinal, Frame f) {
        CtExpression<?> init = ev.getDefaultExpression();
        List<Object> args = new ArrayList<>();
        CtType<?> type = en;
        if (init instanceof CtNewClass<?> nc && nc.getAnonymousClass() != null) {
            type = nc.getAnonymousClass();
            for (CtExpression<?> a : nc.getArguments()) {
                args.add(eval(a, f));
            }
        } else if (init instanceof CtConstructorCall<?> cc) {
            for (CtExpression<?> a : cc.getArguments()) {
                args.add(eval(a, f));
            }
        }
        Obj o = new Obj(type);
        o.enumName = ev.getSimpleName();
        o.ordinal = ordinal;
        initFieldDefaults(o, type);
        try {
            if (type != en) {
                initObject(o, (CtClass<?>) en, args, null);
                runFieldInits(o, type);
            } else {
                initObject(o, (CtClass<?>) en, args, null);
            }
        } catch (Abort a) {
            o.taint = a.getMessage();
        }
        return o;
    }

    // ---- assignment ----------------------------------------------------------------------------------------

    private Object evalAssignment(CtAssignment<?, ?> as, Frame f) {
        CtExpression<?> lhs = as.getAssigned();
        Object rhs = eval(as.getAssignment(), f);
        if (as instanceof CtOperatorAssignment<?, ?> oa) {
            Object cur = eval(lhs, f);
            rhs = binary(oa.getKind(), cur, rhs, oa);
            if (cur instanceof Number || cur instanceof Character) {
                rhs = coerceLike(rhs, cur);
            }
        } else {
            rhs = coerce(rhs, lhs.getType());
        }
        assign(lhs, rhs, f);
        return rhs;
    }

    private void assign(CtExpression<?> lhs, Object v, Frame f) {
        if (lhs instanceof CtFieldAccess<?> fa) {
            CtFieldReference<?> ref = fa.getVariable();
            CtExpression<?> t = fa.getTarget();
            if (t instanceof CtTypeAccess<?> ta && !ta.isImplicit()) {
                CtType<?> st = src.type(qn(ta.getAccessedType()));
                String owner = st == null ? qn(ta.getAccessedType()) : staticOwner(ref, st);
                if (st != null) {
                    classInit(src.type(owner) == null ? st : src.type(owner));
                }
                statics.put(owner + "#" + ref.getSimpleName(), v);
                return;
            }
            if (t == null || t instanceof CtThisAccess<?> || t instanceof CtTypeAccess<?>) {
                if (isStaticField(ref, f.cls)) {
                    String owner = staticOwner(ref, f.cls);
                    CtType<?> st = src.type(owner);
                    if (st != null) {
                        classInit(st);
                    }
                    statics.put(owner + "#" + ref.getSimpleName(), v);
                    return;
                }
                if (f.thiz instanceof Obj o) {
                    Obj holder = o;
                    if (!o.fields.containsKey(ref.getSimpleName())) {
                        Object cur = o.outer;
                        while (cur instanceof Obj oo) {
                            if (oo.fields.containsKey(ref.getSimpleName())) {
                                holder = oo;
                                break;
                            }
                            cur = oo.outer;
                        }
                    }
                    holder.fields.put(ref.getSimpleName(), v);
                    if (holder == o && f.cls != null && shadowed(o.type, ref.getSimpleName())) {
                        // a subclass field with the same name hides this one under the plain key: keep the write of
                        // the declaring class too, as "<declaring class>#<name>" (pattern packs read both)
                        holder.fields.put(f.cls.getQualifiedName() + "#" + ref.getSimpleName(), v);
                    }
                }
                return;
            }
            Object target = eval(t, f);
            if (target instanceof Obj o) {
                o.fields.put(ref.getSimpleName(), v);
            } else if (target instanceof V.VObj vo) {
                vo.fields.put(vi.official(ref.getSimpleName()), v);
            }
            return;
        }
        if (lhs instanceof CtArrayAccess<?, ?> aw) {
            Object arr = eval(aw.getTarget(), f);
            Object idx = eval(aw.getIndexExpression(), f);
            if (arr instanceof Seq s && idx instanceof Number n && n.intValue() >= 0 && n.intValue() < s.items.size()) {
                s.items.set(n.intValue(), v);
            } else if (arr instanceof Seq s) {
                for (int i = 0; i < s.items.size(); i++) {
                    s.items.set(i, new Unknown("array write at unknown index"));
                }
            }
            return;
        }
        if (lhs instanceof CtVariableAccess<?> va) {
            String name = va.getVariable().getSimpleName();
            if (!f.scope.has(name) && f.thiz instanceof Obj o && o.captured != null && o.captured.has(name)) {
                o.captured.set(name, v);
            } else {
                f.scope.set(name, v);
            }
        }
    }

    // ---- operators -----------------------------------------------------------------------------------------

    private Object evalBinary(CtBinaryOperator<?> bo, Frame f) {
        BinaryOperatorKind k = bo.getKind();
        Object l = eval(bo.getLeftHandOperand(), f);
        if (k == BinaryOperatorKind.AND) {
            if (Boolean.FALSE.equals(l)) {
                return false;
            }
            Object r = eval(bo.getRightHandOperand(), f);
            if (Boolean.TRUE.equals(l)) {
                return r instanceof Boolean ? r : unk(r, bo);
            }
            return Boolean.FALSE.equals(r) ? false : unk(l, bo);
        }
        if (k == BinaryOperatorKind.OR) {
            if (Boolean.TRUE.equals(l)) {
                return true;
            }
            Object r = eval(bo.getRightHandOperand(), f);
            if (Boolean.FALSE.equals(l)) {
                return r instanceof Boolean ? r : unk(r, bo);
            }
            return Boolean.TRUE.equals(r) ? true : unk(l, bo);
        }
        if (k == BinaryOperatorKind.INSTANCEOF) {
            CtExpression<?> rhs = bo.getRightHandOperand();
            String type = rhs instanceof CtTypeAccess<?> ta ? qn(ta.getAccessedType())
                    : rhs.getType() == null ? null : bin(rhs.getType().getQualifiedName());
            return instanceOf(l, type);
        }
        Object r = eval(bo.getRightHandOperand(), f);
        return binary(k, l, r, bo);
    }

    private Object unk(Object v, CtElement at) {
        return v instanceof Unknown u ? u : new Unknown("non-boolean operand at " + where(at));
    }

    public Object instanceOf(Object v, String type) {
        if (v instanceof Unknown u) {
            return u;
        }
        if (v == V.NULL) {
            return false;
        }
        if (type == null) {
            return new Unknown("instanceof unknown type");
        }
        if (v instanceof Obj o) {
            if (isSameOrSub(o.type, type)) {
                return true;
            }
            // vanilla supertypes are only known by name: unknown unless the source chain proves it
            if (o.vanillaSuper != null && (o.vanillaSuper.equals(type))) {
                return true;
            }
            if (src.type(type) != null) {
                return false; // a source type not in the hierarchy
            }
            return new Unknown("instanceof vanilla type " + type + " on " + o.type.getSimpleName());
        }
        if (v instanceof String) {
            return type.equals("java.lang.String") || type.equals("java.lang.Object") || type.endsWith("CharSequence");
        }
        if (v instanceof Integer) {
            return type.equals("java.lang.Integer") || type.equals("java.lang.Number") || type.equals("java.lang.Object");
        }
        if (v instanceof V.VObj vo) {
            if (vo.type.equals(type)) {
                return true;
            }
            if (src.type(type) != null) {
                return false;
            }
        }
        if (v instanceof V.State && type.endsWith("BlockState")) {
            return true;
        }
        if (v instanceof V.Stack && type.endsWith(".ItemStack")) {
            return true;
        }
        return new Unknown("instanceof " + type + " on " + describe(v));
    }

    /** True if source type t is, extends or implements the named type (source or vanilla, by qualified name). */
    public boolean isSameOrSub(CtType<?> t, String qualified) {
        return isSub(t, qualified, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private boolean isSub(CtType<?> t, String q, Set<CtType<?>> seen) {
        if (t == null || !seen.add(t)) {
            return false;
        }
        if (t.getQualifiedName().equals(q)) {
            return true;
        }
        CtTypeReference<?> sup = t.getSuperclass();
        if (sup != null) {
            if (sup.getQualifiedName().equals(q)) {
                return true;
            }
            if (isSub(src.type(sup.getQualifiedName()), q, seen)) {
                return true;
            }
        }
        for (CtTypeReference<?> i : t.getSuperInterfaces()) {
            if (i.getQualifiedName().equals(q) || isSub(src.type(i.getQualifiedName()), q, seen)) {
                return true;
            }
        }
        return false;
    }

    /** All supertypes names (source chain plus the first vanilla names) of a source type. */
    public List<String> supertypeNames(CtType<?> t) {
        List<String> out = new ArrayList<>();
        Set<CtType<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectSupers(t, out, seen);
        return out;
    }

    private void collectSupers(CtType<?> t, List<String> out, Set<CtType<?>> seen) {
        if (t == null || !seen.add(t)) {
            return;
        }
        out.add(t.getQualifiedName());
        CtTypeReference<?> sup = t.getSuperclass();
        if (sup != null) {
            CtType<?> st = src.type(sup.getQualifiedName());
            if (st == null) {
                out.add(sup.getQualifiedName());
            } else {
                collectSupers(st, out, seen);
            }
        }
        for (CtTypeReference<?> i : t.getSuperInterfaces()) {
            CtType<?> it = src.type(i.getQualifiedName());
            if (it == null) {
                out.add(i.getQualifiedName());
            } else {
                collectSupers(it, out, seen);
            }
        }
    }

    Object binary(BinaryOperatorKind k, Object l, Object r, CtElement at) {
        if (k == BinaryOperatorKind.PLUS && (l instanceof String || r instanceof String)) {
            if (l instanceof Unknown u) {
                return u;
            }
            if (r instanceof Unknown u) {
                return u;
            }
            String ls = str(l);
            String rs = str(r);
            if (ls == null || rs == null) {
                return new Unknown("string concat with " + describe(ls == null ? l : r));
            }
            return ls + rs;
        }
        if (k == BinaryOperatorKind.EQ || k == BinaryOperatorKind.NE) {
            if (l instanceof Unknown u) {
                return u;
            }
            if (r instanceof Unknown u) {
                return u;
            }
            Boolean same = sameRef(l, r);
            if (same == null) {
                return new Unknown("cannot compare " + describe(l) + " and " + describe(r));
            }
            return k == BinaryOperatorKind.EQ ? same : !same;
        }
        if (l instanceof Unknown u) {
            return u;
        }
        if (r instanceof Unknown u) {
            return u;
        }
        if (l instanceof Boolean lb && r instanceof Boolean rb) {
            return switch (k) {
                case BITAND -> lb & rb;
                case BITOR -> lb | rb;
                case BITXOR -> lb ^ rb;
                default -> new Unknown("boolean op " + k);
            };
        }
        Number ln = num(l);
        Number rn = num(r);
        if (ln == null || rn == null) {
            return new Unknown("operator " + k + " on " + describe(l) + ", " + describe(r));
        }
        boolean fp = ln instanceof Double || rn instanceof Double || ln instanceof Float || rn instanceof Float;
        boolean dbl = ln instanceof Double || rn instanceof Double;
        boolean lng = ln instanceof Long || rn instanceof Long;
        switch (k) {
            case LT, GT, LE, GE: {
                double a = ln.doubleValue();
                double b = rn.doubleValue();
                if (!fp) {
                    long la = ln.longValue();
                    long lb2 = rn.longValue();
                    return switch (k) {
                        case LT -> la < lb2;
                        case GT -> la > lb2;
                        case LE -> la <= lb2;
                        default -> la >= lb2;
                    };
                }
                return switch (k) {
                    case LT -> a < b;
                    case GT -> a > b;
                    case LE -> a <= b;
                    default -> a >= b;
                };
            }
            case PLUS, MINUS, MUL, DIV, MOD: {
                if (fp) {
                    double a = ln.doubleValue();
                    double b = rn.doubleValue();
                    double res = switch (k) {
                        case PLUS -> a + b;
                        case MINUS -> a - b;
                        case MUL -> a * b;
                        case DIV -> a / b;
                        default -> a % b;
                    };
                    return dbl ? (Object) res : (Object) (float) res;
                }
                long a = ln.longValue();
                long b = rn.longValue();
                if ((k == BinaryOperatorKind.DIV || k == BinaryOperatorKind.MOD) && b == 0) {
                    return new Unknown("division by zero");
                }
                long res = switch (k) {
                    case PLUS -> a + b;
                    case MINUS -> a - b;
                    case MUL -> a * b;
                    case DIV -> a / b;
                    default -> a % b;
                };
                return lng ? (Object) res : (Object) (int) res;
            }
            case BITAND, BITOR, BITXOR, SL, SR, USR: {
                if (fp) {
                    return new Unknown("bit op on float");
                }
                long a = ln.longValue();
                long b = rn.longValue();
                if (lng || k == BinaryOperatorKind.SL || k == BinaryOperatorKind.SR || k == BinaryOperatorKind.USR) {
                    boolean leftLong = ln instanceof Long;
                    long res = switch (k) {
                        case BITAND -> a & b;
                        case BITOR -> a | b;
                        case BITXOR -> a ^ b;
                        case SL -> leftLong ? a << b : (long) ((int) a << b);
                        case SR -> leftLong ? a >> b : (long) ((int) a >> b);
                        default -> leftLong ? a >>> b : (long) ((int) a >>> b);
                    };
                    return leftLong || lng && k != BinaryOperatorKind.SL && k != BinaryOperatorKind.SR
                            && k != BinaryOperatorKind.USR ? (Object) res : (Object) (int) res;
                }
                int ia = (int) a;
                int ib = (int) b;
                return switch (k) {
                    case BITAND -> ia & ib;
                    case BITOR -> ia | ib;
                    default -> ia ^ ib;
                };
            }
            default:
                return new Unknown("operator " + k);
        }
    }

    /** == semantics; null = cannot decide. */
    private Boolean sameRef(Object l, Object r) {
        if (l == r) {
            return true;
        }
        if (l == V.NULL || r == V.NULL) {
            return false;
        }
        if (num(l) != null && num(r) != null) {
            return V.same(l, r);
        }
        if (l instanceof Obj || r instanceof Obj || l instanceof V.VObj || r instanceof V.VObj) {
            if ((l instanceof Obj || l instanceof V.VObj) && (r instanceof Obj || r instanceof V.VObj)) {
                return false;
            }
            // an Obj compared to a symbolic vanilla constant: undecidable
            if (l instanceof V.Sym || r instanceof V.Sym || l instanceof V.RegRef || r instanceof V.RegRef) {
                return null;
            }
            return false;
        }
        if (l instanceof V.Sym || l instanceof V.RegRef || l instanceof V.Registry || l instanceof V.TagRef
                || l instanceof String || l instanceof Boolean || l instanceof V.ClassV || l instanceof V.ResLoc) {
            return l.equals(r);
        }
        return l.equals(r) ? true : null;
    }

    private Object evalUnary(CtUnaryOperator<?> uo, Frame f) {
        UnaryOperatorKind k = uo.getKind();
        if (k == UnaryOperatorKind.PREINC || k == UnaryOperatorKind.PREDEC || k == UnaryOperatorKind.POSTINC
                || k == UnaryOperatorKind.POSTDEC) {
            Object cur = eval(uo.getOperand(), f);
            Object next = binary(k == UnaryOperatorKind.PREINC || k == UnaryOperatorKind.POSTINC
                    ? BinaryOperatorKind.PLUS : BinaryOperatorKind.MINUS, cur, 1, uo);
            next = coerceLike(next, cur);
            assign(uo.getOperand(), next, f);
            return k == UnaryOperatorKind.PREINC || k == UnaryOperatorKind.PREDEC ? next : cur;
        }
        Object v = eval(uo.getOperand(), f);
        if (v instanceof Unknown u) {
            return u;
        }
        return switch (k) {
            case NOT -> v instanceof Boolean b ? !b : new Unknown("! on " + describe(v));
            case NEG -> v instanceof Integer i ? -i : v instanceof Long l ? -l : v instanceof Float fl ? -fl
                    : v instanceof Double d ? -d : v instanceof Character c ? -(int) c : new Unknown("- on " + describe(v));
            case POS -> v;
            case COMPL -> v instanceof Integer i ? ~i : v instanceof Long l ? ~l : new Unknown("~ on " + describe(v));
            default -> new Unknown("unary " + k);
        };
    }

    // ---- calls ---------------------------------------------------------------------------------------------

    private Object evalInvocation(CtInvocation<?> inv, Frame f) {
        reached.add(inv);
        CtExecutableReference<?> ex = inv.getExecutable();
        if (ex.isConstructor()) {
            return V.NULL; // this(...)/super(...) are handled by construct()
        }
        String rawName = ex.getSimpleName();
        String name = vi.official(rawName);
        CtExpression<?> t = inv.getTarget();
        List<Object> args = new ArrayList<>();
        for (CtExpression<?> a : inv.getArguments()) {
            args.add(eval(a, f));
        }
        String owner = ex.getDeclaringType() == null ? null : qn(ex.getDeclaringType());
        if (t != null && !(t instanceof CtTypeAccess<?>)) {
            String tn = typeNameOf(t, f);
            if (tn != null) {
                // static call on a type that noclasspath parsed as a variable/field
                CtType<?> st = src.type(tn);
                if (st != null) {
                    CtMethod<?> m = findMethod(st, rawName, args, ex);
                    if (m != null && m.getBody() != null) {
                        return callMethod(m, null, m.getDeclaringType(), args);
                    }
                }
                return builtin(tn, name, null, args, inv);
            }
        }
        if (preempted(owner, name)) {
            Object recv = t == null || t instanceof CtTypeAccess<?> || t instanceof CtThisAccess<?> ? null : eval(t, f);
            return builtin(owner, name, recv, args, inv);
        }
        // static call: explicit type access, or implicit target of a static method
        if (t instanceof CtTypeAccess<?> ta) {
            String tOwner = qn(ta.getAccessedType());
            if (ta.isImplicit() && f.thiz instanceof Obj o) {
                CtMethod<?> m = findMethod(o.type, rawName, args, ex);
                if (m != null && !m.isStatic()) {
                    return callMethod(m, o, m.getDeclaringType(), args);
                }
                CtMethod<?> om = findMethodInOuter(o, rawName, args, ex);
                if (om != null) {
                    return om.isStatic() ? callMethod(om, null, om.getDeclaringType(), args)
                            : callMethod(om, outerFor(o, om.getDeclaringType()), om.getDeclaringType(), args);
                }
            }
            CtType<?> st = src.type(tOwner);
            if (st == null && ta.isImplicit() && f.cls != null) {
                st = f.cls;
            }
            if (st != null) {
                CtMethod<?> m = findMethod(st, rawName, args, ex);
                if (m == null) {
                    for (CtType<?> enc = st.getDeclaringType(); enc != null && m == null; enc = enc.getDeclaringType()) {
                        m = findMethod(enc, rawName, args, ex);
                    }
                }
                if (m != null && m.getBody() != null) {
                    return callMethod(m, m.isStatic() ? null : f.thiz, m.getDeclaringType(), args);
                }
                if (st.isEnum() && (name.equals("values") || name.equals("valueOf"))) {
                    return enumStatic(st, name, args);
                }
            }
            return builtin(owner == null ? tOwner : owner, name, null, args, inv);
        }
        Object target;
        boolean superCall = t instanceof CtSuperAccess<?>;
        if (t == null || t instanceof CtThisAccess<?> && ((CtThisAccess<?>) t).isImplicit()) {
            target = f.thiz;
            if (target instanceof Obj o) {
                CtMethod<?> m = findMethod(o.type, rawName, args, ex);
                if (m == null) {
                    CtMethod<?> om = findMethodInOuter(o, rawName, args, ex);
                    if (om != null) {
                        return om.isStatic() ? callMethod(om, null, om.getDeclaringType(), args)
                                : callMethod(om, outerFor(o, om.getDeclaringType()), om.getDeclaringType(), args);
                    }
                }
            } else if (f.cls != null) {
                CtMethod<?> m = findMethod(f.cls, rawName, args, ex);
                if (m != null && m.isStatic()) {
                    return callMethod(m, null, m.getDeclaringType(), args);
                }
            }
        } else {
            target = eval(t, f);
        }
        if (target instanceof Obj o) {
            CtType<?> start = o.type;
            if (superCall) {
                start = f.cls == null ? null : superType(f.cls);
                if (start == null) {
                    return vanillaCallOnObj(o, f.cls == null ? owner : vanillaSuperOf(f.cls), name, args, inv);
                }
            }
            CtMethod<?> m = findMethod(start, rawName, args, ex);
            if (m != null && m.getBody() != null) {
                return callMethod(m, o, m.getDeclaringType(), args);
            }
            if (o.enumName != null) {
                Object r = enumInstanceCall(o, name, args);
                if (r != Builtins.NOT_HANDLED) {
                    return r;
                }
            }
            return vanillaCallOnObj(o, owner, name, args, inv);
        }
        if (target instanceof Fn fn && !name.equals("andThen") && !name.equals("compose")) {
            return callFn(fn, args);
        }
        if (target instanceof Unknown && owner != null) {
            // default methods of a source interface run even on an unknown receiver; their abstract calls are
            // what pattern packs intercept
            CtType<?> ot = src.type(owner);
            if (ot != null && ot.isInterface()) {
                CtMethod<?> m = findMethod(ot, rawName, args, ex);
                if (m != null && m.getBody() != null && !m.isStatic()) {
                    return callMethod(m, target, ot, args);
                }
            }
        }
        return builtin(owner, name, target, args, inv);
    }

    private String vanillaSuperOf(CtType<?> cls) {
        CtType<?> cur = cls;
        while (cur != null) {
            CtTypeReference<?> s = cur.getSuperclass();
            if (s == null) {
                return null;
            }
            CtType<?> st = src.type(s.getQualifiedName());
            if (st == null) {
                return s.getQualifiedName();
            }
            cur = st;
        }
        return null;
    }

    private Object vanillaCallOnObj(Obj o, String owner, String name, List<Object> args, CtInvocation<?> inv) {
        String vowner = o.vanillaSuper != null ? o.vanillaSuper : owner;
        o.vanillaCalls.add(new V.Call(name, List.copyOf(args)));
        Object r = builtinOrNot(vowner, name, o, args, inv);
        if (r == Builtins.NOT_HANDLED && owner != null && !owner.equals(vowner)) {
            r = builtinOrNot(owner, name, o, args, inv);
        }
        if (r != Builtins.NOT_HANDLED) {
            return r;
        }
        return new Unknown("vanilla method " + shortName(vowner) + "#" + name);
    }

    private CtMethod<?> findMethodInOuter(Obj o, String name, List<Object> args, CtExecutableReference<?> ex) {
        for (CtType<?> enc = o.type.getDeclaringType(); enc != null; enc = enc.getDeclaringType()) {
            CtMethod<?> m = findMethod(enc, name, args, ex);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private Object outerFor(Obj o, CtType<?> type) {
        Object cur = o.outer;
        while (cur instanceof Obj oo) {
            if (isSameOrSub(oo.type, type.getQualifiedName())) {
                return oo;
            }
            cur = oo.outer;
        }
        return new Unknown("no enclosing instance");
    }

    private Object enumStatic(CtType<?> st, String name, List<Object> args) {
        classInit(st);
        List<Object> vals = new ArrayList<>();
        if (st instanceof CtEnum<?> en) {
            for (CtEnumValue<?> ev : en.getEnumValues()) {
                vals.add(statics.get(st.getQualifiedName() + "#" + ev.getSimpleName()));
            }
        }
        if (name.equals("values")) {
            return new Seq("array", vals);
        }
        if (args.size() == 1 && args.get(0) instanceof String s) {
            Object v = statics.get(st.getQualifiedName() + "#" + s);
            return v == null ? new Unknown("no enum constant " + s) : v;
        }
        return new Unknown("valueOf(" + describe(args.isEmpty() ? null : args.get(0)) + ")");
    }

    private Object enumInstanceCall(Obj o, String name, List<Object> args) {
        return switch (name) {
            case "name", "toString" -> args.isEmpty() ? (Object) o.enumName : Builtins.NOT_HANDLED;
            case "ordinal" -> o.ordinal;
            case "equals" -> args.size() == 1 ? (Object) (args.get(0) == o) : Builtins.NOT_HANDLED;
            case "hashCode" -> o.ordinal;
            default -> Builtins.NOT_HANDLED;
        };
    }

    /** Invokes a method by name on any value (source dispatch, then builtins). */
    public Object invokeAny(Object target, String name, List<Object> args, CtInvocation<?> site) {
        if (target instanceof Obj o) {
            return invokeVirtual(o, name, args, site);
        }
        if (target instanceof Fn fn) {
            return callFn(fn, args);
        }
        return builtin(null, name, target, args, site);
    }

    /** Most specific source method with the given official name and arity (class chain first), or null. */
    public CtMethod<?> sourceMethod(CtType<?> t, String officialName, int argc) {
        for (CtMethod<?> m : allMethods(t)) {
            if (m.getBody() != null && vi.official(m.getSimpleName()).equals(officialName) && arity(m, argc)) {
                return m;
            }
        }
        return null;
    }

    /** Calls a source method by official name if the object's class defines one; null if it does not. */
    public Object callIfDefined(Obj o, String officialName, List<Object> args) {
        CtMethod<?> m = sourceMethod(o.type, officialName, args.size());
        return m == null ? null : topLevel(() -> callMethod(m, o, m.getDeclaringType(), args));
    }

    public Object invokeVirtual(Obj o, String name, List<Object> args, CtInvocation<?> site) {
        CtMethod<?> m = findMethod(o.type, name, args, null);
        if (m == null) {
            // SRG names in source vs official requested name
            for (CtMethod<?> cand : allMethods(o.type)) {
                if (vi.official(cand.getSimpleName()).equals(name) && arity(cand, args.size())) {
                    m = cand;
                    break;
                }
            }
        }
        if (m != null && m.getBody() != null) {
            return callMethod(m, o, m.getDeclaringType(), args);
        }
        if (o.enumName != null) {
            Object r = enumInstanceCall(o, name, args);
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return vanillaCallOnObj(o, null, vi.official(name), args, site);
    }

    Object builtin(String owner, String name, Object target, List<Object> args, CtInvocation<?> site) {
        if (target == V.NULL) {
            return new Unknown("call of " + name + " on null");
        }
        Object r = builtinOrNot(owner, name, target, args, site);
        if (r != Builtins.NOT_HANDLED) {
            return r;
        }
        Unknown fromArgs = target instanceof Unknown u ? u : null;
        return new Unknown("unsupported call " + shortName(owner) + "#" + name
                + (fromArgs != null ? " on unknown receiver (" + fromArgs.reason() + ")" : ""));
    }

    private Object builtinOrNot(String owner, String name, Object target, List<Object> args, CtInvocation<?> site) {
        Builtins.Call call = new Builtins.Call(owner, name, target, args, site);
        for (Builtins b : builtins) {
            Object r = b.invoke(this, call);
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return Builtins.NOT_HANDLED;
    }

    public boolean isIntercept(String owner, String name) {
        for (Builtins b : builtins) {
            if (b.isIntercept(owner, name) || b.preempts(owner, name)) {
                return true;
            }
        }
        return false;
    }

    private boolean preempted(String owner, String name) {
        if (owner == null) {
            return false;
        }
        for (Builtins b : builtins) {
            if (b.preempts(owner, name)) {
                return true;
            }
        }
        return false;
    }

    /** Calls a functional value with arguments. */
    public Object callFn(Fn fn, List<Object> args) {
        if (depth == 0) {
            return topLevel(() -> callFnInner(fn, args));
        }
        return callFnInner(fn, args);
    }

    private Object callFnInner(Fn fn, List<Object> args) {
        if (fn.lambda != null) {
            CtLambda<?> lam = fn.lambda;
            Frame f = new Frame(new Scope(fn.captured), fn.thiz, fn.cls);
            List<? extends CtParameter<?>> ps = lam.getParameters();
            for (int i = 0; i < ps.size(); i++) {
                f.scope.declare(ps.get(i).getSimpleName(), i < args.size() ? args.get(i) : new Unknown("missing arg"));
            }
            if (++depth > MAX_DEPTH) {
                depth--;
                return new Unknown("recursion depth");
            }
            try {
                if (lam.getExpression() != null) {
                    return eval(lam.getExpression(), f);
                }
                try {
                    exec(lam.getBody(), f);
                } catch (ReturnSig r) {
                    return r.value;
                }
                return V.NULL;
            } catch (Abort a) {
                return new Unknown(a.getMessage());
            } finally {
                depth--;
            }
        }
        CtExecutableReferenceExpression<?, ?> ref = fn.ref;
        CtExecutableReference<?> ex = ref.getExecutable();
        String raw = ex.getSimpleName();
        CtExpression<?> t = ref.getTarget();
        if (ex.isConstructor() || raw.equals("new")) {
            String type = t instanceof CtTypeAccess<?> ta ? qn(ta.getAccessedType())
                    : ex.getDeclaringType() == null ? null : qn(ex.getDeclaringType());
            if (type == null) {
                return new Unknown("constructor reference of unknown type");
            }
            if (t instanceof CtTypeAccess<?> ta && ta.getAccessedType() instanceof spoon.reflect.reference.CtArrayTypeReference<?>) {
                return new Unknown("array constructor reference");
            }
            CtType<?> st = src.type(type);
            if (st != null) {
                return construct(st, args, null, null, fn.thiz);
            }
            return builtinConstruct(type, args, ref);
        }
        String name = vi.official(raw);
        if (fn.receiver != null) {
            if (fn.receiver instanceof Obj o) {
                return invokeVirtual(o, raw, args, null);
            }
            return invokeAny(fn.receiver, name, args, null);
        }
        String owner = t instanceof CtTypeAccess<?> ta ? qn(ta.getAccessedType())
                : ex.getDeclaringType() == null ? null : qn(ex.getDeclaringType());
        CtType<?> st = owner == null ? null : src.type(owner);
        if (st != null) {
            CtMethod<?> sm = findMethod(st, raw, args, null);
            if (sm != null && sm.isStatic()) {
                return callMethod(sm, null, sm.getDeclaringType(), args);
            }
            if (!args.isEmpty()) {
                CtMethod<?> im = findMethod(st, raw, args.subList(1, args.size()), null);
                if (im != null && !im.isStatic()) {
                    return invokeAny(args.get(0), raw, args.subList(1, args.size()), null);
                }
            }
        }
        Object r = builtinOrNot(owner, name, null, args, null);
        if (r != Builtins.NOT_HANDLED) {
            return r;
        }
        if (!args.isEmpty()) {
            return invokeAny(args.get(0), name, args.subList(1, args.size()), null);
        }
        return new Unknown("method reference " + shortName(owner) + "::" + name);
    }

    Object callMethod(CtExecutable<?> m, Object thiz, CtType<?> cls, List<Object> args) {
        if (m.getBody() == null) {
            return new Unknown("abstract method " + m.getSimpleName());
        }
        if (++depth > MAX_DEPTH) {
            depth--;
            return new Unknown("recursion depth");
        }
        callStack.push(m);
        try {
            Frame f = new Frame(new Scope(null), thiz, cls);
            bindParams(m, args, f.scope);
            try {
                execBlock(m.getBody().getStatements(), f);
            } catch (ReturnSig r) {
                Object v = r.value;
                return m instanceof CtMethod<?> mm ? coerce(v, mm.getType()) : v;
            } catch (BreakSig | ContinueSig b) {
                return new Unknown("stray break/continue");
            }
            return V.NULL;
        } catch (Abort a) {
            return new Unknown(a.getMessage());
        } finally {
            depth--;
            callStack.pop();
        }
    }

    /** Innermost source methods/constructors being run ("Cls#m@line"), for diagnostics. */
    public String callerContext(int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (CtElement e : callStack) {
            if (i++ >= n) {
                break;
            }
            CtType<?> t = e instanceof CtType<?> ct ? ct : e.getParent(CtType.class);
            sb.append(i == 1 ? "" : " < ").append(t == null ? "?" : t.getSimpleName()).append('#')
                    .append(e instanceof CtExecutable<?> ex ? ex.getSimpleName() : "<init>");
        }
        return sb.toString();
    }

    private void bindParams(CtExecutable<?> m, List<Object> args, Scope scope) {
        List<CtParameter<?>> ps = m.getParameters();
        for (int i = 0; i < ps.size(); i++) {
            CtParameter<?> p = ps.get(i);
            if (p.isVarArgs() && i == ps.size() - 1) {
                if (args.size() == ps.size() && args.get(i) instanceof Seq s && s.kind.equals("array")) {
                    scope.declare(p.getSimpleName(), s);
                } else {
                    List<Object> rest = new ArrayList<>(i < args.size() ? args.subList(i, args.size()) : List.of());
                    scope.declare(p.getSimpleName(), new Seq("array", rest));
                }
            } else {
                scope.declare(p.getSimpleName(), i < args.size() ? coerce(args.get(i), p.getType())
                        : new Unknown("missing argument " + p.getSimpleName()));
            }
        }
    }

    // ---- method lookup -------------------------------------------------------------------------------------

    List<CtMethod<?>> allMethods(CtType<?> t) {
        List<CtMethod<?>> out = new ArrayList<>();
        Set<CtType<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectMethods(t, out, seen);
        return out;
    }

    private void collectMethods(CtType<?> t, List<CtMethod<?>> out, Set<CtType<?>> seen) {
        if (t == null || !seen.add(t)) {
            return;
        }
        out.addAll(t.getMethods());
        collectMethods(superType(t), out, seen);
        for (CtTypeReference<?> i : t.getSuperInterfaces()) {
            collectMethods(src.type(i.getQualifiedName()), out, seen);
        }
    }

    private static boolean arity(CtMethod<?> m, int n) {
        int p = m.getParameters().size();
        if (p == n) {
            return true;
        }
        return p > 0 && m.getParameters().get(p - 1).isVarArgs() && n >= p - 1;
    }

    /**
     * Most specific method named {@code name} for the arguments, searching t's class chain first (overrides win),
     * then interfaces (default methods). Abstract declarations are returned only if nothing concrete exists.
     */
    public CtMethod<?> findMethod(CtType<?> t, String name, List<Object> args, CtExecutableReference<?> ref) {
        if (t == null) {
            return null;
        }
        CtMethod<?> abstractHit = null;
        Set<CtType<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<CtType<?>> order = new ArrayList<>();
        for (CtType<?> cur = t; cur != null && seen.add(cur); cur = superType(cur)) {
            order.add(cur);
        }
        List<CtType<?>> ifaces = new ArrayList<>();
        for (CtType<?> c : order) {
            collectInterfaces(c, ifaces, seen);
        }
        order.addAll(ifaces);
        for (CtType<?> cur : order) {
            List<CtMethod<?>> cands = methodsNamed(cur, name);
            CtMethod<?> best = pick(cands, args, ref);
            if (best != null) {
                if (best.getBody() != null) {
                    return best;
                }
                if (abstractHit == null) {
                    abstractHit = best;
                }
            }
        }
        return abstractHit;
    }

    private void collectInterfaces(CtType<?> t, List<CtType<?>> out, Set<CtType<?>> seen) {
        for (CtTypeReference<?> i : t.getSuperInterfaces()) {
            CtType<?> it = src.type(i.getQualifiedName());
            if (it != null && seen.add(it)) {
                out.add(it);
                collectInterfaces(it, out, seen);
            }
        }
    }

    private List<CtMethod<?>> methodsNamed(CtType<?> t, String name) {
        return methodCache.computeIfAbsent(t, k -> {
            Map<String, List<CtMethod<?>>> m = new HashMap<>();
            for (CtMethod<?> me : k.getMethods()) {
                m.computeIfAbsent(me.getSimpleName(), x -> new ArrayList<>()).add(me);
            }
            return m;
        }).getOrDefault(name, List.of());
    }

    private CtMethod<?> pick(List<CtMethod<?>> cands, List<Object> args, CtExecutableReference<?> ref) {
        CtMethod<?> fallback = null;
        int fallbackScore = Integer.MIN_VALUE;
        for (CtMethod<?> m : cands) {
            if (!arity(m, args.size())) {
                continue;
            }
            int score = m.getParameters().size() == args.size() ? 1 : 0;
            if (ref != null && ref.getParameters().size() == m.getParameters().size()) {
                boolean all = true;
                for (int i = 0; i < m.getParameters().size(); i++) {
                    String a = erasure(ref.getParameters().get(i));
                    String b = erasure(m.getParameters().get(i).getType());
                    if (a == null || b == null || !a.equals(b)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    score += 2;
                }
            }
            score += compat(m, args);
            if (score > fallbackScore) {
                fallback = m;
                fallbackScore = score;
            }
        }
        return fallback;
    }

    private static String erasure(CtTypeReference<?> t) {
        if (t == null) {
            return null;
        }
        String s = t.getQualifiedName();
        int lt = s.indexOf('<');
        s = lt < 0 ? s : s.substring(0, lt);
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** Rough argument/parameter compatibility for overloads that differ by type (int vs Block vs BlockState). */
    private int compat(CtMethod<?> m, List<Object> args) {
        int score = 0;
        List<CtParameter<?>> ps = m.getParameters();
        boolean va = !ps.isEmpty() && ps.get(ps.size() - 1).isVarArgs();
        int n = va ? args.size() : Math.min(ps.size(), args.size());
        for (int i = 0; i < n; i++) {
            CtParameter<?> param = ps.get(Math.min(i, ps.size() - 1));
            String pt = erasure(param.getType());
            Object a = args.get(i);
            if (pt == null || a instanceof Unknown) {
                continue;
            }
            if (param.isVarArgs() && pt.endsWith("[]") && !(a instanceof Seq)) {
                pt = pt.substring(0, pt.length() - 2);
            }
            if (a instanceof V.VObj vo) {
                String simple = vo.type.substring(Math.max(vo.type.lastIndexOf('.'), vo.type.lastIndexOf('$')) + 1);
                score += simple.equals(pt) ? 3 : pt.equals("Object") ? 0 : -2;
                continue;
            }
            if (a instanceof V.RegRef rr) {
                score += rr.registry().equals("item") && (pt.equals("Item") || pt.equals("ItemLike"))
                        || rr.registry().equals("block") && (pt.equals("Block") || pt.equals("ItemLike"))
                        || rr.registry().equals("fluid") && pt.equals("Fluid") ? 3
                        : pt.equals("String") || pt.equals("int") ? -5 : 0;
                continue;
            }
            if (a instanceof V.TagRef) {
                score += pt.equals("TagKey") ? 3 : pt.equals("Object") ? 0 : -2;
                continue;
            }
            if (a instanceof V.Holder) {
                score += pt.equals("RegistryObject") || pt.equals("Supplier") ? 3 : pt.equals("Object") ? 0 : -2;
                continue;
            }
            boolean prim = pt.equals("int") || pt.equals("long") || pt.equals("short") || pt.equals("byte")
                    || pt.equals("float") || pt.equals("double") || pt.equals("char") || pt.equals("Integer");
            if (a instanceof Number || a instanceof Character) {
                score += prim ? 2 : -3;
            } else if (a instanceof Boolean) {
                score += pt.equals("boolean") || pt.equals("Boolean") ? 2 : -3;
            } else if (a instanceof String) {
                score += pt.equals("String") || pt.equals("Object") || pt.equals("CharSequence") ? 2 : -3;
            } else if (a instanceof V.State) {
                score += pt.equals("BlockState") ? 3 : pt.equals("Block") ? -3 : 0;
            } else if (a instanceof V.Pos || a instanceof V.MutPos) {
                score += pt.contains("BlockPos") || pt.equals("Vec3i") ? 3 : prim ? -3 : 0;
            } else if (a instanceof V.Stack) {
                score += pt.equals("ItemStack") ? 3 : 0;
            } else if (a instanceof Obj o) {
                String fpt = pt;
                score += prim ? -3 : isSameOrSub(o.type, bin(param.getType().getQualifiedName())) ? 3
                        : supertypeNames(o.type).stream().anyMatch(sn -> sn.endsWith("." + fpt) || sn.endsWith("$" + fpt))
                                ? 2 : 0;
            } else if (a instanceof Seq s) {
                score += param.getType() instanceof spoon.reflect.reference.CtArrayTypeReference<?> ? 2
                        : prim ? -3 : 0;
            } else if (prim) {
                score -= 3;
            }
        }
        return score;
    }

    // ---- object construction -------------------------------------------------------------------------------

    private Object evalNew(CtConstructorCall<?> cc, Frame f) {
        reached.add(cc);
        List<Object> args = new ArrayList<>();
        for (CtExpression<?> a : cc.getArguments()) {
            args.add(eval(a, f));
        }
        if (cc instanceof CtNewClass<?> nc && nc.getAnonymousClass() != null) {
            return sited(construct(nc.getAnonymousClass(), args, nc, f.scope, f.thiz), cc);
        }
        String type = bin(cc.getType().getQualifiedName());
        CtType<?> st = src.type(type);
        if (st != null) {
            try {
                if (cc.getExecutable().getExecutableDeclaration() instanceof CtConstructor<?> decl
                        && decl.getDeclaringType() == st) {
                    nextCtor = decl;
                }
            } catch (RuntimeException e) {
                nextCtor = null;
            }
            return sited(construct(st, args, null, null, f.thiz), cc);
        }
        return builtinConstruct(type, args, cc);
    }

    private static Object sited(Object o, CtElement site) {
        if (o instanceof Obj ob && ob.site == null) {
            ob.site = site;
        }
        return o;
    }

    public Object builtinConstruct(String type, List<Object> args, CtElement site) {
        int lt = type.indexOf('<');
        if (lt >= 0) {
            type = type.substring(0, lt);
        }
        for (Builtins b : builtins) {
            Object r = b.construct(this, type, args, site);
            if (r != Builtins.NOT_HANDLED) {
                return r;
            }
        }
        return new V.VObj(type, null, args);
    }

    Object construct(CtType<?> type, List<Object> args, CtNewClass<?> anon, Scope captured, Object creator) {
        if (!(type instanceof CtClass<?> cls)) {
            return new Unknown("cannot instantiate " + type.getQualifiedName());
        }
        if (cls.isAbstract() && anon == null) {
            return new Unknown("abstract class " + type.getSimpleName());
        }
        if (++depth > MAX_DEPTH) {
            depth--;
            return new Unknown("recursion depth");
        }
        try {
            Obj o = new Obj(cls);
            o.captured = captured;
            if (creator instanceof Obj c && (anon != null || !cls.isStatic() && cls.getDeclaringType() != null)) {
                o.outer = c;
            }
            initFieldDefaults(o, cls);
            if (anon != null) {
                // anonymous class: arguments go to the super constructor
                CtTypeReference<?> sup = cls.getSuperclass();
                CtType<?> st = sup == null ? null : src.type(sup.getQualifiedName());
                if (st instanceof CtClass<?> sc) {
                    initObject(o, sc, args, null);
                } else if (sup != null) {
                    o.vanillaSuper = sup.getQualifiedName();
                    o.superArgs = List.copyOf(args);
                } else if (!cls.getSuperInterfaces().isEmpty()) {
                    o.vanillaSuper = cls.getSuperInterfaces().iterator().next().getQualifiedName();
                }
                runFieldInits(o, cls);
                return o;
            }
            CtConstructor<?> chosen = nextCtor != null && nextCtor.getDeclaringType() == cls ? nextCtor : null;
            nextCtor = null;
            initObject(o, cls, args, chosen);
            return o;
        } catch (Abort a) {
            return new Unknown("constructor of " + type.getSimpleName() + ": " + a.getMessage());
        } finally {
            depth--;
        }
    }

    private void initFieldDefaults(Obj o, CtType<?> t) {
        for (CtType<?> cur = t; cur != null; cur = superType(cur)) {
            for (CtField<?> fd : cur.getFields()) {
                if (!fd.isStatic()) {
                    o.fields.putIfAbsent(fd.getSimpleName(), defaultFor(fd.getType()));
                }
            }
        }
    }

    /** Runs one constructor of cls (and its super chain) on o. */
    void initObject(Obj o, CtClass<?> cls, List<Object> args, Object chosen) {
        if (++depth > MAX_DEPTH) {
            depth--;
            throw new Abort("constructor recursion depth");
        }
        callStack.push(cls);
        try {
            initObjectInner(o, cls, args, chosen instanceof CtConstructor<?> c ? c : null);
        } finally {
            depth--;
            callStack.pop();
        }
    }

    private void initObjectInner(Obj o, CtClass<?> cls, List<Object> args, CtConstructor<?> chosen) {
        CtConstructor<?> ctor = pickCtor(cls, args);
        if (chosen != null && arityOk(chosen, args.size()) && (ctor == null || ctorScore(chosen, args) >= ctorScore(ctor, args))) {
            ctor = chosen;
        }
        if (ctor == null && cls.getConstructors().stream().anyMatch(c -> !c.isImplicit())) {
            throw new Abort("no constructor of " + cls.getSimpleName() + " takes " + args.size() + " arguments");
        }
        Frame f = new Frame(new Scope(o.captured), o, cls);
        List<CtStatement> body = List.of();
        if (ctor != null) {
            bindParams(ctor, args, f.scope);
            body = ctor.getBody() == null ? List.of() : ctor.getBody().getStatements();
        }
        int start = 0;
        CtInvocation<?> first = body.isEmpty() || !(body.get(0) instanceof CtInvocation<?> fi)
                || !fi.getExecutable().isConstructor() ? null : (CtInvocation<?>) body.get(0);
        boolean delegatesThis = false;
        if (first != null) {
            start = 1;
            List<Object> sargs = new ArrayList<>();
            for (CtExpression<?> a : first.getArguments()) {
                sargs.add(eval(a, f));
            }
            if (isThisCall(first, cls)) {
                delegatesThis = true;
                CtConstructor<?> target = null;
                try {
                    if (first.getExecutable().getExecutableDeclaration() instanceof CtConstructor<?> c
                            && c.getDeclaringType() == cls) {
                        target = c;
                    }
                } catch (RuntimeException e) {
                    target = null;
                }
                if (target == ctor || target != null && !arityOk(target, sargs.size())) {
                    target = pickCtor(cls, sargs, ctor);
                    if (target == null) {
                        throw new Abort("constructor delegates to itself");
                    }
                }
                if (target == null) {
                    target = pickCtor(cls, sargs, ctor);
                }
                initObject(o, cls, sargs, target);
            } else {
                superInit(o, cls, sargs);
            }
        } else {
            superInit(o, cls, List.of());
        }
        if (!delegatesThis) {
            runFieldInits(o, cls);
        }
        for (int i = start; i < body.size(); i++) {
            exec(body.get(i), f);
        }
    }

    private static boolean arityOk(CtConstructor<?> c, int n) {
        int p = c.getParameters().size();
        return p == n || p > 0 && c.getParameters().get(p - 1).isVarArgs() && n >= p - 1;
    }

    /** this(...) rather than super(...): Spoon gives both an implicit this target, so look at the source form. */
    private boolean isThisCall(CtInvocation<?> call, CtClass<?> cls) {
        if (call.getTarget() instanceof CtSuperAccess<?> || call.isImplicit() || call.getPosition() == null
                || !call.getPosition().isValidPosition()) {
            return false; // Spoon inserts implicit super() calls without a position
        }
        String text = "";
        try {
            var pos = call.getPosition();
            String code = pos.getCompilationUnit().getOriginalSourceCode();
            int st = pos.getSourceStart();
            text = code.substring(st, Math.min(code.length(), st + 16)).stripLeading();
        } catch (RuntimeException e) {
            text = "";
        }
        if (text.startsWith("super")) {
            return false;
        }
        if (text.startsWith("this")) {
            return true;
        }
        CtTypeReference<?> dt = call.getExecutable().getDeclaringType();
        return dt != null && bin(dt.getQualifiedName()).equals(cls.getQualifiedName());
    }

    private void superInit(Obj o, CtClass<?> cls, List<Object> sargs) {
        CtTypeReference<?> sup = cls.getSuperclass();
        if (cls.isEnum()) {
            return;
        }
        CtType<?> st = sup == null ? null : src.type(sup.getQualifiedName());
        if (st instanceof CtClass<?> sc) {
            initObject(o, sc, sargs, null);
        } else if (sup != null && o.vanillaSuper == null) {
            o.vanillaSuper = sup.getQualifiedName();
            o.superArgs = List.copyOf(sargs);
        }
    }

    private void runFieldInits(Obj o, CtType<?> cls) {
        Frame f = new Frame(new Scope(o.captured), o, cls);
        for (CtTypeMember m : cls.getTypeMembers()) {
            if (m instanceof CtField<?> fd && !fd.isStatic() && fd.getDefaultExpression() != null) {
                o.fields.put(fd.getSimpleName(), coerce(eval(fd.getDefaultExpression(), f), fd.getType()));
            } else if (m instanceof CtAnonymousExecutable ae && !ae.isStatic()) {
                exec(ae.getBody(), f);
            }
        }
    }

    private CtConstructor<?> pickCtor(CtClass<?> cls, List<Object> args) {
        return pickCtor(cls, args, null);
    }

    private CtConstructor<?> pickCtor(CtClass<?> cls, List<Object> args, CtConstructor<?> exclude) {
        CtConstructor<?> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (CtConstructor<?> c : cls.getConstructors()) {
            if (c.isImplicit() && cls.getConstructors().size() > 1 || c == exclude) {
                continue;
            }
            int score = ctorScore(c, args);
            if (score > bestScore) {
                best = c;
                bestScore = score;
            }
        }
        return best == null || bestScore == Integer.MIN_VALUE ? null : best;
    }

    /** How well arguments fit a constructor's parameters; MIN_VALUE if the arity does not fit. */
    private int ctorScore(CtConstructor<?> c, List<Object> args) {
        int p = c.getParameters().size();
        boolean va = !c.getParameters().isEmpty() && c.getParameters().get(p - 1).isVarArgs();
        if (p != args.size() && !(va && args.size() >= p - 1)) {
            return Integer.MIN_VALUE;
        }
        int score = p == args.size() ? 1 : 0;
        for (int i = 0; i < Math.min(p, args.size()); i++) {
            String pt = erasure(c.getParameters().get(i).getType());
            Object a = args.get(i);
            if (pt == null) {
                continue;
            }
            boolean prim = pt.equals("int") || pt.equals("long") || pt.equals("float") || pt.equals("double")
                    || pt.equals("short") || pt.equals("byte") || pt.equals("char");
            boolean str = pt.equals("String");
            boolean wire = pt.startsWith("Json") || pt.equals("FriendlyByteBuf") || pt.equals("CompoundTag");
            if (wire && !(a instanceof V.VObj wv && wv.type.endsWith(pt)) && a != V.NULL) {
                score -= 5; // deserialisation constructors never take interpreter-built values
                continue;
            }
            if (a instanceof V.RegRef rr) {
                score += rr.registry().equals("item") && (pt.equals("Item") || pt.equals("ItemLike"))
                        || rr.registry().equals("block") && (pt.equals("Block") || pt.equals("ItemLike"))
                        || rr.registry().equals("fluid") && pt.equals("Fluid") ? 3 : prim || str ? -5 : 0;
                continue;
            }
            if (a instanceof Number) {
                score += prim || pt.equals("Integer") ? 2 : -3;
            } else if (a instanceof String) {
                score += str ? 2 : -3;
            } else if (a instanceof Boolean) {
                score += pt.equals("boolean") ? 2 : -3;
            } else if (a instanceof Obj ob) {
                score += prim || str ? -3 : isSameOrSub(ob.type, bin(c.getParameters().get(i).getType().getQualifiedName()))
                        ? 3 : 0;
            } else if (a instanceof Seq) {
                score += c.getParameters().get(i).getType() instanceof spoon.reflect.reference.CtArrayTypeReference<?>
                        || pt.contains("List") || pt.contains("Collection") || pt.contains("Set")
                        || pt.equals("Iterable") ? 3 : pt.startsWith("Json") || str || prim ? -5 : 0;
            } else if (a instanceof V.VObj vo) {
                String simple = vo.type.substring(Math.max(vo.type.lastIndexOf('.'), vo.type.lastIndexOf('$')) + 1);
                score += simple.equals(pt) ? 3 : pt.startsWith("Json") || prim || str ? -5 : 0;
            } else if (a instanceof V.Stack) {
                score += pt.equals("ItemStack") ? 3 : pt.startsWith("Json") || prim || str ? -5 : 0;
            } else if (a instanceof V.ResLoc) {
                score += pt.equals("ResourceLocation") ? 3 : prim || str ? -5 : 0;
            } else if (a instanceof V.State || a instanceof V.Sym || a instanceof V.RegRef || a instanceof V.TagRef) {
                score += prim || str ? -5 : 0;
            } else if (a == V.NULL) {
                score += prim ? -3 : 0;
            }
        }
        return score;
    }

    // ---- conversions ---------------------------------------------------------------------------------------

    public static Object defaultFor(CtTypeReference<?> t) {
        if (t == null) {
            return V.NULL;
        }
        return switch (t.getQualifiedName()) {
            case "int", "short", "byte" -> 0;
            case "long" -> 0L;
            case "float" -> 0f;
            case "double" -> 0d;
            case "boolean" -> false;
            case "char" -> '\0';
            default -> V.NULL;
        };
    }

    private static Object coerce(Object v, CtTypeReference<?> t) {
        if (t == null || !(v instanceof Number || v instanceof Character)) {
            return v;
        }
        return switch (t.getQualifiedName()) {
            case "int", "java.lang.Integer" -> toInt(v);
            case "long", "java.lang.Long" -> v instanceof Character c ? (long) c : ((Number) v).longValue();
            case "float", "java.lang.Float" -> v instanceof Character c ? (float) c : ((Number) v).floatValue();
            case "double", "java.lang.Double" -> v instanceof Character c ? (double) c : ((Number) v).doubleValue();
            case "short" -> (int) (short) toInt(v);
            case "byte" -> (int) (byte) toInt(v);
            case "char" -> v instanceof Character ? v : (char) ((Number) v).intValue();
            default -> v;
        };
    }

    private static int toInt(Object v) {
        return v instanceof Character c ? c : ((Number) v).intValue();
    }

    private static Object coerceLike(Object v, Object like) {
        if (!(v instanceof Number n)) {
            return v;
        }
        if (like instanceof Integer) {
            return n.intValue();
        }
        if (like instanceof Long) {
            return n.longValue();
        }
        if (like instanceof Float) {
            return n.floatValue();
        }
        if (like instanceof Double) {
            return n.doubleValue();
        }
        if (like instanceof Character) {
            return (char) n.intValue();
        }
        return v;
    }

    private static Object cast(Object v, CtTypeReference<?> t) {
        if (v instanceof Unknown) {
            return v;
        }
        String q = t.getQualifiedName();
        if (v instanceof Number || v instanceof Character) {
            return switch (q) {
                case "int" -> toInt(v instanceof Double d ? (Object) (int) (double) d
                        : v instanceof Float fl ? (Object) (int) (float) fl : v);
                case "long" -> v instanceof Character c ? (long) c : ((Number) v).longValue();
                case "float" -> v instanceof Character c ? (float) c : ((Number) v).floatValue();
                case "double" -> v instanceof Character c ? (double) c : ((Number) v).doubleValue();
                case "short" -> (int) (short) toInt(v instanceof Number n && !(v instanceof Integer) ? n.intValue() : v);
                case "byte" -> (int) (byte) toInt(v instanceof Number n && !(v instanceof Integer) ? n.intValue() : v);
                case "char" -> v instanceof Character ? v : (char) ((Number) v).intValue();
                default -> v;
            };
        }
        return v;
    }

    public static Number num(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        if (v instanceof Character c) {
            return (int) c;
        }
        return null;
    }

    public String str(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Character || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v == V.NULL) {
            return "null";
        }
        if (v instanceof V.ResLoc rl) {
            return rl.toString();
        }
        if (v instanceof Obj o && o.enumName != null) {
            Object r = invokeVirtual(o, "toString", List.of(), null);
            return r instanceof String s ? s : null;
        }
        return null;
    }

    // ---- diagnostics ---------------------------------------------------------------------------------------

    public static String describe(Object v) {
        if (v == null) {
            return "nothing";
        }
        if (v instanceof Unknown u) {
            return "unknown(" + u.reason() + ")";
        }
        String s = v.toString();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    static String reasonOf(Object v, CtElement at) {
        if (v instanceof Unknown u) {
            return u.reason();
        }
        return "non-boolean " + describe(v) + " at " + where(at);
    }

    public static String where(CtElement e) {
        if (e == null || e.getPosition() == null || !e.getPosition().isValidPosition()) {
            return "?";
        }
        return e.getPosition().getFile().getName() + ":" + e.getPosition().getLine();
    }

    public static String snippet(CtElement e) {
        if (e == null) {
            return "";
        }
        String s;
        try {
            s = e.toString();
        } catch (RuntimeException ex) {
            s = e.getClass().getSimpleName();
        }
        s = s.replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    static String shortName(String q) {
        if (q == null) {
            return "?";
        }
        return q.substring(q.lastIndexOf('.') + 1);
    }

    /** True if m is static (methods and fields). */
    static boolean isStatic(CtModifiable m) {
        return m.hasModifier(ModifierKind.STATIC);
    }

    /** Statics snapshot for debugging (sorted). */
    public Map<String, Object> staticsSorted() {
        return new java.util.TreeMap<>(statics);
    }

    @SuppressWarnings("unused")
    private static <K, W> Map<K, W> linked() {
        return new LinkedHashMap<>();
    }
}
