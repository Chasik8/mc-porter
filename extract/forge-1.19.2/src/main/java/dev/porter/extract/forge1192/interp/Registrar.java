package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.model.SourceModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Finds and runs the mod's registration code. Intercept sites are the calls that {@link Builtins#isIntercept}
 * claims (registry {@code register}, network registration, pack-defined calls). Methods that (transitively, through
 * direct calls) contain intercept sites are "relevant"; relevant methods that nobody calls directly — event
 * handlers, setup methods passed as method references, mod constructors — are the roots. Classes whose static
 * initialisers contain intercept sites (DeferredRegister holders) are roots too. Roots run once, in a stable order,
 * with both branches of undecidable conditions executed (facts are flagged as conditional).
 */
public final class Registrar {
    private final Interpreter in;
    private final SourceModel src;
    private final List<CtInvocation<?>> sites = new ArrayList<>();
    private final Set<CtExecutable<?>> relevant = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CtElement> roots = new ArrayList<>();
    private final Set<CtElement> done = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CtElement> running = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<CtExecutable<?>, List<CtElement>> rootsReaching = new IdentityHashMap<>();
    private final Map<String, List<CtExecutable<?>>> staticWriters = new HashMap<>();
    private final Map<CtExecutable<?>, List<CtExecutable<?>>> callees = new IdentityHashMap<>();
    private final List<String> log = new ArrayList<>();

    public Registrar(Interpreter in) {
        this.in = in;
        this.src = in.source();
    }

    public List<CtInvocation<?>> sites() {
        return sites;
    }

    public List<CtElement> roots() {
        return roots;
    }

    public List<String> log() {
        return log;
    }

    /** Official owner of an invocation (declaring type of the executable, else the static target type). */
    public String ownerOf(CtInvocation<?> inv) {
        CtExecutableReference<?> ex = inv.getExecutable();
        if (ex.getDeclaringType() != null) {
            return in.qn(ex.getDeclaringType());
        }
        if (inv.getTarget() instanceof CtTypeAccess<?> ta) {
            return in.qn(ta.getAccessedType());
        }
        if (inv.getTarget() != null && inv.getTarget().getType() != null) {
            return in.bin(inv.getTarget().getType().getQualifiedName());
        }
        return null;
    }

    public void analyse() {
        Map<CtExecutable<?>, List<CtExecutable<?>>> callers = new IdentityHashMap<>();
        Set<CtType<?>> staticRoots = new LinkedHashSet<>();
        for (CtType<?> t : src.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (inv.getParent(CtType.class) != t) {
                    continue; // nested types are visited on their own
                }
                CtExecutable<?> encl = enclosing(inv);
                String owner = ownerOf(inv);
                String name = in.vanilla().official(inv.getExecutable().getSimpleName());
                if (in.isIntercept(owner, name)) {
                    sites.add(inv);
                    if (encl != null) {
                        relevant.add(encl);
                    } else {
                        staticRoots.add(t);
                    }
                }
                CtExecutable<?> decl = declaration(inv);
                if (decl != null && encl != null) {
                    callers.computeIfAbsent(decl, k -> new ArrayList<>()).add(encl);
                    callees.computeIfAbsent(encl, k -> new ArrayList<>()).add(decl);
                }
            }
            for (CtFieldWrite<?> fw : t.getElements(new TypeFilter<>(CtFieldWrite.class))) {
                if (fw.getParent(CtType.class) != t) {
                    continue;
                }
                CtExecutable<?> encl = enclosing(fw);
                CtField<?> fd = fw.getVariable().getFieldDeclaration();
                if (encl != null && fd != null && fd.isStatic() && fd.getDeclaringType() != null) {
                    staticWriters.computeIfAbsent(fd.getDeclaringType().getQualifiedName() + "#" + fd.getSimpleName(),
                            k -> new ArrayList<>()).add(encl);
                }
            }
        }
        // propagate relevance to callers
        List<CtExecutable<?>> work = new ArrayList<>(relevant);
        while (!work.isEmpty()) {
            CtExecutable<?> m = work.remove(work.size() - 1);
            for (CtExecutable<?> c : callers.getOrDefault(m, List.of())) {
                if (relevant.add(c)) {
                    work.add(c);
                }
            }
        }
        // static initialisers that call relevant methods are roots as well
        for (CtType<?> t : src.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (inv.getParent(CtType.class) == t && enclosing(inv) == null) {
                    CtExecutable<?> decl = declaration(inv);
                    if (decl != null && relevant.contains(decl)) {
                        staticRoots.add(t);
                    }
                }
            }
        }
        List<CtExecutable<?>> methodRoots = new ArrayList<>();
        // calls Spoon could not bind to a declaration (overloads in noclasspath): name/arity seen at a call site
        Set<String> calledByName = new java.util.HashSet<>();
        for (CtType<?> t : src.types()) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                calledByName.add(inv.getExecutable().getSimpleName() + "/" + inv.getArguments().size());
            }
        }
        for (CtExecutable<?> m : relevant) {
            if (callers.containsKey(m)) {
                continue;
            }
            if (!m.getParameters().isEmpty() && m instanceof CtMethod<?>
                    && calledByName.contains(m.getSimpleName() + "/" + m.getParameters().size())) {
                continue; // a helper whose callers Spoon did not resolve: it runs when they call it
            }
            methodRoots.add(m);
        }
        methodRoots.sort(Comparator.comparing(Registrar::posKey));
        List<CtType<?>> typeRoots = new ArrayList<>(staticRoots);
        typeRoots.sort(Comparator.comparing(CtType::getQualifiedName));
        roots.addAll(typeRoots);
        roots.addAll(methodRoots);
        // which roots reach which methods (for the demand hook)
        for (CtElement r : roots) {
            if (r instanceof CtExecutable<?> ex) {
                reach(ex, r, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        }
        // roots that register registry entries run before the others (recipes, menus...), so that later roots see
        // the registered objects (stable sort: source order within each group)
        Set<CtExecutable<?>> registering = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CtInvocation<?> site : sites) {
            String n = site.getExecutable().getSimpleName();
            String o = ownerOf(site);
            if (n.equals("register") && o != null && (o.endsWith("IForgeRegistry") || o.endsWith("ForgeRegistry")
                    || o.endsWith("DeferredRegister") || o.endsWith("RegisterEvent"))) {
                CtExecutable<?> e = enclosing(site);
                if (e != null) {
                    registering.add(e);
                }
            }
        }
        Set<CtElement> early = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CtExecutable<?> m : registering) {
            early.addAll(rootsReaching.getOrDefault(m, List.of()));
        }
        List<CtElement> ordered = new ArrayList<>(typeRoots);
        for (CtExecutable<?> m : methodRoots) {
            if (early.contains(m)) {
                ordered.add(m);
            }
        }
        for (CtExecutable<?> m : methodRoots) {
            if (!early.contains(m)) {
                ordered.add(m);
            }
        }
        roots.clear();
        roots.addAll(ordered);
        in.setMissingStaticHook(this::onMissingStatic);
    }

    private void reach(CtExecutable<?> m, CtElement root, Set<CtExecutable<?>> seen) {
        if (!seen.add(m)) {
            return;
        }
        rootsReaching.computeIfAbsent(m, k -> new ArrayList<>()).add(root);
        for (CtExecutable<?> c : callees.getOrDefault(m, List.of())) {
            reach(c, root, seen);
        }
    }

    private static String posKey(CtElement e) {
        if (e.getPosition() == null || !e.getPosition().isValidPosition()) {
            return "~";
        }
        return e.getPosition().getFile().getPath().replace('\\', '/') + ":" + String.format("%08d",
                e.getPosition().getSourceStart());
    }

    /** The method/constructor containing e (lambdas count as part of their method); null in field initialisers. */
    static CtExecutable<?> enclosing(CtElement e) {
        CtElement p = e.getParent();
        while (p != null) {
            if (p instanceof CtMethod<?> || p instanceof CtConstructor<?>) {
                return (CtExecutable<?>) p;
            }
            if (p instanceof CtAnonymousExecutable || p instanceof CtField<?> || p instanceof CtType<?>) {
                return null;
            }
            p = p.getParent();
        }
        return null;
    }

    private CtExecutable<?> declaration(CtInvocation<?> inv) {
        try {
            CtExecutable<?> d = inv.getExecutable().getExecutableDeclaration();
            if (d instanceof CtMethod<?> || d instanceof CtConstructor<?>) {
                return d.getParent(CtType.class) != null && src.type(d.getParent(CtType.class).getQualifiedName()) != null
                        ? d : null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Runs all roots. */
    public void runAll(long budgetPerRoot) {
        boolean saved = in.bothBranches;
        in.bothBranches = true;
        try {
            for (CtElement r : roots) {
                run(r, budgetPerRoot);
            }
        } finally {
            in.bothBranches = saved;
            in.resetBudget(5_000_000L);
        }
    }

    private long budget = 20_000_000L;

    private void run(CtElement r, long budgetPerRoot) {
        if (done.contains(r) || running.contains(r)) {
            return;
        }
        budget = budgetPerRoot;
        if (running.isEmpty()) {
            in.resetBudget(budgetPerRoot);
        }
        running.add(r);
        String savedRoot = in.currentRoot;
        in.currentRoot = describe(r);
        try {
            if (r instanceof CtType<?> t) {
                in.classInit(t);
            } else if (r instanceof CtMethod<?> m) {
                List<Object> args = unknownArgs(m);
                Object thiz = null;
                if (!m.isStatic()) {
                    CtType<?> owner = m.getDeclaringType();
                    thiz = blankInstance(owner);
                }
                in.call(m, thiz, args);
            } else if (r instanceof CtConstructor<?> c) {
                in.newInstance(c.getDeclaringType(), unknownArgs(c));
            }
        } catch (Interpreter.BudgetExceeded e) {
            log.add("root " + describe(r) + ": step budget exceeded");
        } catch (RuntimeException | StackOverflowError e) {
            log.add("root " + describe(r) + ": " + e);
        } finally {
            in.currentRoot = savedRoot;
            running.remove(r);
            done.add(r);
        }
    }

    private Object blankInstance(CtType<?> owner) {
        if (owner instanceof CtClass<?> cls && !cls.isAbstract()) {
            V.Obj o = new V.Obj(cls);
            for (CtField<?> f : cls.getFields()) {
                if (!f.isStatic()) {
                    o.fields.put(f.getSimpleName(), new V.Unknown("field of root instance " + cls.getSimpleName()));
                }
            }
            return o;
        }
        return new V.Unknown("instance of " + owner.getSimpleName());
    }

    private static List<Object> unknownArgs(CtExecutable<?> m) {
        List<Object> args = new ArrayList<>();
        for (CtParameter<?> p : m.getParameters()) {
            args.add(new V.Unknown("parameter " + p.getSimpleName() + " of root " + m.getSimpleName()));
        }
        return args;
    }

    private void onMissingStatic(String key) {
        List<CtExecutable<?>> writers = staticWriters.get(key);
        if (writers == null || writers.isEmpty()) {
            return; // never assigned anywhere: the default value is the truth
        }
        boolean anyRoot = false;
        for (CtExecutable<?> w : writers) {
            for (CtElement r : rootsReaching.getOrDefault(w, List.of())) {
                anyRoot = true;
                if (!done.contains(r) && !running.contains(r)) {
                    boolean saved = in.bothBranches;
                    in.bothBranches = true;
                    try {
                        run(r, budget);
                    } finally {
                        in.bothBranches = saved;
                    }
                }
            }
        }
        if (!anyRoot) {
            in.putStatic(key, new V.Unknown("static " + key + " is only assigned by code that is never run"));
        }
    }

    static String describe(CtElement e) {
        if (e instanceof CtType<?> t) {
            return t.getQualifiedName() + ".<clinit>";
        }
        if (e instanceof CtExecutable<?> ex) {
            CtType<?> t = ex.getParent(CtType.class);
            return (t == null ? "?" : t.getQualifiedName()) + "#" + ex.getSimpleName();
        }
        return String.valueOf(e);
    }
}
