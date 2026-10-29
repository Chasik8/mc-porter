package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Acceptance on real data; runs only when UPFRONT_ACCEPT_DIR names a directory with {@code lineage.tsv},
 * {@code target_api.tsv} and symbol lists {@code addon-table.tsv} / {@code whole-table.tsv}
 * (UPFRONT_ACCEPT_RULES: rule directories separated by the path separator). Prints the numbers.
 */
class ConceptAcceptanceTest {
    private static final Path DATA = Path.of("..").toAbsolutePath().normalize().resolve("rules/upfront/1.19.2__1.7.10/concepts.yaml");

    @Test
    void realDataNumbers() {
        String dir = System.getenv("UPFRONT_ACCEPT_DIR");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "UPFRONT_ACCEPT_DIR not set");
        List<Path> rules = new ArrayList<>();
        String r = System.getenv("UPFRONT_ACCEPT_RULES");
        if (r != null) {
            for (String p : r.split(java.io.File.pathSeparator)) {
                rules.add(Path.of(p));
            }
        }
        Concepts c = Concepts.load(DATA);
        for (String scope : new String[] {"addon", "whole"}) {
            Path symbols = Path.of(dir, scope + "-table.tsv");
            if (!Files.isRegularFile(symbols)) {
                continue;
            }
            ConceptAcceptance.Result res = ConceptAcceptance.run(c, Path.of(dir, "lineage.tsv"), symbols, Path.of(dir, "target_api.tsv"), rules);
            System.out.println("ACCEPT " + scope + ": types " + res.types() + " withoutPlan " + res.typesWithoutPlan()
                    + " plan " + res.planByMethod() + " sigChanged " + res.signatureChanged() + " explained " + res.explained()
                    + " hits " + res.conceptHits());
            assertEquals(0, res.typesWithoutPlan());
            org.junit.jupiter.api.Assertions.assertTrue(res.explained() >= (scope.equals("addon") ? 40 : 50),
                    scope + " explained " + res.explained());
        }
    }
}
