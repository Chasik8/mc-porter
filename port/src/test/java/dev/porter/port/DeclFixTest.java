package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.javac.CastFix;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic fixtures (no mod code) for the decompiler-artefact fixes of {@link CastFix} / DeclFix. */
class DeclFixTest {
    @TempDir
    Path tmp;

    private String fix(String file, String source, String log, int expected) {
        Path p = tmp.resolve("src/" + file);
        Text.write(p, source);
        assertEquals(expected, CastFix.apply(log, tmp), "fixes");
        return Text.read(p);
    }

    @Test
    void lostLocalDeclarationIsInsertedBeforeTheShallowestStatement() {
        String src = "class A {\n    void f(boolean b, boolean c) {\n        if (b) {\n            g = new Gadget(this);\n        } else if (c) {\n"
                + "            g = new Gadget(this);\n        }\n        use(g);\n    }\n}\n";
        String log = "src/A.java:4: error: cannot find symbol\n            g = new Gadget(this);\n            ^\n  symbol:   variable g\n  location: class A\n"
                + "src/A.java:6: error: cannot find symbol\n            g = new Gadget(this);\n            ^\n  symbol:   variable g\n  location: class A\n"
                + "src/A.java:8: error: cannot find symbol\n        use(g);\n            ^\n  symbol:   variable g\n  location: class A\n";
        String a = fix("A.java", src, log, 1);
        assertTrue(a.contains("        Gadget g;\n        if (b) {"), a);
    }

    @Test
    void leftoverGenericNameIsDropped() {
        String src = "class B {\n    void f() {\n        List<K>[] handlers = make(6);\n    }\n}\n";
        String log = "src/B.java:3: error: cannot find symbol\n        List<K>[] handlers = make(6);\n             ^\n  symbol:   class K\n  location: class B\n";
        String b = fix("B.java", src, log, 1);
        assertTrue(b.contains("List[] handlers = make(6);"), b);
    }

    @Test
    void forEachOverObjectCastTakesTheElementTypeOfTheDeclaration() {
        String src = "class C {\n    Slot[] slots;\n    void f(int ... ids) {\n        for (Object i : (Object)ids) {\n        }\n"
                + "        for (long s : (Object)this.slots) {\n        }\n    }\n}\n";
        String log = "src/C.java:4: error: for-each not applicable to expression type\n        for (Object i : (Object)ids) {\n                        ^\n  required: array or java.lang.Iterable\n  found:    Object\n"
                + "src/C.java:6: error: for-each not applicable to expression type\n        for (long s : (Object)this.slots) {\n                      ^\n  required: array or java.lang.Iterable\n  found:    Object\n";
        String c = fix("C.java", src, log, 2);
        assertTrue(c.contains("for (int i : ids) {"), c);
        assertTrue(c.contains("for (Slot s : this.slots) {"), c);
    }

    @Test
    void objectIteratorBecomesAnIterator() {
        String src = "class D {\n    void f(Maps m) {\n        Object it = m.fast();\n        while (it.hasNext()) {\n        }\n    }\n}\n";
        String log = "src/D.java:4: error: cannot find symbol\n        while (it.hasNext()) {\n                 ^\n  symbol:   method hasNext()\n  location: variable it of type Object\n";
        String d = fix("D.java", src, log, 1);
        assertTrue(d.contains("java.util.Iterator it = m.fast();"), d);
    }

    @Test
    void objectLoopVariableUsedAsMapEntryBecomesAnEntry() {
        String src = "class E {\n    void f(Maps m) {\n        for (Object e : m.fast()) {\n            use(e.getKey());\n        }\n    }\n}\n";
        String log = "src/E.java:4: error: cannot find symbol\n            use(e.getKey());\n                 ^\n  symbol:   method getKey()\n  location: variable e of type Object\n";
        String e = fix("E.java", src, log, 1);
        assertTrue(e.contains("for (java.util.Map.Entry e : m.fast()) {"), e);
    }

    @Test
    void genericArgumentMismatchGetsARawCast() {
        String src = "class F {\n    Map<String, Map<String, Integer>> f(Impl<String, Map> map) {\n        return map;\n    }\n}\n";
        String log = "src/F.java:3: error: incompatible types: Impl<String,Map> cannot be converted to Map<String,Map<String,Integer>>\n        return map;\n               ^\n";
        String f = fix("F.java", src, log, 1);
        assertTrue(f.contains("return (Map) map;"), f);
    }

    @Test
    void arrayOfTypeVariableTargetGetsACast() {
        String src = "class G<T> {\n    T[] cache = new Object[6];\n}\n";
        String log = "src/G.java:2: error: incompatible types: Object[] cannot be converted to T[]\n    T[] cache = new Object[6];\n                ^\n";
        String g = fix("G.java", src, log, 1);
        assertTrue(g.contains("T[] cache = (T[]) new Object[6];"), g);
    }
}
