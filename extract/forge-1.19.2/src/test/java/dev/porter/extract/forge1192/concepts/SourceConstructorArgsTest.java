package dev.porter.extract.forge1192.concepts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.porter.extract.forge1192.interp.V;
import java.util.List;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.visitor.filter.TypeFilter;

/** Constructor arguments of registered objects as repeatable source (porter-extract:newArgs). Synthetic code. */
class SourceConstructorArgsTest {

    static List<String> args(String expr) {
        CtClass<?> c = Launcher.parseClass("""
                package org.demo;
                class Reg {
                    enum Kind { HOT, COLD }
                    static class Drinks { static final Object TEA = new Object(); }
                    static class Cup { Cup(Object... a) {} }
                    static int local() { return 1; }
                    Object make() { int n = 2; return EXPR; }
                }
                """.replace("EXPR", expr));
        CtConstructorCall<?> call = c.getElements(new TypeFilter<>(CtConstructorCall.class)).stream()
                .filter(x -> x.getType().getSimpleName().equals("Cup")).findFirst().orElseThrow();
        V.Obj o = new V.Obj(c.getNestedType("Cup"));
        o.site = call;
        return Vals.sourceConstructorArgs(o);
    }

    @Test
    void literalsStaticFieldsEnumsAndNestedNew() {
        assertEquals(List.of("\"cup_tea\"", "org.demo.Reg.Drinks.TEA"), args("new Cup(\"cup_tea\", Drinks.TEA)"));
        assertEquals(List.of("org.demo.Reg.Kind.COLD", "3", "true", "new org.demo.Reg.Cup(1.5F)"),
                args("new Cup(Kind.COLD, 3, true, new Cup(1.5F))"));
        assertEquals(List.of(), args("new Cup()"));
    }

    @Test
    void notRepeatable() {
        assertNull(args("new Cup(n)"), "local variable");
        assertNull(args("new Cup(local())"), "method call");
        assertNull(args("new Cup((Object) Drinks.TEA)"), "cast");
    }
}
