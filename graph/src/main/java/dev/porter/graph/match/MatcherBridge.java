package dev.porter.graph.match;

import dev.porter.graph.model.Sym;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import matcher.Matcher;
import matcher.classifier.ClassifierLevel;
import matcher.config.ProjectConfig;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.MemberInstance;

/**
 * Bytecode matching with the FabricMC Matcher core, used headless as a library: both obfuscated client jars are
 * loaded, the name anchors are applied as fixed matches, then Matcher's classifiers (signatures, hierarchy, call
 * graph, field/method references, string and numeric constants, code similarity) extend the matching iteratively
 * (classes -> members -> classes...). Only matches Matcher adds on top of the anchors are reported as new; for the
 * anchors Matcher's similarity is recorded as extra evidence.
 */
public final class MatcherBridge {
    private static final double ABS = 0.85;
    private static final double REL = 0.085;
    private static final double ABS_LOW = 0.65;
    private static final double REL_LOW = 0.15;
    private static boolean initialised;

    private MatcherBridge() {
    }

    /** Statistics of one run. */
    public record Stats(int seededClasses, int seededMembers, int newClasses, int newMembers, int bytecodeConfirmed,
                        int bytecodeDisputed) {
    }

    public static synchronized Stats run(PairResult r, Path jarA, Path jarB, Consumer<String> log) {
        if (!initialised) {
            Matcher.init();
            initialised = true;
        }
        ClassEnvironment env = new ClassEnvironment();
        Matcher m = new Matcher(env);
        ProjectConfig cfg = new ProjectConfig.Builder(List.of(jarA), List.of(jarB))
                .classPathA(new ArrayList<>()).classPathB(new ArrayList<>()).sharedClassPath(new ArrayList<>())
                .nonObfuscatedClassPatternA("").nonObfuscatedClassPatternB("")
                .nonObfuscatedMemberPatternA("").nonObfuscatedMemberPatternB("")
                .build();
        DoubleConsumer quiet = p -> { };
        m.init(cfg, quiet);
        try {
            int seededClasses = 0;
            int seededMembers = 0;
            List<PairResult.Match> anchors = r.all();
            for (PairResult.Match x : anchors) {
                if (x.a.isClass()) {
                    ClassInstance ca = env.getLocalClsByNameA(x.a.obfName);
                    ClassInstance cb = env.getLocalClsByNameB(x.b.obfName);
                    if (ca != null && cb != null) {
                        m.match(ca, cb);
                        seededClasses++;
                    }
                }
            }
            for (PairResult.Match x : anchors) {
                if (x.a.isClass()) {
                    continue;
                }
                MemberInstance<?> ma = member(env, x.a, true);
                MemberInstance<?> mb = member(env, x.b, false);
                if (ma != null && mb != null && ma.getCls().getMatch() == mb.getCls() && ma.getMatch() != mb) {
                    if (ma.hasMatch() || mb.hasMatch()) {
                        continue; // hierarchy propagation already decided
                    }
                    m.match(ma, mb);
                    seededMembers++;
                }
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < 2; i++) {
                m.autoMatchClasses(ClassifierLevel.Initial, ABS, REL, quiet);
            }
            for (ClassifierLevel level : new ClassifierLevel[] {ClassifierLevel.Intermediate, ClassifierLevel.Full, ClassifierLevel.Extra}) {
                boolean any;
                int guard = 0;
                do {
                    any = m.autoMatchMethods(level, ABS, REL, quiet);
                    any |= m.autoMatchFields(level, ABS, REL, quiet);
                    any |= m.autoMatchClasses(level, ABS, REL, quiet);
                } while (any && ++guard < 12);
            }
            // Relaxed final pass inside already matched classes: methods whose signature changed (BlockPos -> x,y,z)
            // score lower but are usually the only plausible candidate left.
            for (int i = 0; i < 3; i++) {
                boolean any = m.autoMatchMethods(ClassifierLevel.Full, ABS_LOW, REL_LOW, quiet);
                any |= m.autoMatchFields(ClassifierLevel.Full, ABS_LOW, REL_LOW, quiet);
                any |= m.autoMatchClasses(ClassifierLevel.Full, ABS_LOW, REL_LOW, quiet);
                if (!any) {
                    break;
                }
            }
            log.accept("matcher: classifiers took " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            return collect(r, env, seededClasses, seededMembers);
        } finally {
            m.reset();
        }
    }

    private static MemberInstance<?> member(ClassEnvironment env, Sym s, boolean sideA) {
        ClassInstance c = sideA ? env.getLocalClsByNameA(s.owner.obfName) : env.getLocalClsByNameB(s.owner.obfName);
        if (c == null) {
            return null;
        }
        return s.kind == Sym.Kind.METHOD ? c.getMethod(s.obfName, s.obfDesc) : c.getField(s.obfName, s.obfDesc);
    }

    private static Stats collect(PairResult r, ClassEnvironment env, int seededClasses, int seededMembers) {
        int newClasses = 0;
        int newMembers = 0;
        int confirmed = 0;
        int disputed = 0;
        for (Sym ca : r.a.classes()) {
            ClassInstance ci = env.getLocalClsByNameA(ca.obfName);
            if (ci == null) {
                continue;
            }
            ClassInstance mi = ci.getMatch();
            PairResult.Match existing = r.ofA(ca);
            if (existing == null && mi != null) {
                Sym cb = r.b.classByObf(mi.getName());
                if (cb != null && r.add(ca, cb, conf(ci.getSimilarity()), "bytecode-similarity", "matcher")) {
                    newClasses++;
                }
            }
        }
        for (Sym ca : r.a.classes()) {
            ClassInstance ci = env.getLocalClsByNameA(ca.obfName);
            if (ci == null || ci.getMatch() == null) {
                continue;
            }
            for (Sym ma : ca.members) {
                MemberInstance<?> mi = ma.kind == Sym.Kind.METHOD ? ci.getMethod(ma.obfName, ma.obfDesc) : ci.getField(ma.obfName, ma.obfDesc);
                if (mi == null) {
                    continue;
                }
                PairResult.Match existing = r.ofA(ma);
                MemberInstance<?> mm = mi.getMatch();
                if (existing != null) {
                    if (mm != null) {
                        boolean same = mm.getCls().getName().equals(existing.b.owner.obfName) && mm.getName().equals(existing.b.obfName)
                                && mm.getDesc().equals(existing.b.obfDesc);
                        if (same) {
                            float sim = mi.getSimilarity();
                            if (sim >= 0.5f) {
                                existing.evidence.add("bytecode-similarity");
                                confirmed++;
                            }
                        } else {
                            disputed++;
                        }
                    }
                    continue;
                }
                if (mm == null) {
                    continue;
                }
                Sym cb = r.b.classByObf(mm.getCls().getName());
                if (cb == null) {
                    continue;
                }
                Sym mb = null;
                for (Sym s : cb.members) {
                    if (s.kind == ma.kind && s.obfName.equals(mm.getName()) && s.obfDesc.equals(mm.getDesc())) {
                        mb = s;
                        break;
                    }
                }
                if (mb != null && r.add(ma, mb, conf(mi.getSimilarity()), "bytecode-similarity", "matcher")) {
                    newMembers++;
                }
            }
        }
        return new Stats(seededClasses, seededMembers, newClasses, newMembers, confirmed, disputed);
    }

    private static double conf(float similarity) {
        double c = 0.55 + 0.4 * Math.max(0, Math.min(1, similarity));
        return Math.round(c * 1000) / 1000.0;
    }
}
