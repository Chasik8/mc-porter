package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.port.config.PortConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortConfigTest {
    @TempDir
    Path tmp;

    static Path repo() {
        return Path.of(System.getProperty("porter.repoRoot", "..")).toAbsolutePath().normalize();
    }

    @Test
    void examplesValidateAndLoad() {
        Path ex = repo().resolve("contracts/examples/port-config/valid/delta-all-stages.port.yaml");
        assertTrue(ContractValidator.get().validateFile(ex, Contract.PORT_CONFIG).valid());
        PortConfig c = PortConfig.load(ex);
        assertEquals("hostaddon-steam", c.name());
        assertEquals("1.19.2", c.fromBare());
        assertEquals(c.dir().resolve("build/port-steam").normalize(), c.work());
        assertTrue(c.root().path("compile").path("processorpath").get(1).asString().startsWith("/home/me/porter-cache/libs/"));
        PortRunner r = new PortRunner(c, System.out);
        assertEquals(PortRunner.STAGES, r.enabled());
        assertEquals(List.of("closure", "upfront", "rewrite"), r.plan("closure", "rewrite", null, false));
        assertEquals(List.of("install"), r.plan(null, null, "install", false));
        assertThrows(IllegalArgumentException.class, () -> r.plan("bogus", null, null, false));
    }

    @Test
    void variablesComeFromVarsThenEnvironmentAndMustExist() {
        Text.write(tmp.resolve("a.port.yaml"), "schemaVersion: 1\nname: a\nmode: full\nfrom: forge-1.19.2\nto: forge-1.7.10\n"
                + "vars: { BASE: x, DEEP: \"${BASE}/y\" }\nproject: \"${DEEP}/p\"\n");
        assertEquals(tmp.resolve("x/y/p"), PortConfig.load(tmp.resolve("a.port.yaml")).project());
        Text.write(tmp.resolve("b.port.yaml"), "schemaVersion: 1\nname: b\nmode: full\nfrom: forge-1.19.2\nto: forge-1.7.10\nproject: \"${PORTER_SURELY_UNDEFINED_VAR}\"\n");
        assertThrows(IllegalArgumentException.class, () -> PortConfig.load(tmp.resolve("b.port.yaml")));
    }
}
