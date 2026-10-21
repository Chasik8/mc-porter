package dev.porter.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChainTest {
    @Test
    void fullChainJoinsNeighbours() {
        List<Step> steps = Chain.steps(AssetVersion.V1_19_2, AssetVersion.V1_7_10);
        assertEquals(AssetVersion.values().length - 1, steps.size());
        for (int i = 0; i < steps.size(); i++) {
            assertEquals(AssetVersion.values()[i], steps.get(i).from());
            assertEquals(AssetVersion.values()[i + 1], steps.get(i).to());
            assertTrue(steps.get(i).from().packFormat >= steps.get(i).to().packFormat);
        }
    }

    @Test
    void partialChainsAndErrors() {
        assertEquals(1, Chain.steps(AssetVersion.V1_8_9, AssetVersion.V1_7_10).size());
        assertEquals(0, Chain.steps(AssetVersion.V1_12_2, AssetVersion.V1_12_2).size());
        assertThrows(IllegalArgumentException.class, () -> Chain.steps(AssetVersion.V1_7_10, AssetVersion.V1_19_2));
        assertEquals(AssetVersion.V1_19_2, AssetVersion.of("forge-1.19.2").orElseThrow());
        assertTrue(AssetVersion.of("1.20.1").isEmpty());
    }

    @Test
    void cliRejectsUnsupportedPairs() {
        System.setProperty("porter.reports.dir", System.getProperty("java.io.tmpdir") + "/porter-assets-test-reports");
        int code = new picocli.CommandLine(new AssetsCommand()).execute("--src", ".", "--from", "1.7.10", "--to", "1.19.2", "--out", "x");
        assertEquals(2, code);
    }
}
