package dev.porter.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.ErrorClass;
import dev.porter.loop.javac.ErrorClassifier;
import dev.porter.loop.javac.JavacDiagnostic;
import dev.porter.loop.javac.JavacOutputParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
    private static final String GRADLE = """
            > Task :compileJava FAILED
            C:\\work\\mod\\src\\main\\java\\com\\example\\A.java:5: error: cannot find symbol
                    tag.putInt("energy", 1);
                       ^
              symbol:   method putInt(String,int)
              location: variable tag of type NBTTagCompound
            C:\\work\\mod\\src\\main\\java\\com\\example\\A.java:9: error: method getTileEntity in class World cannot be applied to given types;
                    return world.getTileEntity(pos);
                                ^
              required: int,int,int
              found:    BlockPos
              reason: actual and formal argument lists differ in length
            C:\\work\\mod\\src\\main\\java\\com\\example\\B.java:3: warning: [deprecation] foo() in Bar has been deprecated
                    foo();
                    ^
            /home/u/mod/src/main/java/com/example/C.java:12: error: ';' expected
                    int x = 1
                             ^
            Note: Some input files use unchecked or unsafe operations.
            * What went wrong:
            Execution failed for task ':compileJava'.
            > Compilation failed; see the compiler output below.
              C:\\work\\mod\\src\\main\\java\\com\\example\\A.java:5: error: cannot find symbol
                      tag.putInt("energy", 1);
                         ^
                symbol:   method putInt(String,int)
                location: variable tag of type NBTTagCompound
            3 errors
            1 warning

            FAILURE: Build failed with an exception.
            """;

    @Test
    void parsesGradleOutput() {
        List<JavacDiagnostic> ds = JavacOutputParser.parse(GRADLE);
        assertEquals(4, ds.size());
        JavacDiagnostic d = ds.get(0);
        assertEquals("C:\\work\\mod\\src\\main\\java\\com\\example\\A.java", d.file());
        assertEquals(5, d.line());
        assertEquals("cannot find symbol", d.message());
        assertEquals("method putInt(String,int)", d.detail("symbol"));
        assertEquals("variable tag of type NBTTagCompound", d.detail("location"));
        assertEquals(12, d.column());
        assertEquals("BlockPos", ds.get(1).detail("found"));
        assertEquals("warning", ds.get(2).severity());
        assertEquals(0, ds.get(3).details().size());

        List<ClassifiedError> es = new ErrorClassifier().classifyAll(ds);
        assertEquals(3, es.size());
        assertEquals(ErrorClass.MISSING_METHOD, es.get(0).errorClass());
        assertEquals("NBTTagCompound#putInt(String,int)", es.get(0).symbol());
        assertEquals(List.of("String", "int"), es.get(0).argTypes());
        assertEquals(ErrorClass.SIGNATURE_MISMATCH, es.get(1).errorClass());
        assertEquals("World#getTileEntity(int,int,int)", es.get(1).symbol());
        assertEquals(List.of("BlockPos"), es.get(1).argTypes());
        assertEquals(ErrorClass.JAVA_SYNTAX, es.get(2).errorClass());
    }

    @Test
    void parsesRawDiagnostics() {
        String raw = """
                A.java:3:9: compiler.err.cant.resolve.location: kindname.class, CompoundTag, , , (compiler.misc.location: kindname.class, com.example.A, null)
                A.java:7:12: compiler.err.cant.resolve.location.args: kindname.method, putInt, , java.lang.String,int, (compiler.misc.location.1: kindname.variable, tag, com.example.NBTTagCompound)
                A.java:9:5: compiler.err.method.does.not.override.superclass
                A.java:11:18: compiler.err.prob.found.req: (compiler.misc.inconvertible.types: java.lang.String, int)
                A.java:12:14: compiler.err.expected: ';'
                5 errors
                """;
        List<ClassifiedError> es = new ErrorClassifier().classifyAll(JavacOutputParser.parse(raw));
        assertEquals(5, es.size());
        assertEquals(ErrorClass.MISSING_CLASS, es.get(0).errorClass());
        assertEquals("CompoundTag", es.get(0).symbol());
        assertEquals(ErrorClass.MISSING_METHOD, es.get(1).errorClass());
        assertEquals("putInt", es.get(1).member());
        assertEquals(ErrorClass.NO_OVERRIDE, es.get(2).errorClass());
        assertEquals(ErrorClass.INCOMPATIBLE_TYPES, es.get(3).errorClass());
        assertEquals(ErrorClass.JAVA_SYNTAX, es.get(4).errorClass());
        assertEquals("compiler.err.expected", es.get(4).code());
    }

    @Test
    void jdk8Wording() {
        String out = """
                src/A.java:4: error: class, interface, or enum expected
                }
                ^
                src/A.java:2: error: cannot access Tessellator
                  class file for net.minecraft.client.renderer.Tessellator not found
                """;
        List<ClassifiedError> es = new ErrorClassifier().classifyAll(JavacOutputParser.parse(out));
        assertEquals(ErrorClass.JAVA_SYNTAX, es.get(0).errorClass());
        assertEquals(ErrorClass.MISSING_CLASS, es.get(1).errorClass());
        assertEquals("net.minecraft.client.renderer.Tessellator", es.get(1).symbol());
        assertNull(es.get(1).diagnostic().sourceLine());
    }
}
