package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shipped concepts.yaml against the shim sources of this repository. */
class ConceptsDataTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DATA = ROOT.resolve("rules/upfront/1.19.2__1.7.10/concepts.yaml");

    @Test
    void shippedTableHasTheSixteenParameterConceptsAndFiveResultConcepts() {
        Concepts c = Concepts.load(DATA);
        long params = c.all().stream().filter(x -> x.param() != null).count();
        long rets = c.all().stream().filter(x -> x.ret() != null).count();
        assertTrue(params >= 16, "param concepts " + params);
        assertTrue(rets >= 5, "result concepts " + rets);
        assertEquals("(III)V", c.conceptDesc("(Lnet/minecraft/core/BlockPos;)V", Map.of()));
        assertEquals("(Lnet/minecraft/block/Block;I)Lnet/minecraft/block/Block;",
                c.conceptDesc("(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;", Map.of()));
        assertEquals("(I)Z", c.conceptDesc("(Lnet/minecraft/world/InteractionHand;I)Lnet/minecraft/world/InteractionResult;", Map.of()));
    }

    @Test
    void everyExpressionResolvesAgainstTheShims() {
        List<Path> roots = List.of(ROOT.resolve("rewrite/shim/src/main/java"), ROOT.resolve("rewrite/shim/src/adapters/java"));
        assertTrue(Files.isDirectory(roots.get(1)), "shim sources not found at " + roots.get(1));
        assertEquals(List.of(), Concepts.load(DATA).check(roots));
    }
}
