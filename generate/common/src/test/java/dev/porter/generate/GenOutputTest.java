package dev.porter.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeSpec;
import java.util.List;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.Test;

class GenOutputTest {
    private static TypeSpec stub(String name, String base) {
        return TypeSpec.classBuilder(name).addModifiers(Modifier.PUBLIC).superclass(ClassName.get("org.demo.gen", base)).build();
    }

    @Test
    void oneLogicClassForSeveralNodesWithDifferentStubsGetsNoStub() {
        // before: five menus sharing one container class (each with its own generated base) failed generate with
        // "two different contents for .../ColossalMachineContainer.java"; after: no stub, the roles are reported
        GenOutput out = new GenOutput();
        out.addStub("org.demo", stub("SharedContainer", "MaceratorMenuBase"), "menu demo:macerator");
        out.addStub("org.demo", stub("SharedContainer", "FurnaceMenuBase"), "menu demo:furnace");
        out.addStub("org.demo", stub("SharedContainer", "ExtractorMenuBase"), "menu demo:extractor");
        assertFalse(out.files().containsKey("src/main/java/org/demo/SharedContainer.java"));
        assertFalse(out.stubs().containsKey("org.demo.SharedContainer"));
        assertEquals(List.of("menu demo:macerator", "menu demo:furnace", "menu demo:extractor"), out.sharedStubs().get("org.demo.SharedContainer"));
    }

    @Test
    void identicalStubsAreWrittenOnce() {
        GenOutput out = new GenOutput();
        out.addStub("org.demo", stub("Lamp", "LampBase"), "block demo:lamp");
        out.addStub("org.demo", stub("Lamp", "LampBase"), "block demo:lamp");
        assertTrue(out.files().containsKey("src/main/java/org/demo/Lamp.java"));
        assertTrue(out.sharedStubs().isEmpty());
    }

    @Test
    void generatedFileClashStillFailsWithAnExplanation() {
        GenOutput out = new GenOutput();
        out.put("src/main/resources/a.txt", "one");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> out.put("src/main/resources/a.txt", "two"));
        assertTrue(e.getMessage().contains("two IR nodes generate the same file differently"), e.getMessage());
    }
}
