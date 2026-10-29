package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConceptsTest {
    private static final String YAML = """
            schemaVersion: 1
            concepts:
              - type: a/Pos
                plan: EXPAND
                shim: s.Pos
                param: {desc: III, expand: ["$a.x()", "$a.y()", "$a.z()"]}
              - type: a/Hand
                plan: EXPAND
                param: {desc: "", expand: []}
              - type: a/State
                plan: EXPAND
                param: {desc: "Lt/Block;I", expand: ["$a.block()", "$a.meta()"]}
                ret: {desc: "Lt/Block;", wrap: "State.of($e)", imports: [s.State]}
              - type: a/Result
                plan: EXPAND
                ret: {desc: Z, wrap: "Result.of($e)", imports: [s.Result]}
              - type: a/Key
                plan: EXPAND
                whenSignature: "Key<La/Level;>"
                param: {desc: I, expand: ["$a.id()"]}
              - type: a/Level
                plan: EXPAND
                param: {desc: "Lt/World;", expand: ["$a"]}
            types:
              a.Prop: {plan: MIRROR, shim: s.Prop}
            """;

    private final Concepts c = Concepts.parse(YAML);

    @Test
    void positionIsUnfolded() {
        assertEquals("(III)V", c.conceptDesc("(La/Pos;)V", Map.of()));
        assertEquals("(IIII)V", c.conceptDesc("(IIII)V", Map.of()));
        assertEquals("(IIIZ)V", c.conceptDesc("(La/Pos;Z)V", Map.of()));
    }

    @Test
    void droppedParameterDisappears() {
        assertEquals("(I)V", c.conceptDesc("(La/Hand;I)V", Map.of()));
        assertEquals("()V", c.conceptDesc("(La/Hand;)V", Map.of()));
    }

    @Test
    void resultIsWrapped() {
        assertEquals("(Lt/Block;I)Lt/Block;", c.conceptDesc("(La/State;)La/State;", Map.of()));
        assertEquals("()Z", c.conceptDesc("()La/Result;", Map.of()));
        // a parameter of a type with only a result side is not a concept
        assertEquals("(La/Result;)V", c.conceptDesc("(La/Result;)V", Map.of()));
    }

    @Test
    void arraysAreUntouchedAndAHint() {
        assertEquals("([La/Pos;)V", c.conceptDesc("([La/Pos;)V", Map.of()));
        assertTrue(c.hasConceptArray("([La/Pos;)V"));
        assertTrue(!c.hasConceptArray("(La/Pos;)V"));
    }

    @Test
    void classMapAppliesToOtherTypes() {
        assertEquals("(Lt/Thing;III)Lt/Other;", c.conceptDesc("(La/Thing;La/Pos;)La/Other;", Map.of("a.Thing", "t.Thing", "a.Other", "t.Other")));
        assertEquals("[Lt/Thing;", c.conceptDesc("[La/Thing;", Map.of("a.Thing", "t.Thing")));
    }

    @Test
    void fieldTypeTakesResultSideWithoutExpansion() {
        assertEquals("Lt/Block;", c.conceptDesc("La/State;", Map.of()));
        assertEquals("La/Pos;", c.conceptDesc("La/Pos;", Map.of()));
    }

    @Test
    void conditionalConceptNeedsTheGenericSignature() {
        String d = "(La/Key;)V";
        assertEquals("(I)V", c.conceptDesc(d, Map.of())); // permissive form
        assertNull(c.conceptDesc(d, Map.of(), null)); // unknown signature: a hint
        assertEquals("(I)V", c.conceptDesc(d, Map.of(), "(La/Key<La/Level;>;)V"));
        assertEquals("(La/Key;)V", c.conceptDesc(d, Map.of(), "(La/Key<La/Item;>;)V"));
    }

    @Test
    void expressionsExpandAndWrap() {
        assertEquals(List.of("p.x()", "p.y()", "p.z()"), c.expand("a.Pos", "p"));
        assertEquals(List.of("(q ? p : r).x()", "(q ? p : r).y()", "(q ? p : r).z()"), c.expand("a/Pos", "q ? p : r"));
        assertEquals(List.of(), c.expand("a.Hand", "InteractionHand.MAIN_HAND"));
        assertEquals(List.of("lvl"), c.expand("a.Level", "lvl"));
        assertEquals("State.of(w.get(p))", c.wrap("a.State", "w.get(p)"));
        assertEquals(List.of("s.State"), c.imports("a.State", true));
        assertEquals(3, c.argUses("a.Pos"));
    }

    @Test
    void conceptsInReportsTheUsedConcepts() {
        assertEquals(List.of("a/Pos", "a/Hand", "a/State"), c.conceptsIn("(La/Pos;La/Hand;)La/State;"));
        assertEquals(List.of(), c.conceptsIn("(La/Result;)V"));
    }

    @Test
    void shimCheckFindsMissingHelpers(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("s"));
        Files.writeString(dir.resolve("s/Pos.java"), "package s; class Pos { int x(){return 0;} int y(){return 0;} }");
        Files.writeString(dir.resolve("s/State.java"), "package s; class State { static State of(Object o){return null;} }");
        List<String> problems = c.check(List.of(dir));
        assertTrue(problems.stream().anyMatch(p -> p.contains("no method z")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("s.Result")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("s.Prop")), problems.toString());
        assertEquals(3, problems.size(), problems.toString());
    }
}
