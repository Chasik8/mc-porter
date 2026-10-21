package dev.porter.extract.forge1192.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;

/**
 * Spoon model of the mod sources.
 *
 * <p>Decision 0007: the model is built in <b>noclasspath</b> mode by default. Types of the mod itself are fully
 * resolved (they are in the sources); Minecraft/Forge types are known by their qualified names (from imports),
 * which is all the extractors match on. An optional classpath (e.g. deobfuscated Forge jars) can be passed and
 * makes the model typed where available.
 */
public final class SourceModel {
    private final CtModel model;
    private final Factory factory;
    private final List<CtType<?>> types;
    private final Map<String, CtType<?>> byName = new HashMap<>();
    /** Simple name → qualified name of every imported type (null when two imports disagree). */
    private final Map<String, String> imported = new HashMap<>();

    private SourceModel(CtModel model, Factory factory) {
        this.model = model;
        this.factory = factory;
        List<CtType<?>> all = new ArrayList<>();
        for (CtType<?> t : model.getAllTypes()) {
            collect(t, all);
        }
        all.sort(Comparator.comparing(CtType::getQualifiedName));
        this.types = List.copyOf(all);
        java.util.Set<Object> seenUnits = new java.util.HashSet<>();
        for (CtType<?> t : model.getAllTypes()) {
            var pos = t.getPosition();
            if (pos == null || !pos.isValidPosition() || pos.getCompilationUnit() == null
                    || !seenUnits.add(pos.getCompilationUnit())) {
                continue;
            }
            for (spoon.reflect.declaration.CtImport imp : pos.getCompilationUnit().getImports()) {
                String q = null;
                if (imp instanceof spoon.experimental.CtUnresolvedImport ui && !ui.isStatic()
                        && !ui.getUnresolvedReference().endsWith("*")) {
                    q = ui.getUnresolvedReference();
                } else if (imp.getReference() instanceof spoon.reflect.reference.CtTypeReference<?> tr) {
                    q = tr.getQualifiedName();
                }
                if (q != null) {
                    String simple = q.substring(Math.max(q.lastIndexOf('.'), q.lastIndexOf('$')) + 1);
                    if (imported.containsKey(simple) && !q.equals(imported.get(simple))) {
                        imported.put(simple, null);
                    } else {
                        imported.put(simple, q);
                    }
                }
            }
        }
        for (CtType<?> t : all) {
            if (t.isTopLevel()) {
                imported.putIfAbsent(t.getSimpleName(), t.getQualifiedName());
            }
        }
    }

    private final Map<Object, Map<String, String>> unitImports = new java.util.IdentityHashMap<>();

    /** Simple name resolved through the imports (and package) of the compilation unit of {@code ctx}, then globally. */
    public String importedType(String simple, spoon.reflect.declaration.CtElement ctx) {
        try {
            var pos = ctx == null ? null : ctx.getPosition();
            var cu = pos == null || !pos.isValidPosition() ? null : pos.getCompilationUnit();
            if (cu != null) {
                Map<String, String> m = unitImports.computeIfAbsent(cu, k -> {
                    Map<String, String> r = new HashMap<>();
                    for (spoon.reflect.declaration.CtImport imp : cu.getImports()) {
                        String q = null;
                        if (imp instanceof spoon.experimental.CtUnresolvedImport ui && !ui.isStatic()
                                && !ui.getUnresolvedReference().endsWith("*")) {
                            q = ui.getUnresolvedReference();
                        } else if (imp.getReference() instanceof spoon.reflect.reference.CtTypeReference<?> tr) {
                            q = tr.getQualifiedName();
                        }
                        if (q != null) {
                            r.put(q.substring(Math.max(q.lastIndexOf('.'), q.lastIndexOf('$')) + 1), q);
                        }
                    }
                    return r;
                });
                String q = m.get(simple);
                if (q != null) {
                    return q;
                }
            }
        } catch (RuntimeException e) {
            // fall through to the global table
        }
        return importedType(simple);
    }

    /** Qualified name for a simple type name seen in imports (or a top-level mod type), else null. */
    public String importedType(String simple) {
        String q = imported.get(simple);
        if (q != null) {
            return q;
        }
        return switch (simple) {
            case "String", "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "Math",
                    "Object", "StringBuilder", "Enum", "System", "Iterable", "Class", "Number" -> "java.lang." + simple;
            default -> null;
        };
    }

    private void collect(CtType<?> t, List<CtType<?>> all) {
        all.add(t);
        byName.put(t.getQualifiedName(), t);
        for (CtType<?> n : t.getNestedTypes()) {
            collect(n, all);
        }
    }

    public static SourceModel build(List<Path> javaRoots, List<Path> classpath) {
        Launcher launcher = new Launcher();
        for (Path p : javaRoots) {
            launcher.addInputResource(p.toString());
        }
        var env = launcher.getEnvironment();
        env.setNoClasspath(true);
        env.setComplianceLevel(17);
        env.setCommentEnabled(false);
        env.setAutoImports(false);
        env.setIgnoreDuplicateDeclarations(true);
        env.setLevel("OFF");
        env.setShouldCompile(false);
        if (classpath != null && !classpath.isEmpty()) {
            env.setSourceClasspath(classpath.stream().map(Path::toString).toArray(String[]::new));
        }
        CtModel model = launcher.buildModel();
        return new SourceModel(model, launcher.getFactory());
    }

    public CtModel model() {
        return model;
    }

    public Factory factory() {
        return factory;
    }

    /** All types including nested ones, sorted by qualified name. */
    public List<CtType<?>> types() {
        return types;
    }

    /** Source type by qualified name ({@code a.b.Outer$Inner}), or null if not part of the mod. */
    public CtType<?> type(String qualifiedName) {
        return byName.get(qualifiedName);
    }
}
