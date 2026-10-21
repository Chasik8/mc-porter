package dev.porter.extract.forge1192;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.JdkBuiltins;
import dev.porter.extract.forge1192.interp.McBuiltins;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.model.SourceModel;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/** The extraction interpreter computes what is computable and never picks a branch it cannot decide. */
class InterpreterTest {
    @TempDir
    static Path dir;
    static Interpreter in;
    static CtType<?> t;

    @BeforeAll
    static void setUp() throws Exception {
        Path src = dir.resolve("t/Sample.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package t;
                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import net.minecraft.core.BlockPos;
                import net.minecraft.core.Direction;
                public class Sample {
                    static final int BASE = 8;
                    static Object config;
                    enum Kind { SMALL(1), LARGE(4); final int size; Kind(int s) { size = s; } int twice() { return size * 2; } }
                    static int arith() { int s = 0; for (int i = 0; i < 9; i++) { if (i % 2 == 0) continue; s += BASE + i * 18; } return s; }
                    static String concat() { String r = "a"; for (Kind k : Kind.values()) r = r + "_" + k.name().toLowerCase() + k.twice(); return r; }
                    static int undecidable() { int x = 1; if (config != null && config.hashCode() > 2) { x = 2; } return x; }
                    static int undecidableReturn() { if (config.equals("x")) { return 1; } return 2; }
                    static Object lambda() { java.util.function.IntUnaryOperator f = v -> v * v + BASE; return f.applyAsInt(3); }
                    static Object map() { Map<String, Integer> m = new HashMap<>(); m.put("a", 1); m.put("b", 2); m.put("a", 3); return m.get("a") + m.size(); }
                    static Object list() { List<Integer> l = new ArrayList<>(); for (int i = 0; i < 4; i++) l.add(i * i); return l.get(3); }
                    static Object pos() { BlockPos p = new BlockPos(1, 2, 3).relative(Direction.NORTH, 2).above(); return p.getZ() * 100 + p.getY(); }
                    static Object packed() { long k = new BlockPos(-2, 5, 7).asLong(); BlockPos q = BlockPos.of(k); return q.getX() + "," + q.getY() + "," + q.getZ(); }
                    static Object clockwise() { return Direction.NORTH.getClockWise().getName(); }
                    static Object ternary(boolean b) { return b ? "yes" : "no"; }
                    static Object chars() { char c = 'A'; c++; return c; }
                    static Object division() { return 7 / 2 + 7 % 3 + (int) (2.5f * 2); }
                    interface Reg { class B { String[] s; String id; B(String n) { this("x", n); } B(String a, String b) { this.id = a + ":" + b; } B set(String... v) { this.s = v; return this; } B out(Object o, int n) { return this; } } }
                    static Object nested() { return new Reg.B("a").out("q", 1).set("x", "y").s.length; }
                    static Object nestedId() { return new Reg.B("a").id; }
                    static class Base { Object tex; Base(Object t) { this.tex = t; } }
                    static class Sub extends Base { Object tex; Sub() { super("model"); this.tex = "drops"; } }
                    static Object shadow() { return new Sub(); }
                }
                """);
        SourceModel model = SourceModel.build(List.of(dir), List.of());
        in = new Interpreter(model, VanillaIndex.empty());
        Facts facts = new Facts();
        in.addBuiltins(new McBuiltins(facts, "test"));
        in.addBuiltins(new DataBuiltins());
        in.addBuiltins(new JdkBuiltins());
        t = model.type("t.Sample");
    }

    private Object run(String name, Object... args) {
        CtMethod<?> m = t.getMethodsByName(name).get(0);
        return in.call(m, null, List.of(args));
    }

    @Test
    void arithmeticAndLoops() {
        // i = 1,3,5,7: 4*8 + 18*(1+3+5+7)
        assertEquals(32 + 18 * 16, run("arith"));
        assertEquals(9, run("list"));
        assertEquals(3 + 1 + 5, run("division"));
        assertEquals('B', run("chars"));
    }

    @Test
    void enumsStringsLambdasMaps() {
        assertEquals("a_small2_large8", run("concat"));
        assertEquals(17, run("lambda"));
        assertEquals(5, run("map"));
        assertEquals("yes", run("ternary", true));
    }

    @Test
    void nestedBuilderWithVarargs() {
        assertEquals("x:a", run("nestedId"));
        assertEquals(2, run("nested"));
    }

    @Test
    void minecraftValues() {
        assertEquals(103, run("pos"));
        assertEquals("-2,5,7", run("packed"));
        assertEquals("east", run("clockwise"));
    }

    @Test
    void hiddenSuperclassFieldKeepsItsValue() {
        V.Obj o = (V.Obj) run("shadow");
        assertEquals("drops", o.fields.get("tex"), "plain key: the subclass field");
        assertTrue(o.fields.entrySet().stream().anyMatch(e -> e.getKey().endsWith("Base#tex") && "model".equals(e.getValue())),
                o.fields.toString());
    }

    @Test
    void neverGuessesUndecidableBranches() {
        // config is null → condition decidable (false)
        assertEquals(1, run("undecidable"));
        Object r = run("undecidableReturn");
        assertInstanceOf(V.Unknown.class, r);
        assertTrue(((V.Unknown) r).reason().length() > 0);
    }
}
