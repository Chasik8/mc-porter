package dev.porter.rewrite.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CfrLambdasTest {
    @Test
    void capturedReceiverAndLocal() {
        String in = "add(new Box(1, (Button.OnPress)(Button.OnPress)LambdaMetafactory.metafactory(null, null, null, "
                + "(Lnet/mc/Button;)V, lambda$init$0(int net.mc.Button ), (Lnet/mc/Button;)V)((Comp)this, (int)index), \"a,)\")).visible = on;";
        assertEquals("add(new Box(1, (Button.OnPress)(Button.OnPress)((cfr$0) -> ((Comp)this).cfr$lambda$init$0((int)index, cfr$0)), \"a,)\")).visible = on;",
                CfrLambdas.fix(in));
    }

    @Test
    void methodReferenceOnThis() {
        String in = "x((Cb)LambdaMetafactory.metafactory(null, null, null, (Ljava/lang/Object;)V, toggle(a.b.ColorButton ), "
                + "(La/b/ColorButton;)V)((W)this));";
        assertEquals("x((Cb)((cfr$0) -> ((W)this).toggle(cfr$0)));", CfrLambdas.fix(in));
    }

    @Test
    void staticNoArgsAndTwoSamParams() {
        assertEquals("private static void cfr$lambda$run$1() {}\nr = (R)(() -> cfr$lambda$run$1());",
                CfrLambdas.fix("private static void lambda$run$1() {}\nr = (R)LambdaMetafactory.metafactory(null, null, null, ()V, lambda$run$1(), ()V)();"));
        assertEquals("r = (R)(() -> cfr$lambda$run$1());",
                CfrLambdas.fix("r = (R)LambdaMetafactory.metafactory(null, null, null, ()V, lambda$run$1(), ()V)();"));
        assertEquals("f = (F)((cfr$0, cfr$1) -> cfr$lambda$f$2((long)k, cfr$0, cfr$1));",
                CfrLambdas.fix("f = (F)LambdaMetafactory.metafactory(null, null, null, (I[Ljava/lang/String;)Z, lambda$f$2(long int java.lang.String[] ), (I[Ljava/lang/String;)Z)((long)k);"));
    }

    @Test
    void unresolvableLeftAlone() {
        String ctor = "s = (S)LambdaMetafactory.metafactory(null, null, null, ()Ljava/lang/Object;, <init>(), ()La/B;)();";
        assertEquals(ctor, CfrLambdas.fix(ctor));
        assertEquals("no call here", CfrLambdas.fix("no call here"));
    }
}
