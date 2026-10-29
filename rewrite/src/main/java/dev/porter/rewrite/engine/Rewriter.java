package dev.porter.rewrite.engine;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.RuleBook.Prepared;
import dev.porter.rewrite.engine.TypeFacts.Tri;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtStatementList;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.cu.position.BodyHolderSourcePosition;
import spoon.reflect.cu.position.DeclarationSourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;
import spoon.support.reflect.CtExtendedModifier;

/**
 * The rule engine: builds a Spoon model of the mod sources, matches every call, constructor call, field access,
 * type reference and overriding method against the rules (type-aware, SRG-aware), rewrites matched code through
 * position-based text edits ({@link TextEdits}) and writes a {@code TODO(porter:<reason>)} line comment for every
 * case it does not rewrite: platform symbols without a rule, ambiguous matches, unprovable receiver types, failed
 * or unknown preconditions. Deterministic: same input, same rules, same output bytes.
 */
public final class Rewriter {

    /**
     * @param keepTargetMarks keep the {@link Template#TARGET_MARK} before {@code $target(...)} names in the output (the
     *        caller relocates and strips them); otherwise they are stripped here
     */
    public record Options(List<String> platformPrefixes, boolean shimEnabled, String shimPackage, boolean platformTodos,
            int complianceLevel, List<Path> classpath, boolean keepTargetMarks) {
        public Options(List<String> platformPrefixes, boolean shimEnabled, String shimPackage, boolean platformTodos,
                int complianceLevel, List<Path> classpath) {
            this(platformPrefixes, shimEnabled, shimPackage, platformTodos, complianceLevel, classpath, false);
        }

        public static Options defaults() {
            return new Options(List.of("net.minecraft.", "net.minecraftforge.", "com.mojang."), true,
                    RuleBook.SHIM_PACKAGE, true, 17, List.of());
        }

        public Options withPlatformTodos(boolean on) {
            return new Options(platformPrefixes, shimEnabled, shimPackage, on, complianceLevel, classpath, keepTargetMarks);
        }

        public Options withKeepTargetMarks(boolean on) {
            return new Options(platformPrefixes, shimEnabled, shimPackage, platformTodos, complianceLevel, classpath, on);
        }
    }

    /** One input file; {@code path} is relative with '/' separators. Non-target files only provide types. */
    public record SourceFile(String path, String text, boolean target) {
    }

    public record Todo(String file, int line, String reason, String detail) {
    }

    public record FileResult(String path, String text, Map<String, Integer> applied, List<Todo> todos, boolean changed) {
    }

    public record Result(List<FileResult> files, Set<String> shimClasses) {
    }

    private final RuleBook book;
    private final NameTable names;
    private final TypeFacts facts;
    private final Map<String, String> graphStatus;
    private final Options opts;

    /**
     * @param graphStatus symbol key ({@code owner#name}, or class name) -> non-mechanical graph status (removed,
     *        signature_changed, split, merged), used to explain TODOs
     */
    public Rewriter(List<Rule> rules, NameTable names, TypeFacts facts, Map<String, String> graphStatus, Options opts) {
        this.book = new RuleBook(rules, opts.shimPackage());
        this.names = names;
        this.facts = facts;
        this.graphStatus = graphStatus;
        this.opts = opts;
    }

    public RuleBook book() {
        return book;
    }

    // ------------------------------------------------------------------------------------------------ model

    private spoon.reflect.factory.Factory factory;
    private final Set<String> targetTypes = new HashSet<>();
    private final Set<String> alsoPorted = new HashSet<>();

    /**
     * Source files that are not rewritten in this run but are part of the port (rewritten in another run of it): their
     * types count as ported, like the rewritten ones (not as platform types without a rule).
     */
    public void alsoPorted(Set<String> sourcePaths) {
        alsoPorted.addAll(sourcePaths);
    }
    private final Map<String, TypeFacts.Supers> sourceSupers = new HashMap<>();
    private final Set<String> shimUsed = new TreeSet<>();

    public Result rewrite(List<SourceFile> files) {
        List<SourceFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(SourceFile::path));
        Launcher launcher = new Launcher();
        Environment env = launcher.getEnvironment();
        env.setNoClasspath(true);
        env.setComplianceLevel(opts.complianceLevel());
        env.setCommentEnabled(false);
        env.setAutoImports(false);
        env.setIgnoreDuplicateDeclarations(true);
        env.setShouldCompile(false);
        if (!opts.classpath().isEmpty()) {
            env.setSourceClasspath(opts.classpath().stream().map(Path::toString).toArray(String[]::new));
        }
        Map<String, SourceFile> byPath = new HashMap<>();
        for (SourceFile f : sorted) {
            launcher.addInputResource(new VirtualFile(f.text(), f.path()));
            byPath.put(f.path(), f);
        }
        CtModel model = launcher.buildModel();
        factory = launcher.getFactory();
        facts.setSourceLookup(this::sourceSupers);

        Map<SourceFile, CtCompilationUnit> units = new LinkedHashMap<>();
        for (CtCompilationUnit cu : factory.CompilationUnit().getMap().values()) {
            SourceFile f = cu.getFile() == null ? null : byPath.get(cu.getFile().getPath().replace('\\', '/'));
            if (f == null && cu.getFile() != null) {
                String p = cu.getFile().getPath().replace('\\', '/');
                f = sorted.stream().filter(s -> p.endsWith(s.path())).findFirst().orElse(null);
            }
            if (f != null) {
                units.put(f, cu);
            }
        }
        for (SourceFile f : sorted) {
            CtCompilationUnit cu = units.get(f);
            if ((f.target() || alsoPorted.contains(f.path())) && cu != null) {
                for (CtType<?> t : cu.getDeclaredTypes()) {
                    for (CtType<?> n : t.getElements(new TypeFilter<CtType<?>>(CtType.class))) {
                        targetTypes.add(n.getQualifiedName());
                    }
                }
            }
        }
        List<FileResult> out = new ArrayList<>();
        for (SourceFile f : sorted) {
            if (!f.target()) {
                continue;
            }
            CtCompilationUnit cu = units.get(f);
            if (cu == null) {
                out.add(new FileResult(f.path(), f.text(), Map.of(),
                        List.of(new Todo(f.path(), 1, "parse", "file not in the Spoon model (syntax error?)")), false));
                continue;
            }
            out.add(new Unit(cu, f).run());
        }
        model.getAllTypes(); // keep the model reachable until here
        return new Result(out, Set.copyOf(shimUsed));
    }

    private TypeFacts.Supers sourceSupers(String binaryName) {
        if (sourceSupers.containsKey(binaryName)) {
            return sourceSupers.get(binaryName);
        }
        CtType<?> t = factory.Type().get(binaryName);
        TypeFacts.Supers s = null;
        if (t != null && t.getPosition().isValidPosition()) {
            CtCompilationUnit cu = t.getPosition().getCompilationUnit();
            String sup = null;
            if (t.getSuperclass() != null) {
                sup = resolveOrMarker(t.getSuperclass(), cu);
            } else if (t instanceof CtEnum) {
                sup = "java.lang.Enum";
            } else if (t instanceof CtRecord) {
                sup = "java.lang.Record";
            } else if (t instanceof CtClass) {
                sup = "java.lang.Object";
            }
            List<String> ifs = new ArrayList<>();
            for (CtTypeReference<?> i : t.getSuperInterfaces()) {
                ifs.add(resolveOrMarker(i, cu));
            }
            ifs.sort(null);
            s = new TypeFacts.Supers(sup, ifs);
        }
        sourceSupers.put(binaryName, s);
        return s;
    }

    private String resolveOrMarker(CtTypeReference<?> r, CtCompilationUnit cu) {
        String n = resolve(r, cu);
        return n == null ? "?unresolved:" + r.getQualifiedName() : n;
    }

    /** a package that declares source (mod) types, e.g. a mod class placed in a platform package */
    private boolean isSourcePackage(String pkg) {
        String prefix = pkg + ".";
        return targetTypes.stream().anyMatch(t -> t.startsWith(prefix) && t.indexOf('.', prefix.length()) < 0);
    }

    private boolean isSourceType(String binaryName) {
        return binaryName != null && sourceSupers(binaryName) != null;
    }

    boolean isPlatform(String owner) {
        if (owner == null || targetTypes.contains(owner)) {
            return false;
        }
        for (String p : opts.platformPrefixes()) {
            if (owner.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ types

    private static final Pattern IMPORT_NAME = Pattern.compile("import\\s+(static\\s+)?([\\w$.\\s]+?(?:\\.\\s*\\*)?)\\s*;");

    /** Binary name of a type reference, resolving nested types that noclasspath mode leaves as "Outer.Inner". */
    String resolve(CtTypeReference<?> r, CtCompilationUnit cu) {
        if (r == null || r instanceof CtTypeParameterReference || r instanceof CtWildcardReference) {
            return null;
        }
        if (r instanceof CtArrayTypeReference<?> a) {
            String c = resolve(a.getComponentType(), cu);
            return c == null ? null : c + "[]";
        }
        String q = r.getQualifiedName();
        if (q == null || q.isEmpty() || q.contains("<nulltype>") || q.contains("?")) {
            return null;
        }
        if (r.isPrimitive()) {
            return q;
        }
        if (!q.contains(".") && q.indexOf('$') > 0) {
            q = q.replace('$', '.'); // unresolved nested type "Outer$Inner" (noclasspath): resolve Outer via imports
        }
        String first = q.contains(".") ? q.substring(0, q.indexOf('.')) : q;
        boolean unresolved = !q.contains(".") || Character.isUpperCase(first.charAt(0));
        if (!unresolved) {
            // Package-qualified; nested types may come as "pkg.Outer.Inner" for classes outside the model.
            return nestedBinary(q);
        }
        CtCompilationUnit unit = cu != null ? cu : (r.getPosition().isValidPosition() ? r.getPosition().getCompilationUnit() : null);
        if (unit != null) {
            String in = importMap(unit).get(first);
            if (in != null) {
                String rest = q.length() > first.length() ? q.substring(first.length() + 1).replace('.', '$') : "";
                String base = nestedBinary(in);
                return rest.isEmpty() ? base : base + "$" + rest;
            }
            if (factory != null && unit.getDeclaredPackage() != null) {
                String cand = unit.getDeclaredPackage().getQualifiedName() + "." + q.replace('.', '$');
                if (factory.Type().get(cand) != null) {
                    return cand;
                }
            }
        }
        if (!q.contains(".") && isJavaLang(q)) {
            return "java.lang." + q;
        }
        return null;
    }

    private final Map<CtCompilationUnit, Map<String, String>> importMaps = new IdentityHashMap<>();

    /** simple name -> written canonical name of every single-type import of a unit (parsed from the source text). */
    Map<String, String> importMap(CtCompilationUnit cu) {
        return importMaps.computeIfAbsent(cu, u -> {
            Map<String, String> m = new HashMap<>();
            String src = u.getOriginalSourceCode();
            for (CtImport imp : u.getImports()) {
                if (src == null) {
                    // in-memory sources (no original text): the import's own reference names the type
                    String w = null;
                    if (imp instanceof spoon.experimental.CtUnresolvedImport ui && !ui.isStatic()) {
                        w = ui.getUnresolvedReference();
                    } else if (imp.getImportKind() == spoon.reflect.declaration.CtImportKind.TYPE
                            && imp.getReference() instanceof CtTypeReference<?> tr) {
                        w = tr.getQualifiedName();
                    }
                    if (w != null && !w.endsWith("*")) {
                        w = w.replace('$', '.');
                        m.putIfAbsent(Selector.simple(w), w);
                    }
                    continue;
                }
                if (!imp.getPosition().isValidPosition() || src == null) {
                    continue;
                }
                Matcher mm = IMPORT_NAME.matcher(src);
                mm.region(imp.getPosition().getSourceStart(), Math.min(src.length(), imp.getPosition().getSourceEnd() + 2));
                if (mm.find() && mm.group(1) == null) {
                    String w = mm.group(2).replaceAll("\\s+", "");
                    if (!w.endsWith(".*")) {
                        m.putIfAbsent(Selector.simple(w), w);
                    }
                }
            }
            return m;
        });
    }

    /** "a.b.Outer.Inner" (canonical, from an import) -> binary if the model knows the outer type. */
    private String nestedBinary(String canonical) {
        if (canonical.contains("$") || factory != null && factory.Type().get(canonical) != null) {
            return canonical;
        }
        String[] parts = canonical.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0)) && i < parts.length - 1) {
                StringBuilder sb = new StringBuilder(String.join(".", java.util.Arrays.copyOfRange(parts, 0, i + 1)));
                for (int j = i + 1; j < parts.length; j++) {
                    sb.append('$').append(parts[j]);
                }
                return sb.toString();
            }
        }
        return canonical;
    }

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");
    private static final Set<String> BOXES = Set.of("java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
            "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double");

    static boolean isPrimitiveName(String t) {
        return PRIMITIVES.contains(t);
    }

    static boolean isBox(String t) {
        return BOXES.contains(t);
    }

    private static boolean isJavaLang(String simple) {
        try {
            Class.forName("java.lang." + simple, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ per file

    private record Site(CtElement element, int start, int end) {
    }

    private record Failure(String reason, String detail) {
    }

    private record Decision(Prepared match, Map<String, Object> bindings, List<Failure> failures) {
    }

    /** Placeholder value: a source element or a literal text (implicit receivers). */
    private record Bound(CtElement element, int start, int end, String literal, boolean implicit) {
    }

    private record PendingTodo(Site site, CtElement anchorSource, String reason, String detail) {
    }

    private final class Unit {
        final CtCompilationUnit cu;
        final SourceFile file;
        final String text;
        final String nl;
        final TextEdits edits;
        final List<PendingTodo> todos = new ArrayList<>();
        final Map<TextEdits.Edit, Prepared> ruleEdits = new IdentityHashMap<>();
        final Map<TextEdits.Edit, List<int[]>> editRefs = new IdentityHashMap<>();
        final Set<CtElement> todoSites = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> finalImports = new HashSet<>();
        final Map<String, String> importSimple = new HashMap<>();
        final Set<String> addedImports = new TreeSet<>();
        /** Single-type imports of platform types without a rule (invalid in the target; a rule's import may displace them). */
        final Set<String> unmappedImports = new HashSet<>();
        final String pkg;
        int importInsertAt = 0;

        Unit(CtCompilationUnit cu, SourceFile file) {
            this.cu = cu;
            this.file = file;
            this.text = file.text();
            this.nl = text.contains("\r\n") ? "\r\n" : "\n";
            this.edits = new TextEdits(text);
            this.pkg = cu.getDeclaredPackage() == null ? "" : cu.getDeclaredPackage().getQualifiedName();
        }

        FileResult run() {
            computeImportInsertPoint();
            for (CtImport imp : cu.getImports()) {
                handleImport(imp);
            }
            applyDeclarations();
            for (CtType<?> t : cu.getDeclaredTypes()) {
                for (CtElement e : t.getElements(new TypeFilter<>(CtElement.class))) {
                    dispatch(e);
                }
            }
            // A call on an untyped receiver is only reported when nothing inside the receiver was reported or
            // rewritten already (otherwise the receiver's own TODO explains it).
            for (Object[] u : untypedCalls) {
                CtInvocation<?> inv = (CtInvocation<?>) u[0];
                if (!targetHasTodo(inv.getTarget())) {
                    todo((Site) u[1], "unresolved-type", "cannot type the receiver of " + u[2] + "(...)");
                }
            }
            return finish();
        }

        boolean here(CtElement e) {
            SourcePosition p = e.getPosition();
            return p.isValidPosition() && p.getCompilationUnit() == cu && !e.isImplicit();
        }

        void dispatch(CtElement e) {
            try {
                if (e instanceof spoon.reflect.code.CtStatementList sl && !book.statementPatterns().isEmpty()) {
                    statementPatterns(sl);
                }
                if (e instanceof CtExpression<?> ex && !book.expressionPatterns().isEmpty() && here(ex) && expressionPattern(ex)) {
                    return; // the pattern's replacement owns this node; inner sites are still visited
                }
                if (e instanceof CtInvocation<?> inv) {
                    if (here(inv)) {
                        handleCall(inv);
                    }
                } else if (e instanceof CtConstructorCall<?> cc) {
                    if (here(cc)) {
                        handleNew(cc);
                    }
                } else if (e instanceof CtFieldAccess<?> fa) {
                    if (here(fa)) {
                        handleField(fa);
                    }
                } else if (e instanceof spoon.reflect.code.CtVariableAccess<?> va
                        && va.getVariable() instanceof spoon.reflect.reference.CtUnboundVariableReference<?> ub
                        && ub.getSimpleName().matches("[A-Z][\\w$]*\\.[A-Za-z_$][\\w$]*") && here(va)) {
                    handleUnboundStaticField(va, ub.getSimpleName());
                } else if (e instanceof CtTypeReference<?> tr) {
                    if (here(tr) && !(tr instanceof CtArrayTypeReference) && !(tr instanceof CtTypeParameterReference)
                            && !(tr instanceof CtWildcardReference) && !(tr.getParent() instanceof CtImport)
                            && !tr.isPrimitive()) {
                        handleTypeRef(tr);
                    }
                } else if (e instanceof CtTypeAccess<?> ta && here(ta) && !here(ta.getAccessedType())) {
                    // A static qualifier ("LogicalSide.CLIENT", "Block.Properties.of(...)" for BlockBehaviour$Properties)
                    // whose type reference Spoon gives no position: rewrite the written qualifier inside the type
                    // access's own range. A member rule that replaced the whole access drops this nested edit; a
                    // rule that renamed only the member (or bound the qualifier) keeps it.
                    String t = resolve(ta.getAccessedType(), cu);
                    if (t != null) {
                        typeSite(ta, t, ta.getPosition().getSourceStart(), ta.getPosition().getSourceEnd() + 1);
                    }
                } else if (e instanceof CtAnnotation<?> an) {
                    // Spoon gives the annotation's type reference no source position: rewrite the written name
                    // inside the annotation's own range ("@OnlyIn(...)").
                    CtTypeReference<?> at = an.getAnnotationType();
                    if (here(an) && at != null && !here(at)) {
                        String t = resolve(at, cu);
                        // a fully qualified annotation name is taken as written: without the annotation on the
                        // classpath Spoon may resolve "@a.b.Nullable" to an imported "Nullable" of another package
                        Matcher qa = Pattern.compile("@\\s*([a-z][\\w]*(?:\\s*\\.\\s*[\\w$]+)+)")
                                .matcher(text.substring(an.getPosition().getSourceStart(), Math.min(text.length(), an.getPosition().getSourceEnd() + 1)));
                        if (qa.lookingAt()) {
                            t = nestedBinary(qa.group(1).replaceAll("\\s+", ""));
                        }
                        if (t != null) {
                            typeSite(an, t, an.getPosition().getSourceStart(), an.getPosition().getSourceEnd() + 1);
                        }
                    }
                } else if (e instanceof CtMethod<?> m) {
                    if (here(m) && targetTypes.contains(m.getDeclaringType().getQualifiedName())) {
                        handleOverride(m);
                    }
                } else if (e instanceof CtExecutableReferenceExpression<?, ?> mr) {
                    if (here(mr)) {
                        handleMethodRef(mr);
                    }
                }
            } catch (RuntimeException ex) {
                if (e.getPosition().isValidPosition()) {
                    todo(new Site(e, e.getPosition().getSourceStart(), e.getPosition().getSourceEnd() + 1), "internal",
                            ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
                }
            }
        }

        // -------------------------------------------------------------------------------------------- ranges

        int[] range(CtElement e) {
            SourcePosition p = e.getPosition();
            int s = p.getSourceStart();
            int en = p.getSourceEnd() + 1;
            while (en > s && (text.charAt(en - 1) == ';' || Character.isWhitespace(text.charAt(en - 1)))) {
                en--;
            }
            return new int[] {s, en};
        }

        /**
         * Range of a rewrite site. Spoon keeps casts on the expression and its position covers them
         * ("(byte) Mth.clamp(...)"); the rewritten expression must keep its casts, so the site starts after them.
         * Bindings ({@link #range}) keep their casts.
         */
        int[] siteRange(CtElement e) {
            int[] r = range(e);
            int s = r[0];
            int en = r[1];
            if (e instanceof spoon.reflect.code.CtExpression<?> ex && !ex.getTypeCasts().isEmpty()) {
                java.util.regex.Matcher cm = CAST_PREFIX.matcher(text);
                // "((X) call())": Spoon's range includes the parentheses around the cast; they stay too
                int wrap = 0;
                while (s < en && text.charAt(s) == '(') {
                    cm.region(s, en);
                    if (cm.lookingAt()) {
                        break;
                    }
                    s++;
                    while (s < en && Character.isWhitespace(text.charAt(s))) {
                        s++;
                    }
                    wrap++;
                }
                for (int w = 0; w < wrap; w++) {
                    while (en > s && Character.isWhitespace(text.charAt(en - 1))) {
                        en--;
                    }
                    if (en > s && text.charAt(en - 1) == ')') {
                        en--;
                    }
                }
                int at = s;
                for (int i = 0; i < ex.getTypeCasts().size(); i++) {
                    cm.region(at, en);
                    if (!cm.lookingAt()) {
                        break;
                    }
                    at = cm.end();
                }
                s = at;
            }
            return new int[] {s, en};
        }

        private static final Pattern CAST_PREFIX = Pattern.compile("\\(\\s*[\\w.$\\[\\]<>?, ]+?\\s*\\)\\s*");

        int lineStart(int offset) {
            int i = offset;
            while (i > 0 && text.charAt(i - 1) != '\n') {
                i--;
            }
            return i;
        }

        int lineOf(int offset) {
            int line = 1;
            for (int i = 0; i < offset && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        String indentAt(int lineStart) {
            int i = lineStart;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            return text.substring(lineStart, i);
        }

        // -------------------------------------------------------------------------------------------- typing

        String enclosingType(CtElement e) {
            CtType<?> t = e.getParent(CtType.class);
            return t == null ? null : t.getQualifiedName();
        }

        /** Static type of an expression (binary name) or null; uses SRG/graph member descriptors where Spoon has none. */
        String staticType(CtExpression<?> expr) {
            if (expr == null) {
                return null;
            }
            if (!expr.getTypeCasts().isEmpty()) {
                return resolve(expr.getTypeCasts().get(0), cu);
            }
            if (expr instanceof CtThisAccess<?>) {
                return enclosingType(expr);
            }
            if (expr instanceof CtSuperAccess<?>) {
                String enc = enclosingType(expr);
                TypeFacts.Supers s = enc == null ? null : facts.supers(enc);
                return s == null ? null : s.superclass();
            }
            if (expr instanceof CtTypeAccess<?> ta) {
                return resolve(ta.getAccessedType(), cu);
            }
            String byDesc = null;
            if (expr instanceof CtInvocation<?> inv) {
                byDesc = memberType(inv.getExecutable().getSimpleName(), "method",
                        inv.getTarget() == null || inv.getTarget().isImplicit() ? enclosingType(inv) : staticType(inv.getTarget()),
                        inv.getArguments().size());
            } else if (expr instanceof CtFieldAccess<?> fa) {
                String fname = fa.getVariable().getSimpleName();
                String ftarget = fa.getTarget() == null || fa.getTarget().isImplicit() ? enclosingType(fa) : staticType(fa.getTarget());
                byDesc = memberType(fname, "field", ftarget, -1);
                // Without the source version's classpath Spoon reads "Outer.Nested" (a nested type used as a
                // qualifier, e.g. IFluidHandler.FluidAction.EXECUTE) as a field access: a CamelCase "field" of a type
                // access that no symbol table knows is the nested type.
                if (byDesc == null && ftarget != null && fa.getTarget() instanceof CtTypeAccess<?>
                        && NESTED_TYPE_NAME.matcher(fname).matches() && fa.getVariable().getDeclaration() == null) {
                    return ftarget + "$" + fname;
                }
            }
            if (byDesc != null) {
                return byDesc;
            }
            CtTypeReference<?> t;
            try {
                t = expr.getType();
            } catch (RuntimeException ex) {
                return null;
            }
            CtCompilationUnit declCu = cu;
            if (expr instanceof CtVariableRead<?> vr && vr.getVariable().getDeclaration() != null
                    && vr.getVariable().getDeclaration().getPosition().isValidPosition()) {
                declCu = vr.getVariable().getDeclaration().getPosition().getCompilationUnit();
            }
            if (expr instanceof CtFieldAccess<?> fa && fa.getVariable().getFieldDeclaration() != null
                    && fa.getVariable().getFieldDeclaration().getPosition().isValidPosition()) {
                declCu = fa.getVariable().getFieldDeclaration().getPosition().getCompilationUnit();
            }
            if (expr instanceof CtInvocation<?> inv && inv.getExecutable().getExecutableDeclaration() != null
                    && inv.getExecutable().getExecutableDeclaration().getPosition().isValidPosition()) {
                declCu = inv.getExecutable().getExecutableDeclaration().getPosition().getCompilationUnit();
                if (inv.getExecutable().getExecutableDeclaration() instanceof CtMethod<?> md && md.getType() != null) {
                    String n = resolve(md.getType(), declCu);
                    if (n != null) {
                        return n;
                    }
                }
            }
            return resolve(t, declCu);
        }

        /** Type of a member from the name table: SRG alias (exact) or canonical name on a known receiver. */
        String memberType(String name, String kind, String recvType, int argc) {
            List<NameTable.Member> ms = names.resolveAlias(name).stream().filter(m -> kind.equals(m.kind())).toList();
            if (ms.isEmpty() && recvType != null) {
                List<NameTable.Member> found = new ArrayList<>();
                for (String anc : facts.ancestry(recvType)) {
                    found.addAll(names.members(anc, name).stream().filter(m -> kind.equals(m.kind())).toList());
                    if (!found.isEmpty()) {
                        break;
                    }
                }
                ms = found;
            }
            Set<String> types = new TreeSet<>();
            for (NameTable.Member m : ms) {
                if ("method".equals(kind)) {
                    if (argc >= 0 && m.paramTypes().size() != argc) {
                        continue;
                    }
                    types.add(Descriptors.returnType(m.desc()));
                } else if (m.desc() != null) {
                    types.add(Descriptors.returnType("()" + m.desc()));
                }
            }
            return types.size() == 1 ? types.iterator().next() : null;
        }

        String receiverType(CtExpression<?> target, CtElement site) {
            if (target == null || (target.isImplicit() && target instanceof CtThisAccess<?>)) {
                return enclosingType(site);
            }
            return staticType(target);
        }

        Tri argCompatible(CtExpression<?> arg, String param) {
            if (arg instanceof CtLiteral<?> lit && lit.getValue() == null) {
                return isPrimitiveName(param) ? Tri.NO : Tri.YES;
            }
            String a = staticType(arg);
            if (a == null) {
                return Tri.UNKNOWN;
            }
            String pa = param.replace('$', '.');
            boolean pPrim = isPrimitiveName(pa);
            boolean aPrim = isPrimitiveName(a) || isBox(a);
            if (pPrim || isBox(pa)) {
                return aPrim ? Tri.YES : Tri.NO;
            }
            if (aPrim) {
                return Tri.NO;
            }
            if (pa.endsWith("[]") != a.endsWith("[]")) {
                return Tri.NO;
            }
            String ps = Selector.simple(pa.replace("[]", ""));
            String as = Selector.simple(a.replace("[]", ""));
            if (ps.equals(as) || "Object".equals(ps)) {
                return Tri.YES;
            }
            boolean complete = true;
            for (String anc : facts.ancestry(a.replace("[]", ""))) {
                if (Selector.simple(anc).equals(ps)) {
                    return Tri.YES;
                }
                if (facts.supers(anc) == null) {
                    complete = false;
                }
            }
            return complete ? Tri.NO : Tri.UNKNOWN;
        }

        /**
         * Subtype check for rule owners. A known, non-platform library type outside the sources (fastutil, Guava,
         * the JDK...) cannot extend a platform class, so it is a definite NO rather than UNKNOWN.
         */
        Tri subtype(String sub, String sup) {
            Tri t = facts.isSubtype(sub, sup);
            if (t == Tri.UNKNOWN && sub != null && !sub.startsWith("?") && isPlatformName(sup) && !isPlatformName(sub)
                    && !isSourceType(sub)) {
                return Tri.NO;
            }
            return t;
        }

        boolean isPlatformName(String type) {
            for (String p : opts.platformPrefixes()) {
                if (type.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }

        boolean related(String memberOwner, String selOwner) {
            return memberOwner.equals(selOwner) || facts.isSubtype(selOwner, memberOwner) == Tri.YES
                    || facts.isSubtype(memberOwner, selOwner) == Tri.YES;
        }

        boolean paramsMatch(List<String> declared, List<String> sel) {
            if (declared.size() != sel.size()) {
                return false;
            }
            for (int i = 0; i < sel.size(); i++) {
                if (!Selector.simple(declared.get(i).replace("[]", "")).equals(Selector.simple(sel.get(i).replace("[]", "")))
                        || declared.get(i).endsWith("[]") != sel.get(i).endsWith("[]")) {
                    return false;
                }
            }
            return true;
        }

        Set<String> nameKeys(String name, String kind) {
            Set<String> keys = new TreeSet<>();
            keys.add(name);
            for (NameTable.Member m : names.resolveAlias(name)) {
                if (kind.equals(m.kind())) {
                    keys.add(m.name());
                }
            }
            return keys;
        }

        boolean aliasHit(String name, String kind, Selector sel) {
            if (name.equals(sel.name())) {
                return false;
            }
            for (NameTable.Member m : names.resolveAlias(name)) {
                if (kind.equals(m.kind()) && m.name().equals(sel.name()) && related(m.owner(), sel.owner())
                        && ("field".equals(kind) || paramsMatch(m.paramTypes(), sel.params()))) {
                    return true;
                }
            }
            return false;
        }

        // -------------------------------------------------------------------------------------------- decisions

        interface Eval {
            /** null = no match; a Failure = would match but cannot apply; else bindings. */
            Object eval(Prepared p);
        }

        Decision decide(List<Prepared> candidates, Eval eval) {
            List<Failure> failures = new ArrayList<>();
            Prepared match = null;
            Map<String, Object> bindings = null;
            for (Prepared p : candidates) {
                if (match != null && p.priority() < match.priority()) {
                    break;
                }
                Object r = eval.eval(p);
                if (r == null) {
                    continue;
                }
                if (r instanceof Failure f) {
                    failures.add(f);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) r;
                if (match == null) {
                    match = p;
                    bindings = b;
                } else if (!RuleBook.canonicalReplace(match).equals(RuleBook.canonicalReplace(p)) || !match.imports().equals(p.imports())) {
                    return new Decision(null, null, List.of(new Failure("ambiguous",
                            "rules " + match.id() + " and " + p.id() + " both match with priority " + p.priority())));
                }
            }
            return new Decision(match, bindings, match == null ? failures : List.of());
        }

        Map<String, Object> bindings(Prepared p, CtExpression<?> target, CtElement site, List<? extends CtElement> args,
                String memberName, int[] siteRange) {
            Map<String, Object> b = new LinkedHashMap<>();
            Rule.Bind bind = p.rule().match().bind();
            if (bind == null) {
                return b;
            }
            if (bind.recv() != null) {
                b.put(bind.recv(), boundTarget(target, site, memberName, siteRange));
            }
            if (bind.args() != null) {
                for (int i = 0; i < bind.args().size() && i < args.size(); i++) {
                    int[] r = range(args.get(i));
                    b.put(bind.args().get(i), new Bound(args.get(i), r[0], r[1], null, false));
                }
            }
            if (bind.result() != null) {
                b.put(bind.result(), new Bound(site, siteRange[0], siteRange[1], null, false));
            }
            return b;
        }

        Bound boundTarget(CtExpression<?> target, CtElement site, String memberName, int[] siteRange) {
            if (target == null || target.isImplicit()) {
                if (target instanceof CtTypeAccess<?> ta) {
                    String t = resolve(ta.getAccessedType(), cu);
                    return new Bound(target, -1, -1, t == null ? "this" : Selector.simple(t), true);
                }
                return new Bound(target, -1, -1, "this", true);
            }
            int[] r = derivedTargetRange(siteRange, memberName);
            if (target.getPosition().isValidPosition() && target.getPosition().getCompilationUnit() == cu) {
                int[] t = range(target);
                // noclasspath qualified names ("e.field") may give the target the whole access's range
                if (r == null || t[1] <= r[1]) {
                    return new Bound(target, t[0], t[1], null, false);
                }
            }
            if (r != null) {
                if (target instanceof CtTypeAccess<?> ta) {
                    String t = resolve(ta.getAccessedType(), cu);
                    if (t != null) {
                        typeSite(ta, t, r[0], r[1]);
                    }
                }
                return new Bound(target, r[0], r[1], null, false);
            }
            return new Bound(target, -1, -1, "this", true);
        }

        /**
         * The receiver part of "recv.member" / "recv.member(args)": the text before the LAST ".member" of the site,
         * after skipping a trailing argument list (so "a.get(b).get(c)" gives "a.get(b)").
         */
        int[] derivedTargetRange(int[] siteRange, String memberName) {
            int end = siteRange[1];
            if (end > siteRange[0] && text.charAt(end - 1) == ')') {
                int depth = 0;
                int i = end - 1;
                for (; i >= siteRange[0]; i--) {
                    char c = text.charAt(i);
                    if (c == ')') {
                        depth++;
                    } else if (c == '(' && --depth == 0) {
                        break;
                    }
                }
                end = i;
            }
            while (end > siteRange[0] && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            int nameStart = end - memberName.length();
            if (nameStart <= siteRange[0] || !text.startsWith(memberName, nameStart)) {
                return null;
            }
            int dot = nameStart;
            while (dot > siteRange[0] && Character.isWhitespace(text.charAt(dot - 1))) {
                dot--;
            }
            if (dot <= siteRange[0] || text.charAt(dot - 1) != '.') {
                return null;
            }
            int recvEnd = dot - 1;
            while (recvEnd > siteRange[0] && Character.isWhitespace(text.charAt(recvEnd - 1))) {
                recvEnd--;
            }
            return recvEnd > siteRange[0] ? new int[] {siteRange[0], recvEnd} : null;
        }

        Failure checkPreconditions(Prepared p, Map<String, Object> b, CtElement site, String recvType, List<? extends CtElement> args) {
            for (String c : p.preconditions()) {
                Failure f = precondition(c, p, b, site, recvType, args);
                if (f != null) {
                    return f;
                }
            }
            return null;
        }

        Failure precondition(String c, Prepared p, Map<String, Object> b, CtElement site, String recvType,
                List<? extends CtElement> args) {
            String id = p.id();
            if (c.startsWith("shim:")) {
                return opts.shimEnabled() ? null : new Failure("precondition", id + ": " + c + " (shim disabled)");
            }
            if (c.startsWith("pure:") || c.startsWith("literal:")) {
                Object v = b.get(c.substring(c.indexOf(':') + 1));
                if (!(v instanceof Bound bound)) {
                    return new Failure("unknown-precondition", id + ": " + c + " (unbound placeholder)");
                }
                boolean ok = c.startsWith("pure:") ? bound.implicit() || pure(bound.element())
                        : bound.element() instanceof CtLiteral<?>;
                return ok ? null : new Failure("precondition", id + ": " + c + " not met");
            }
            if (c.startsWith("recv-subtype:")) {
                Tri t = facts.isSubtype(recvType, c.substring("recv-subtype:".length()));
                return t == Tri.YES ? null : new Failure(t == Tri.NO ? "precondition" : "unresolved-type", id + ": " + c);
            }
            if (c.startsWith("arg-subtype:")) {
                String[] parts = c.split(":", 3);
                int i = Integer.parseInt(parts[1]);
                if (i >= args.size() || !(args.get(i) instanceof CtExpression<?> ae)) {
                    return new Failure("precondition", id + ": " + c);
                }
                Tri t = facts.isSubtype(staticType(ae), parts[2]);
                return t == Tri.YES ? null : new Failure(t == Tri.NO ? "precondition" : "unresolved-type", id + ": " + c);
            }
            if (c.startsWith("in-subtype-of:")) {
                Tri t = facts.isSubtype(enclosingType(site), c.substring("in-subtype-of:".length()));
                return t == Tri.YES ? null : new Failure(t == Tri.NO ? "precondition" : "unresolved-type", id + ": " + c);
            }
            if (c.equals("statement") || c.equals("not-statement")) {
                boolean st = site.getParent() instanceof CtStatementList;
                return st == c.equals("statement") ? null : new Failure("precondition", id + ": " + c);
            }
            if (c.startsWith("position:")) {
                // type rules: where the matched type reference is written (RFC 006)
                String want = c.substring("position:".length());
                if (!want.equals("extends") && !want.equals("implements")) {
                    return new Failure("unknown-precondition", id + ": " + c);
                }
                return superRef(site, want.equals("extends")) ? null : new Failure("precondition", id + ": " + c);
            }
            if (c.startsWith("type:")) {
                // type:$x:<fqn> - the expression bound to $x is provably a <fqn> (RFC 006)
                String[] parts = c.split(":", 3);
                Object v = parts.length == 3 ? b.get(parts[1]) : null;
                if (!(v instanceof Bound bound) || !(bound.element() instanceof CtExpression<?> be)) {
                    return new Failure("unknown-precondition", id + ": " + c + " (unbound placeholder)");
                }
                Tri t = facts.isSubtype(staticType(be), parts[2]);
                return t == Tri.YES ? null : new Failure(t == Tri.NO ? "precondition" : "unresolved-type", id + ": " + c);
            }
            if (c.equals("extends") || c.equals("not-extends")) {
                // the matched type reference is a class's direct superclass ("class X extends <here>"): the root of
                // a ported hierarchy is redirected to a 1.19.2-API adapter base class
                boolean ext = site instanceof CtTypeReference<?> tr && tr.getRoleInParent() == spoon.reflect.path.CtRole.SUPER_TYPE
                        && tr.getParent() instanceof spoon.reflect.declaration.CtClass<?> cls && cls.getSuperclass() == tr;
                return ext == c.equals("extends") ? null : new Failure("precondition", id + ": " + c);
            }
            if (c.equals("not-super")) {
                // the call is not a super.m(...) call (those stay virtual calls into the ported superclass)
                boolean sup = site instanceof CtInvocation<?> inv && inv.getTarget() instanceof spoon.reflect.code.CtSuperAccess<?>;
                return sup ? new Failure("precondition", id + ": " + c) : null;
            }
            return new Failure("unknown-precondition", id + ": " + c);
        }

        boolean pure(CtElement e) {
            if (e instanceof CtLiteral<?> || e instanceof CtThisAccess<?> || e instanceof CtTypeAccess<?>
                    || e instanceof CtVariableRead<?> && !(e instanceof CtFieldAccess<?>)) {
                return true;
            }
            if (e instanceof CtFieldAccess<?> fa) {
                return fa.getTarget() == null || pure(fa.getTarget());
            }
            return false;
        }

        /** the type reference is the superclass (extends) / a super-interface (implements) of a declared type. */
        boolean superRef(CtElement site, boolean superclass) {
            if (!(site instanceof CtTypeReference<?> tr) || !tr.isParentInitialized() || !(tr.getParent() instanceof CtType<?> ty)
                    || ty instanceof spoon.reflect.declaration.CtTypeParameter) {
                return false; // a type parameter's bound ("T extends X") is not an extends clause
            }
            if (superclass) {
                return ty.getSuperclass() == tr;
            }
            for (CtTypeReference<?> i : ty.getSuperInterfaces()) {
                if (i == tr) {
                    return true;
                }
            }
            return false;
        }

        // -------------------------------------------------------------------------------------------- patterns (RFC 006)

        final PatternMatcher.Host patternHost = new PatternMatcher.Host() {
            @Override
            public boolean sameMember(String patternName, String targetName, String kind) {
                if (patternName.equals(targetName)) {
                    return true;
                }
                for (NameTable.Member m : names.resolveAlias(targetName)) {
                    if (kind.equals(m.kind()) && m.name().equals(patternName)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String text(CtElement e) {
                int[] r = siteRange(e); // without casts: "(T) x" and "x" bind the same placeholder
                return text.substring(r[0], r[1]).replaceAll("\\s+", "");
            }

            @Override
            public boolean positioned(CtElement e) {
                return here(e);
            }

            @Override
            public String typeText(spoon.reflect.reference.CtTypeReference<?> r) {
                SourcePosition p = r.getPosition();
                if (p == null || !p.isValidPosition() || p.getSourceEnd() < p.getSourceStart() || p.getSourceEnd() >= text.length()) {
                    return null;
                }
                String t = text.substring(p.getSourceStart(), p.getSourceEnd() + 1).trim();
                return t.isEmpty() ? null : t;
            }
        };

        /** Bindings of a pattern match as placeholder values. */
        Map<String, Object> patternBindings(CodePattern pat, PatternMatcher.Match m) {
            Map<String, Object> b = new LinkedHashMap<>();
            for (Map.Entry<String, CtElement> en : m.bound().entrySet()) {
                CtElement el = en.getValue();
                if (pat.localHole(en.getKey()) && el.getPosition() instanceof DeclarationSourcePosition dp) {
                    b.put(en.getKey(), new Bound(el, dp.getNameStart(), dp.getNameEnd() + 1, null, false));
                } else if (pat.statementHole(en.getKey())) {
                    b.put(en.getKey(), new Bound(el, el.getPosition().getSourceStart(), statementEnd(el), null, false));
                } else {
                    // a hole the pattern wrote casts on (e.g. "(T) $e") binds the expression without its casts
                    int[] r = balanced(m.castFree().contains(en.getKey()) ? siteRange(el) : range(el));
                    b.put(en.getKey(), new Bound(el, r[0], r[1], null, false));
                }
            }
            for (Map.Entry<String, String> en : m.types().entrySet()) {
                b.put(en.getKey(), new Bound(null, 0, 0, en.getValue(), false));
            }
            return b;
        }

        /** End (exclusive) of a statement including its ';' when Spoon's position stops before it. */
        int statementEnd(CtElement st) {
            int e = st.getPosition().getSourceEnd() + 1;
            if (e > 0 && e <= text.length() && text.charAt(e - 1) != ';' && text.charAt(e - 1) != '}') {
                int i = e;
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
                if (i < text.length() && text.charAt(i) == ';') {
                    return i + 1;
                }
            }
            return e;
        }

        /** Widens [s, e) over parentheses Spoon left outside an expression's position, so the range is balanced. */
        int[] balanced(int[] r) {
            int s = r[0];
            int e = r[1];
            for (int guard = 0; guard < 16; guard++) {
                int depth = 0;
                int min = 0;
                for (int i = s; i < e; i++) {
                    char c = text.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        min = Math.min(min, depth);
                    }
                }
                if (min < 0) {
                    int i = s;
                    while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
                        i--;
                    }
                    if (i > 0 && text.charAt(i - 1) == '(') {
                        s = i - 1;
                        continue;
                    }
                    break;
                }
                if (depth > 0) {
                    int i = e;
                    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                        i++;
                    }
                    if (i < text.length() && text.charAt(i) == ')') {
                        e = i + 1;
                        continue;
                    }
                }
                break;
            }
            return new int[] {s, e};
        }

        boolean expressionPattern(CtExpression<?> ex) {
            Decision d = decide(book.expressionPatterns(), p -> {
                PatternMatcher.Match m = new PatternMatcher(p.pattern(), patternHost).matchExpression(ex);
                if (m == null) {
                    return null;
                }
                Map<String, Object> b = patternBindings(p.pattern(), m);
                Failure f = checkPreconditions(p, b, ex, null, List.of());
                return f != null ? f : b;
            });
            // a pattern that writes the casts of its root, "(T) $e", consumes the target's casts
            boolean consumesCasts = d.match() != null && !d.match().pattern().expression().getTypeCasts().isEmpty();
            int[] r = balanced(consumesCasts ? range(ex) : siteRange(ex));
            Site site = new Site(ex, r[0], r[1]);
            if (d.match() != null) {
                apply(d.match(), site, d.bindings());
                return true;
            }
            reportPatternFailures(d, site);
            return false;
        }

        /**
         * A pattern whose precondition is definitely false is simply another idiom (no TODO); unprovable types and
         * ambiguities are still reported.
         */
        void reportPatternFailures(Decision d, Site site) {
            d.failures().stream().filter(f -> !"precondition".equals(f.reason())).forEach(f -> todo(site, f.reason(), f.detail()));
        }

        void statementPatterns(spoon.reflect.code.CtStatementList sl) {
            List<CtStatement> st = sl.getStatements();
            for (int i = 0; i < st.size(); ) {
                final int at = i;
                if (!here(st.get(at))) {
                    i++;
                    continue;
                }
                Decision d = decide(book.statementPatterns(), p -> {
                    PatternMatcher.Match m = new PatternMatcher(p.pattern(), patternHost).matchStatements(st, at);
                    if (m == null) {
                        return null;
                    }
                    for (int k = 0; k < p.pattern().stmts().size(); k++) {
                        if (!here(st.get(at + k))) {
                            return null;
                        }
                    }
                    Map<String, Object> b = patternBindings(p.pattern(), m);
                    Failure f = checkPreconditions(p, b, st.get(at), null, List.of());
                    return f != null ? f : b;
                });
                if (d.match() == null) {
                    CtStatement s0 = st.get(at);
                    reportPatternFailures(d, new Site(s0, s0.getPosition().getSourceStart(), statementEnd(s0)));
                    i++;
                    continue;
                }
                int n = d.match().pattern().stmts().size();
                int s = st.get(at).getPosition().getSourceStart();
                int e = statementEnd(st.get(at + n - 1));
                if (d.match().replaceText().isBlank()) {
                    int[] l = wholeLines(s, e);
                    s = l[0];
                    e = l[1];
                }
                apply(d.match(), new Site(st.get(at), s, e), d.bindings());
                i += n;
            }
        }

        /** [s, e) widened to whole lines when only whitespace surrounds it on its first and last line. */
        int[] wholeLines(int s, int e) {
            int ls = lineStart(s);
            if (!text.substring(ls, s).isBlank()) {
                return new int[] {s, e};
            }
            int i = e;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '\r')) {
                i++;
            }
            if (i < text.length() && text.charAt(i) == '\n') {
                return new int[] {ls, i + 1};
            }
            return i >= text.length() ? new int[] {ls, i} : new int[] {s, e};
        }

        // -------------------------------------------------------------------------------------------- declarations (RFC 006)

        void applyDeclarations() {
            for (Prepared p : book.declarations()) {
                Selector sel = p.selector();
                CtType<?> t = factory.Type().get(sel.owner());
                if (t == null || !here(t)) {
                    continue;
                }
                Rule.Edit ed = p.rule().edit();
                CtElement target = t;
                if (sel.name() != null && sel.params() == null) {
                    target = t.getField(sel.name());
                } else if (sel.name() != null) {
                    target = findExecutable(t, sel);
                }
                int at = t.getPosition().getSourceStart();
                Site tsite = new Site(t, at, at);
                if (target == null || !here(target)) {
                    todo(tsite, "unsupported", p.id() + ": " + sel.display() + " not found");
                    continue;
                }
                Failure f = checkPreconditions(p, new LinkedHashMap<>(), target, null, List.of());
                if (f != null) {
                    todo(tsite, f.reason(), f.detail());
                    continue;
                }
                if (!declarationEdits(p, ed, target)) {
                    todo(tsite, "unsupported", p.id() + ": edit does not apply to " + sel.display());
                }
            }
        }

        CtElement findExecutable(CtType<?> t, Selector sel) {
            List<spoon.reflect.declaration.CtExecutable<?>> cands = new ArrayList<>();
            if (Selector.CTOR.equals(sel.name())) {
                if (t instanceof CtClass<?> c) {
                    cands.addAll(c.getConstructors());
                }
            } else {
                cands.addAll(t.getMethodsByName(sel.name()));
            }
            for (spoon.reflect.declaration.CtExecutable<?> ex : cands) {
                List<String> declared = new ArrayList<>();
                for (CtParameter<?> prm : ex.getParameters()) {
                    String n = resolve(prm.getType(), cu);
                    declared.add(n == null ? prm.getType().getSimpleName() : n);
                }
                if (here(ex) && paramsMatch(declared, sel.params())) {
                    return ex;
                }
            }
            return null;
        }

        String declText(String s) {
            return Template.markTargets(RuleBook.relocate(s, opts.shimPackage()));
        }

        void declEdit(Prepared p, CtElement el, int start, int end, String literal) {
            TextEdits.Edit e = edits.replace(start, end, literal, p);
            ruleEdits.put(e, p);
            ruleSites.add(el);
        }

        boolean declarationEdits(Prepared p, Rule.Edit ed, CtElement target) {
            boolean any = false;
            if (target instanceof CtType<?> t && t.getPosition() instanceof BodyHolderSourcePosition bp) {
                int body = bp.getBodyStart();
                if (ed.addSupertypes() != null) {
                    String list = String.join(", ", ed.addSupertypes().stream().map(this::declText).toList());
                    int last = -1;
                    for (CtTypeReference<?> i : t.getSuperInterfaces()) {
                        if (i.getPosition().isValidPosition() && i.getPosition().getCompilationUnit() == cu) {
                            last = Math.max(last, i.getPosition().getSourceEnd() + 1);
                        }
                    }
                    if (last >= 0) {
                        declEdit(p, t, last, last, ", " + list);
                    } else {
                        String kw = t instanceof spoon.reflect.declaration.CtInterface<?> ? "extends " : "implements ";
                        String pre = body > 0 && Character.isWhitespace(text.charAt(body - 1)) ? "" : " ";
                        declEdit(p, t, body, body, pre + kw + list + " ");
                    }
                    any = true;
                }
                if (ed.addMembers() != null) {
                    declEdit(p, t, body + 1, body + 1, declText(ed.addMembers()));
                    any = true;
                }
                if (ed.appendMembers() != null) {
                    int last = body + 1;
                    for (CtTypeMember m : t.getTypeMembers()) {
                        if (here(m)) {
                            last = Math.max(last, statementEnd(m));
                        }
                    }
                    declEdit(p, t, last, last, declText(ed.appendMembers()));
                    any = true;
                }
                return any && ed.initializer() == null && ed.body() == null && ed.remove() == null;
            }
            if (ed.addSupertypes() != null || ed.addMembers() != null || ed.appendMembers() != null) {
                return false;
            }
            if (ed.initializer() != null) {
                if (!(target instanceof spoon.reflect.declaration.CtField<?> f) || !(f.getPosition() instanceof DeclarationSourcePosition dp)) {
                    return false;
                }
                String init = declText(ed.initializer());
                if (f.getDefaultExpression() != null && here(f.getDefaultExpression())) {
                    int[] r = balanced(range(f.getDefaultExpression()));
                    declEdit(p, f, r[0], r[1], init);
                } else {
                    int semi = text.indexOf(';', dp.getNameEnd() + 1);
                    if (semi < 0) {
                        return false;
                    }
                    declEdit(p, f, semi, semi, " = " + init);
                }
                any = true;
            }
            if (ed.body() != null) {
                if (!(target.getPosition() instanceof BodyHolderSourcePosition bp) || bp.getBodyStart() <= 0) {
                    return false;
                }
                declEdit(p, target, bp.getBodyStart() + 1, bp.getBodyEnd(), declText(ed.body()));
                any = true;
            }
            if (Boolean.TRUE.equals(ed.remove())) {
                SourcePosition pos = target.getPosition();
                int s = pos.getSourceStart();
                if (pos instanceof DeclarationSourcePosition dp && dp.getModifierSourceStart() > 0) {
                    s = Math.min(s, dp.getModifierSourceStart());
                }
                for (CtAnnotation<?> a : target.getAnnotations()) {
                    if (a.getPosition().isValidPosition()) {
                        s = Math.min(s, a.getPosition().getSourceStart());
                    }
                }
                int[] l = wholeLines(s, statementEnd(target));
                declEdit(p, target, l[0], l[1], "");
                any = true;
            }
            return any;
        }

        // -------------------------------------------------------------------------------------------- sites

        void handleCall(CtInvocation<?> inv) {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = ex.getSimpleName();
            int[] r = siteRange(inv);
            Site site = new Site(inv, r[0], r[1]);
            List<CtExpression<?>> args = inv.getArguments();
            boolean ctor = Selector.CTOR.equals(name);
            String recvType = ctor ? ctorOwner(inv, ex) : receiverType(inv.getTarget(), inv);
            List<Prepared> cands = book.calls(ctor ? Set.of(Selector.CTOR) : nameKeys(name, "method"));
            Decision d = decide(cands, p -> {
                Selector sel = p.selector();
                if (sel.params().size() != args.size()) {
                    return null;
                }
                boolean alias = false;
                if (ctor) {
                    if (!sel.owner().equals(recvType)) {
                        return null;
                    }
                } else {
                    alias = aliasHit(name, "method", sel);
                    if (!name.equals(sel.name()) && !alias) {
                        return null;
                    }
                    Tri sub = subtype(recvType, sel.owner());
                    if (sub == Tri.NO) {
                        return null;
                    }
                    if (sub == Tri.UNKNOWN && !alias) {
                        return new Failure("unresolved-type", "cannot prove receiver " + (recvType == null ? "<untyped>" : recvType)
                                + " is a " + sel.owner() + " (" + p.id() + ")");
                    }
                }
                for (int i = 0; i < args.size(); i++) {
                    if (argCompatible(args.get(i), sel.params().get(i)) == Tri.NO) {
                        return null;
                    }
                }
                Map<String, Object> b = bindings(p, inv.getTarget(), inv, args, name, r);
                Failure f = checkPreconditions(p, b, inv, recvType, args);
                return f != null ? f : b;
            });
            if (d.match() != null) {
                apply(d.match(), site, d.bindings());
                return;
            }
            if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
                return;
            }
            String owner = ctor ? recvType : callOwner(inv, name, recvType);
            if (owner != null && isPlatform(owner)) {
                todo(site, statusReason(owner, ctor ? "<init>" : canonicalName(name, "method")),
                        (ctor ? "constructor call " : "") + memberDisplay(owner, name, "method", args.size()));
            } else if (owner == null && opts.platformTodos() && recvType == null && !ctor) {
                untypedCalls.add(new Object[] {inv, site, name}); // decided in run(), once inner sites are known
            }
        }

        /** Owner of a super(...)/this(...) call: the declaring type, else the enclosing class or its superclass. */
        String ctorOwner(CtInvocation<?> inv, CtExecutableReference<?> ex) {
            String enc = enclosingType(inv);
            String written = text.substring(range(inv)[0], range(inv)[1]).trim();
            if (written.startsWith("this")) {
                return enc;
            }
            // super(...): the written superclass (noclasspath reports java.lang.Object for unknown superclasses).
            TypeFacts.Supers s = enc == null ? null : facts.supers(enc);
            if (s != null && s.superclass() != null && !s.superclass().startsWith("?unresolved:")) {
                return s.superclass();
            }
            return resolve(ex.getDeclaringType(), cu);
        }

        boolean targetHasTodo(CtElement target) {
            if (target == null) {
                return false;
            }
            for (CtElement e : target.getElements(new TypeFilter<>(CtElement.class))) {
                if (todoSites.contains(e) || ruleSites.contains(e)) {
                    return true;
                }
            }
            return false;
        }

        final Set<CtElement> ruleSites = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Object[]> untypedCalls = new ArrayList<>();

        /** Declaring owner of a called member, when it can be attributed; null if unknown. */
        String callOwner(CtInvocation<?> inv, String name, String recvType) {
            return memberOwner(name, "method", recvType, inv.getExecutable().getExecutableDeclaration());
        }

        String memberOwner(String name, String kind, String recvType, CtElement declaration) {
            List<NameTable.Member> ms = names.resolveAlias(name).stream().filter(m -> kind.equals(m.kind())).toList();
            if (!ms.isEmpty()) {
                if (recvType != null) {
                    for (String anc : facts.ancestry(recvType)) {
                        for (NameTable.Member m : ms) {
                            if (m.owner().equals(anc)) {
                                return anc;
                            }
                        }
                    }
                }
                return ms.stream().map(NameTable.Member::owner).sorted().findFirst().orElse(null);
            }
            if (declaration != null && declaration.getPosition().isValidPosition()) {
                CtType<?> t = declaration.getParent(CtType.class);
                return t == null ? null : t.getQualifiedName();
            }
            if (recvType == null) {
                return null;
            }
            // Not declared in the sources: inherited. Find the declaring ancestor where we can, else the first
            // non-source ancestor (that is where the member must come from).
            String firstForeign = null;
            for (String anc : facts.ancestry(recvType)) {
                if (anc.startsWith("?unresolved:")) {
                    continue;
                }
                if (isSourceType(anc)) {
                    if (isPlatform(anc) && declaresMember(anc, name, kind)) {
                        return anc;
                    }
                    continue;
                }
                if (anc.startsWith("java.") || anc.startsWith("javax.")) {
                    if (javaDeclares(anc, name, kind)) {
                        if ("java.lang.Object".equals(anc) || "java.lang.Enum".equals(anc) || firstForeign == null) {
                            return anc;
                        }
                        // A JDK member reached through a platform type that is renamed away (ListTag is a List in
                        // 1.19.2, NBTTagList is not): still a platform call.
                        return renamedType(firstForeign) ? firstForeign : anc;
                    }
                    continue;
                }
                if (firstForeign == null) {
                    firstForeign = anc;
                }
                if (!names.members(anc, name).isEmpty()) {
                    return anc;
                }
            }
            return firstForeign;
        }

        boolean renamedType(String type) {
            return book.types(type).stream().anyMatch(p -> !p.identityType());
        }

        boolean javaDeclares(String type, String name, String kind) {
            try {
                Class<?> c = Class.forName(type, false, ClassLoader.getPlatformClassLoader());
                if ("field".equals(kind)) {
                    return java.util.Arrays.stream(c.getFields()).anyMatch(f -> f.getName().equals(name));
                }
                return java.util.Arrays.stream(c.getMethods()).anyMatch(m -> m.getName().equals(name))
                        || java.util.Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name));
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }

        boolean declaresMember(String type, String name, String kind) {
            CtType<?> t = factory.Type().get(type);
            if (t == null) {
                return false;
            }
            return "field".equals(kind) ? t.getField(name) != null
                    : t.getMethods().stream().anyMatch(m -> m.getSimpleName().equals(name));
        }

        String canonicalName(String name, String kind) {
            return names.resolveAlias(name).stream().filter(m -> kind.equals(m.kind())).map(NameTable.Member::name)
                    .findFirst().orElse(name);
        }

        String memberDisplay(String owner, String name, String kind, int argc) {
            List<NameTable.Member> ms = names.resolveAlias(name).stream().filter(m -> kind.equals(m.kind())).toList();
            if (!ms.isEmpty()) {
                NameTable.Member m = ms.stream().filter(x -> x.owner().equals(owner)).findFirst().orElse(ms.get(0));
                String d = "method".equals(kind)
                        ? m.owner() + "#" + m.name() + "(" + String.join(", ", m.paramTypes().stream().map(Selector::simple).toList()) + ")"
                        : m.owner() + "#" + m.name();
                return d + " [" + name + "]";
            }
            return "method".equals(kind) ? owner + "#" + name + "(" + argc + " args)" : owner + "#" + name;
        }

        String statusReason(String owner, String name) {
            String s = graphStatus.get(owner + "#" + name);
            if (s == null) {
                s = graphStatus.get(owner);
            }
            if (s == null) {
                return "no-rule";
            }
            return switch (s) {
                case "removed" -> "removed";
                case "signature_changed" -> "signature-changed";
                case "split", "merged" -> s;
                default -> "no-rule";
            };
        }

        void handleNew(CtConstructorCall<?> cc) {
            int[] r = siteRange(cc);
            Site site = new Site(cc, r[0], r[1]);
            String owner = resolve(cc.getType(), cu);
            List<CtExpression<?>> args = cc.getArguments();
            Decision d = decide(book.news(owner == null ? "" : owner), p -> {
                if (p.selector().params().size() != args.size()) {
                    return null;
                }
                for (int i = 0; i < args.size(); i++) {
                    if (argCompatible(args.get(i), p.selector().params().get(i)) == Tri.NO) {
                        return null;
                    }
                }
                if (cc instanceof CtNewClass<?>) {
                    return new Failure("unsupported", p.id() + ": anonymous subclass of " + owner);
                }
                Map<String, Object> b = bindings(p, null, cc, args, "", r);
                Failure f = checkPreconditions(p, b, cc, owner, args);
                return f != null ? f : b;
            });
            if (d.match() != null) {
                apply(d.match(), site, d.bindings());
            } else if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
            } else if (owner != null && isPlatform(owner)) {
                todo(site, statusReason(owner, "<init>"), "new " + owner + "(" + args.size() + " args)");
            }
        }

        /**
         * "Type.NAME" that Spoon could not bind to a field and kept as one unbound variable "Type.NAME": it happens in
         * a lambda whose target type is unresolved (the body is attributed without the lambda's context). The
         * qualifier is a type imported by the file: handled like the static field access it is.
         */
        void handleUnboundStaticField(spoon.reflect.code.CtVariableAccess<?> va, String qualified) {
            int dot = qualified.indexOf('.');
            String qual = qualified.substring(0, dot);
            String name = qualified.substring(dot + 1);
            String owner = null;
            for (CtImport imp : cu.getImports()) {
                String ip = imp.toString().replaceAll("^import\\s+|;\\s*$", "");
                if (ip.endsWith("." + qual) && !ip.contains("*")) {
                    owner = ip;
                    break;
                }
            }
            if (owner == null) {
                return;
            }
            final String recvType = owner;
            int[] r = siteRange(va);
            Site site = new Site(va, r[0], r[1]);
            Decision d = decide(book.fields(nameKeys(name, "field")), p -> {
                Selector sel = p.selector();
                boolean alias = aliasHit(name, "field", sel);
                if (!name.equals(sel.name()) && !alias) {
                    return null;
                }
                if (subtype(recvType, sel.owner()) != Tri.YES) {
                    return null;
                }
                Map<String, Object> b = bindings(p, null, va, List.of(), name, r);
                Failure f = checkPreconditions(p, b, va, recvType, List.of());
                return f != null ? f : b;
            });
            if (d.match() != null) {
                apply(d.match(), site, d.bindings());
            }
        }

        void handleField(CtFieldAccess<?> fa) {
            CtFieldReference<?> ref = fa.getVariable();
            String name = ref.getSimpleName();
            if ("length".equals(name) || "class".equals(name)) {
                return;
            }
            int[] r = siteRange(fa);
            Site site = new Site(fa, r[0], r[1]);
            String recvType = receiverType(fa.getTarget(), fa);
            Decision d = decide(book.fields(nameKeys(name, "field")), p -> {
                Selector sel = p.selector();
                boolean alias = aliasHit(name, "field", sel);
                if (!name.equals(sel.name()) && !alias) {
                    return null;
                }
                Tri sub = subtype(recvType, sel.owner());
                if (sub == Tri.NO) {
                    return null;
                }
                if (sub == Tri.UNKNOWN && !alias) {
                    return new Failure("unresolved-type", "cannot prove " + (recvType == null ? "<untyped>" : recvType)
                            + " is a " + sel.owner() + " (" + p.id() + ")");
                }
                Map<String, Object> b = bindings(p, fa.getTarget(), fa, List.of(), name, r);
                Failure f = checkPreconditions(p, b, fa, recvType, List.of());
                return f != null ? f : b;
            });
            if (d.match() != null) {
                apply(d.match(), site, d.bindings());
                return;
            }
            if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
                return;
            }
            // A static access "Type.FIELD" whose type part has no position: still give the type its own site.
            if (fa.getTarget() instanceof CtTypeAccess<?> ta && !ta.isImplicit() && !ta.getPosition().isValidPosition()) {
                int[] tr = derivedTargetRange(r, name);
                String t = resolve(ta.getAccessedType(), cu);
                if (tr != null && t != null) {
                    typeSite(ta, t, tr[0], tr[1]);
                }
            }
            String owner = memberOwner(name, "field", recvType,
                    ref.getFieldDeclaration() == null ? null : ref.getFieldDeclaration());
            if (owner != null && isPlatform(owner)) {
                todo(site, statusReason(owner, canonicalName(name, "field")), memberDisplay(owner, name, "field", 0));
            }
        }

        void handleMethodRef(CtExecutableReferenceExpression<?, ?> mr) {
            int[] r = range(mr);
            String name = mr.getExecutable().getSimpleName();
            String owner = mr.getTarget() == null ? null : staticType(mr.getTarget());
            String o2 = owner == null ? null : memberOwner(name, "method", owner, mr.getExecutable().getExecutableDeclaration());
            if (o2 != null && isPlatform(o2)) {
                todo(new Site(mr, r[0], r[1]), "no-rule", "method reference " + memberDisplay(o2, name, "method", -1));
            }
        }

        void handleOverride(CtMethod<?> m) {
            String name = m.getSimpleName();
            String declaring = m.getDeclaringType().getQualifiedName();
            if (!(m.getPosition() instanceof DeclarationSourcePosition dp)) {
                return;
            }
            int ns = dp.getNameStart();
            int ne = dp.getNameEnd() + 1;
            Site site = new Site(m, ns, ne);
            List<CtParameter<?>> params = m.getParameters();
            Decision d = decide(book.overrides(nameKeys(name, "method")), p -> {
                Selector sel = p.selector();
                if (sel.params().size() != params.size()) {
                    return null;
                }
                boolean alias = aliasHit(name, "method", sel);
                if (!name.equals(sel.name()) && !alias) {
                    return null;
                }
                List<String> declared = new ArrayList<>();
                for (CtParameter<?> prm : params) {
                    String t = resolve(prm.getType(), cu);
                    declared.add(t == null ? prm.getType().getSimpleName() : t);
                }
                if (!paramsMatch(declared, sel.params())) {
                    return null;
                }
                Tri sub = subtype(declaring, sel.owner());
                if (sub == Tri.NO) {
                    return null;
                }
                if (sub == Tri.UNKNOWN && !alias) {
                    return new Failure("unresolved-type", "cannot prove " + declaring + " extends " + sel.owner() + " (" + p.id() + ")");
                }
                Map<String, Object> b = new LinkedHashMap<>();
                Failure f = checkPreconditions(p, b, m, declaring, List.of());
                return f != null ? f : b;
            });
            if (d.match() != null) {
                applyOverride(d.match(), m, dp, site);
                return;
            }
            if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
                return;
            }
            String owner = overriddenPlatformOwner(m, name, declaring);
            if (owner != null) {
                todo(site, statusReason(owner, canonicalName(name, "method")),
                        "override " + memberDisplay(owner, name, "method", params.size()));
            }
        }

        String overriddenPlatformOwner(CtMethod<?> m, String name, String declaring) {
            List<NameTable.Member> ms = names.resolveAlias(name).stream().filter(x -> "method".equals(x.kind())).toList();
            if (!ms.isEmpty()) {
                String o = memberOwner(name, "method", declaring, null);
                return o != null && isPlatform(o) ? o : null;
            }
            boolean annotated = m.getAnnotations().stream().anyMatch(a -> "Override".equals(a.getAnnotationType().getSimpleName()));
            if (!annotated) {
                return null;
            }
            try {
                for (CtMethod<?> top : m.getTopDefinitions()) {
                    CtType<?> t = top.getDeclaringType();
                    if (t != null && t.getPosition().isValidPosition()) {
                        String o = t.getQualifiedName();
                        return isPlatform(o) ? o : null;
                    }
                }
            } catch (RuntimeException ignored) {
                // noclasspath: fall through to the hierarchy walk
            }
            for (String anc : facts.ancestry(declaring)) {
                if (anc.equals(declaring)) {
                    continue;
                }
                if (isPlatform(anc) && (!isSourceType(anc) || declaresMember(anc, name, "method"))) {
                    return anc;
                }
            }
            return null;
        }

        void handleTypeRef(CtTypeReference<?> tr) {
            String t = resolve(tr, cu);
            if (tr.getParent() instanceof CtAnnotation<?>) {
                // fully qualified annotation names are taken as written (see dispatch)
                SourcePosition ps = tr.getPosition();
                String w = text.substring(ps.getSourceStart(), Math.min(text.length(), ps.getSourceEnd() + 1)).replaceAll("\\s+", "");
                if (w.matches("[a-z]\\w*(\\.[\\w$]+)+")) {
                    t = nestedBinary(w);
                }
            }
            if (t == null) {
                return;
            }
            SourcePosition p = tr.getPosition();
            typeSite(tr, t, p.getSourceStart(), p.getSourceEnd() + 1);
        }

        /** CamelCase (not CONSTANT_CASE) simple name: a nested type when it qualifies a member of a type access. */
        private static final Pattern NESTED_TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*");

        private static final Pattern IDENT_CHAIN =Pattern.compile("(?<![\\w$.])((?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*)([A-Za-z_$][\\w$]*)(?![\\w$])");

        /** A written type name somewhere in [start, end): rename it or report it. */
        void typeSite(CtElement element, String binary, int start, int end) {
            String simple = Selector.simple(binary);
            Matcher m = IDENT_CHAIN.matcher(text);
            m.region(start, end);
            int ns = -1;
            int ne = -1;
            while (m.find()) {
                if (m.group(2).equals(simple)) {
                    ns = m.start();
                    ne = m.end();
                    break;
                }
            }
            if (ns < 0) {
                return; // e.g. 'var' or an implicit reference: nothing written to rewrite
            }
            Site site = new Site(element, ns, ne);
            List<Prepared> cands = book.types(binary);
            Decision d = decide(cands, p -> {
                Failure f = checkPreconditions(p, new LinkedHashMap<>(), element, null, List.of());
                return f != null ? f : new LinkedHashMap<String, Object>();
            });
            if (d.match() != null) {
                if (d.match().identityType()) {
                    return;
                }
                applyType(d.match(), binary, site, text.substring(ns, ne).replaceAll("\\s+", ""));
            } else if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
            } else if (isPlatform(binary)) {
                todo(site, statusReason(binary, null), "type " + binary);
            }
        }

        // -------------------------------------------------------------------------------------------- imports

        void computeImportInsertPoint() {
            int at = 0;
            for (CtImport imp : cu.getImports()) {
                if (imp.getPosition().isValidPosition()) {
                    at = Math.max(at, imp.getPosition().getSourceEnd() + 1);
                }
            }
            if (at == 0) {
                Matcher pm = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(text);
                if (pm.find()) {
                    at = pm.end();
                }
            }
            if (at > 0) {
                int nlAt = text.indexOf('\n', at);
                at = nlAt < 0 ? text.length() : nlAt + 1;
            }
            importInsertAt = at;
        }

        void handleImport(CtImport imp) {
            if (!imp.getPosition().isValidPosition()) {
                return;
            }
            int s = imp.getPosition().getSourceStart();
            int e = imp.getPosition().getSourceEnd() + 1;
            Matcher m = IMPORT_NAME.matcher(text);
            m.region(s, Math.min(text.length(), e + 1));
            if (!m.find()) {
                return;
            }
            Site site = new Site(imp, m.start(2), m.end(2));
            String written = m.group(2).replaceAll("\\s+", "");
            if (m.group(1) != null || written.endsWith(".*")) {
                String owner = written.endsWith(".*") ? written.substring(0, written.length() - 2)
                        : written.substring(0, written.lastIndexOf('.'));
                // the owner type has a type rule: rename the owner part ("import static a.B.C;" -> "import static x.B2.C;")
                String ownerBinary = nestedBinary(owner);
                Decision od = decide(book.types(ownerBinary), p -> {
                    Failure f = checkPreconditions(p, new LinkedHashMap<>(), imp, null, List.of());
                    return f != null ? f : new LinkedHashMap<String, Object>();
                });
                if (od.match() != null && !od.match().identityType()
                        && text.startsWith(owner, site.start())) {
                    String target = Selector.canonical(od.match().replaceText().trim());
                    TextEdits.Edit ed = edits.replace(site.start(), site.start() + owner.length(), target, od.match());
                    ruleEdits.put(ed, od.match());
                    ruleSites.add(imp);
                    return;
                }
                if (m.group(1) == null && written.endsWith(".*") && isPlatform(owner + ".") && !isSourcePackage(owner)) {
                    // on-demand import of a platform package: source-version packages do not exist in the target;
                    // every rewritten type is imported by name, so the wildcard is commented out
                    edits.replace(s, e, "// porter: dropped on-demand import of platform package " + owner, null);
                    todo(site, "no-rule", "on-demand import " + written + " (dropped)");
                    return;
                }
                if (isPlatform(owner + ".") && opts.platformTodos()) {
                    todo(site, "no-rule", (m.group(1) != null ? "static import " : "on-demand import ") + written);
                }
                return;
            }
            // Single-type import (Spoon marks imports of classes it cannot see as UNRESOLVED: use the written name).
            String binary = nestedBinary(written);
            List<Prepared> cands = book.types(binary);
            final String bin = binary;
            Decision d = decide(cands, p -> {
                Failure f = checkPreconditions(p, new LinkedHashMap<>(), imp, null, List.of());
                return f != null ? f : new LinkedHashMap<String, Object>();
            });
            if (d.match() != null) {
                String target = d.match().identityType() ? written : Selector.canonical(d.match().replaceText().trim());
                if (!d.match().identityType()) {
                    TextEdits.Edit ed = edits.replace(site.start(), site.end(), target, d.match());
                    ruleEdits.put(ed, d.match());
                    ruleSites.add(imp);
                }
                finalImports.add(target);
                importSimple.putIfAbsent(Selector.simple(target), target);
                return;
            }
            finalImports.add(written);
            importSimple.putIfAbsent(Selector.simple(written), written);
            if (!d.failures().isEmpty()) {
                d.failures().forEach(f -> todo(site, f.reason(), f.detail()));
            } else if (isPlatform(bin)) {
                todo(site, statusReason(bin, null), "import " + bin);
                unmappedImports.add(written);
            }
        }

        /**
         * An import a rule declares ({@code imports:}): like {@link #ensureImport}, but it may displace a single-type
         * import of an unmapped platform type with the same simple name (that import is invalid in the target; it stays
         * in the text with its TODO and is dropped by the import cleanup). Without this the replacement's simple name
         * would silently resolve to the stale class.
         */
        boolean ensureRuleImport(String canonical) {
            String simple = Selector.simple(canonical);
            String taken = importSimple.get(simple);
            if (taken != null && !taken.equals(canonical) && unmappedImports.contains(taken)) {
                importSimple.remove(simple);
                finalImports.remove(taken);
            }
            return ensureImport(canonical);
        }

        /** Makes {@code canonical} importable; false if its simple name is taken by another import. */
        boolean ensureImport(String canonical) {
            if (!canonical.contains(".")) {
                return true;
            }
            String p = canonical.substring(0, canonical.lastIndexOf('.'));
            if (p.equals(pkg) || "java.lang".equals(p) || finalImports.contains(canonical)) {
                return true;
            }
            String simple = Selector.simple(canonical);
            String taken = importSimple.get(simple);
            if (taken != null && !taken.equals(canonical)) {
                return false;
            }
            importSimple.put(simple, canonical);
            finalImports.add(canonical);
            addedImports.add(canonical);
            return true;
        }

        // -------------------------------------------------------------------------------------------- apply

        void apply(Prepared p, Site site, Map<String, Object> bindings) {
            List<TextEdits.Piece> pieces = new ArrayList<>();
            List<int[]> refs = new ArrayList<>();
            List<Template.Part> parts = p.template().parts();
            for (int i = 0; i < parts.size(); i++) {
                Template.Part part = parts.get(i);
                if (part.literal() != null) {
                    pieces.add(new TextEdits.Lit(part.literal()));
                    continue;
                }
                Object v = bindings.get(part.placeholder());
                if (!(v instanceof Bound b)) {
                    pieces.add(new TextEdits.Lit(part.placeholder()));
                    continue;
                }
                if (b.literal() != null) {
                    // Implicit receiver: "$r.name(...)" keeps the receiver implicit ("name(...)").
                    if (i == 0 && i + 1 < parts.size() && parts.get(1).literal() != null && parts.get(1).literal().startsWith(".")) {
                        pieces.add(new TextEdits.Lit(parts.get(1).literal().substring(1)));
                        i++;
                        continue;
                    }
                    pieces.add(new TextEdits.Lit(b.literal()));
                } else {
                    // "$e.member" with a non-primary binding ((T) x, a + b, c ? x : y): keep the member on the whole expression.
                    boolean wrap = i + 1 < parts.size() && parts.get(i + 1).literal() != null && parts.get(i + 1).literal().startsWith(".")
                            && needsParens(b.element(), castsStripped(b)) && !enclosed(b.start(), b.end());
                    if (wrap) {
                        pieces.add(new TextEdits.Lit("("));
                    }
                    pieces.add(new TextEdits.Ref(b.start(), b.end()));
                    refs.add(new int[] {b.start(), b.end()});
                    if (wrap) {
                        pieces.add(new TextEdits.Lit(")"));
                    }
                }
            }
            TextEdits.Edit e = edits.replace(site.start(), site.end(), pieces, p);
            ruleEdits.put(e, p);
            editRefs.put(e, refs);
            ruleSites.add(site.element());
            p.imports().forEach(this::ensureRuleImport);
        }

        /** the text [start, end) is one parenthesised expression already. */
        private boolean enclosed(int start, int end) {
            if (end - start < 2 || text.charAt(start) != '(' || text.charAt(end - 1) != ')') {
                return false;
            }
            int depth = 0;
            for (int k = start; k < end; k++) {
                char c = text.charAt(k);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return k == end - 1;
                }
            }
            return false;
        }

        private boolean castsStripped(Bound b) {
            return b.element() instanceof CtExpression<?> ex && !ex.getTypeCasts().isEmpty() && b.start() > range(b.element())[0];
        }

        private static boolean needsParens(CtElement el, boolean castsStripped) {
            if (!(el instanceof CtExpression<?> ex)) {
                return false;
            }
            return (!castsStripped && !ex.getTypeCasts().isEmpty()) || el instanceof spoon.reflect.code.CtBinaryOperator<?>
                    || el instanceof spoon.reflect.code.CtUnaryOperator<?> || el instanceof spoon.reflect.code.CtConditional<?>
                    || el instanceof spoon.reflect.code.CtAssignment<?, ?> || el instanceof spoon.reflect.code.CtLambda<?>;
        }

        void applyType(Prepared p, String oldBinary, Site site, String written) {
            String newBinary = nestedBinary(p.replaceText().trim());
            String newCanonical = Selector.canonical(newBinary);
            String replacement;
            if (written.equals(Selector.canonical(oldBinary)) && written.contains(".") && Character.isLowerCase(written.charAt(0))) {
                replacement = newCanonical;
            } else if (written.contains(".")) {
                String top = newBinary.contains("$") ? newBinary.substring(0, newBinary.indexOf('$')) : newBinary;
                String chain = Selector.simple(top) + (newBinary.contains("$") ? "." + newBinary.substring(newBinary.indexOf('$') + 1).replace('$', '.') : "");
                replacement = ensureImport(Selector.canonical(top)) ? chain : newCanonical;
            } else {
                replacement = ensureImport(newCanonical) ? Selector.simple(newBinary) : newCanonical;
            }
            TextEdits.Edit e = edits.replace(site.start(), site.end(), replacement, p);
            ruleEdits.put(e, p);
            ruleSites.add(site.element());
            p.imports().forEach(this::ensureRuleImport);
        }

        void applyOverride(Prepared p, CtMethod<?> m, DeclarationSourcePosition dp, Site site) {
            String rep = p.replaceText().trim();
            Matcher vm = Pattern.compile("^(?:(public|protected|private)\\s+)?(?:([\\w$.\\[\\]]+)\\s+)?([\\w$]+)$").matcher(rep);
            if (!vm.matches()) {
                todo(site, "unsupported", p.id() + ": override replacement must be '[visibility] [returnType] name'");
                return;
            }
            String vis = vm.group(1);
            String returnType = vm.group(2);
            String newName = vm.group(3);
            TextEdits.Edit e = edits.replace(site.start(), site.end(), newName, p);
            ruleEdits.put(e, p);
            ruleSites.add(m);
            if (returnType != null && m.getType() != null && m.getType().getPosition().isValidPosition()) {
                SourcePosition tp = m.getType().getPosition();
                edits.replace(tp.getSourceStart(), tp.getSourceEnd() + 1, returnType, p);
            }
            if (vis != null && !m.hasModifier(ModifierKind.valueOf(vis.toUpperCase(java.util.Locale.ROOT)))) {
                boolean replaced = false;
                for (CtExtendedModifier em : m.getExtendedModifiers()) {
                    ModifierKind k = em.getKind();
                    if ((k == ModifierKind.PUBLIC || k == ModifierKind.PROTECTED || k == ModifierKind.PRIVATE)
                            && em.getPosition().isValidPosition()) {
                        edits.replace(em.getPosition().getSourceStart(), em.getPosition().getSourceEnd() + 1, vis, p);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    int at = m.getType() != null && m.getType().getPosition().isValidPosition()
                            ? m.getType().getPosition().getSourceStart() : site.start();
                    for (CtExtendedModifier em : m.getExtendedModifiers()) {
                        if (em.getPosition().isValidPosition()) {
                            at = Math.min(at, em.getPosition().getSourceStart());
                        }
                    }
                    edits.insert(at, vis + " ", p);
                }
            }
            p.imports().forEach(this::ensureRuleImport);
        }

        // -------------------------------------------------------------------------------------------- todos

        void todo(Site site, String reason, String detail) {
            if (!opts.platformTodos() && ("no-rule".equals(reason) || "unresolved-type".equals(reason))
                    && !ruleRelated(detail)) {
                return;
            }
            todoSites.add(site.element());
            todos.add(new PendingTodo(site, anchorOf(site.element()), reason, detail.replaceAll("[\\r\\n]+", " ")));
        }

        /** Rule-specific failures are always reported, even with platform TODOs off (rule tests). */
        boolean ruleRelated(String detail) {
            return detail.contains("(r-") || detail.startsWith("r-");
        }

        CtElement anchorOf(CtElement e) {
            CtElement cur = e;
            while (cur != null) {
                if (cur instanceof CtImport) {
                    return cur;
                }
                boolean valid = cur.getPosition().isValidPosition() && cur.getPosition().getCompilationUnit() == cu;
                if (valid && cur instanceof CtStatement && cur.isParentInitialized() && cur.getParent() instanceof CtStatementList) {
                    return cur;
                }
                if (valid && (cur instanceof CtTypeMember || cur instanceof CtType)) {
                    return cur;
                }
                cur = cur.isParentInitialized() ? cur.getParent() : null;
            }
            return null;
        }

        int anchorOffset(CtElement anchor, Site site) {
            int start = site.start();
            if (anchor != null && anchor.getPosition().isValidPosition()) {
                SourcePosition p = anchor.getPosition();
                start = p.getSourceStart();
                if (p instanceof DeclarationSourcePosition dsp && !(p instanceof BodyHolderSourcePosition)) {
                    start = Math.min(start, dsp.getModifierSourceStart() > 0 ? dsp.getModifierSourceStart() : start);
                }
                // Annotations come first on declarations: put the TODO above them.
                for (CtAnnotation<?> a : anchor.getAnnotations()) {
                    if (a.getPosition().isValidPosition()) {
                        start = Math.min(start, a.getPosition().getSourceStart());
                    }
                }
            }
            return lineStart(Math.min(start, site.start()));
        }

        boolean insideOpaque(int s, int e) {
            for (Map.Entry<TextEdits.Edit, Prepared> en : ruleEdits.entrySet()) {
                TextEdits.Edit ed = en.getKey();
                if (ed.start() <= s && e <= ed.end() && !(ed.start() == s && ed.end() == e)) {
                    boolean inRef = false;
                    for (int[] r : editRefs.getOrDefault(ed, List.of())) {
                        if (r[0] <= s && e <= r[1]) {
                            inRef = true;
                            break;
                        }
                    }
                    if (!inRef) {
                        return true;
                    }
                }
            }
            return false;
        }

        FileResult finish() {
            if (!addedImports.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (importInsertAt == 0 && !text.isEmpty() && cu.getImports().isEmpty()) {
                    // no package and no imports: imports go first
                }
                for (String imp : addedImports) {
                    sb.append("import ").append(imp).append(';').append(nl);
                }
                edits.insert(importInsertAt, sb.toString(), "imports");
            }
            // TODO lines, grouped by insertion offset, sorted and de-duplicated.
            Map<Integer, Set<String>> lines = new TreeMap<>();
            List<Todo> reported = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (PendingTodo t : todos) {
                if (insideOpaque(t.site().start(), t.site().end())) {
                    continue; // the code it talks about was replaced by a rule
                }
                int at = anchorOffset(t.anchorSource(), t.site());
                at = relocateOutOfEdits(at);
                String line = "// TODO(porter:" + t.reason() + ") " + t.detail();
                lines.computeIfAbsent(at, k -> new TreeSet<>()).add(line);
                if (seen.add(at + "|" + line)) {
                    reported.add(new Todo(file.path(), lineOf(at), t.reason(), t.detail()));
                }
            }
            for (Map.Entry<Integer, Set<String>> en : lines.entrySet()) {
                String indent = indentAt(en.getKey());
                StringBuilder sb = new StringBuilder();
                for (String l : en.getValue()) {
                    sb.append(indent).append(l).append(nl);
                }
                edits.insert(en.getKey(), sb.toString(), "todo");
            }
            TextEdits.Result res = edits.apply();
            Map<String, Integer> applied = new TreeMap<>();
            Set<TextEdits.Edit> lost = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            lost.addAll(res.conflicts());
            lost.addAll(res.dropped());
            for (Map.Entry<TextEdits.Edit, Prepared> en : ruleEdits.entrySet()) {
                if (!lost.contains(en.getKey())) {
                    applied.merge(en.getValue().id(), 1, Integer::sum);
                    noteShim(en.getValue());
                }
            }
            String out = opts.keepTargetMarks() ? res.text() : Template.stripMarks(res.text());
            if (!res.conflicts().isEmpty()) {
                // Re-run without the conflicting edits is not needed: they were simply not applied. Report them.
                for (TextEdits.Edit c : res.conflicts()) {
                    if (c.tag() instanceof Prepared p) {
                        reported.add(new Todo(file.path(), lineOf(c.start()), "conflict", p.id() + " overlaps another rewrite"));
                    }
                }
                if (res.conflicts().stream().anyMatch(c -> c.tag() instanceof Prepared)) {
                    out = insertConflictTodos(out, res.conflicts());
                }
            }
            reported.sort(Comparator.comparingInt(Todo::line).thenComparing(Todo::reason).thenComparing(Todo::detail));
            return new FileResult(file.path(), out, applied, reported, !out.equals(text));
        }

        /** Moves an insertion point out of replaced (non-placeholder) regions, to the start of the replaced line. */
        int relocateOutOfEdits(int at) {
            for (int guard = 0; guard < 8; guard++) {
                boolean moved = false;
                for (Map.Entry<TextEdits.Edit, Prepared> en : ruleEdits.entrySet()) {
                    TextEdits.Edit ed = en.getKey();
                    if (ed.start() < at && at < ed.end()) {
                        boolean inRef = false;
                        for (int[] r : editRefs.getOrDefault(ed, List.of())) {
                            if (r[0] < at && at < r[1]) {
                                inRef = true;
                                break;
                            }
                        }
                        if (!inRef) {
                            at = lineStart(ed.start());
                            moved = true;
                        }
                    }
                }
                if (!moved) {
                    break;
                }
            }
            return at;
        }

        String insertConflictTodos(String out, List<TextEdits.Edit> conflicts) {
            // Conflicts are rare (overlapping source positions); append them as a trailing note to stay safe.
            StringBuilder sb = new StringBuilder(out);
            for (TextEdits.Edit c : conflicts) {
                if (c.tag() instanceof Prepared p) {
                    sb.append("// TODO(porter:conflict) ").append(p.id()).append(" at line ").append(lineOf(c.start()))
                            .append(" overlaps another rewrite").append(nl);
                }
            }
            return sb.toString();
        }

        void noteShim(Prepared p) {
            String shimPkg = opts.shimPackage() == null ? RuleBook.SHIM_PACKAGE : opts.shimPackage();
            List<String> refs = new ArrayList<>(p.imports());
            refs.add(p.replaceText());
            for (String r : refs) {
                for (String cls : List.of("BlockPos", "Direction", "Worlds")) {
                    if (r.contains(shimPkg + "." + cls)) {
                        shimUsed.add(cls);
                    }
                }
            }
        }
    }
}
