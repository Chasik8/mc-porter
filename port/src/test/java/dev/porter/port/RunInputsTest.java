package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.json.Json;
import dev.porter.port.config.PortConfig;
import dev.porter.port.start.Fail;
import dev.porter.port.start.Inputs;
import dev.porter.port.start.Net;
import dev.porter.port.start.TypeTable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunInputsTest {
    @TempDir
    Path tmp;

    private final PrintStream sink = new PrintStream(new ByteArrayOutputStream());

    private Inputs.Spec spec(Path file, boolean optional, String sha) throws Exception {
        String json = "{\"name\":\"original\",\"mod\":\"Example\",\"version\":\"1.0\",\"page\":\"https://example.org/dl\","
                + "\"file\":\"" + file.getFileName() + "\",\"sha256\":\"" + sha + "\",\"optional\":" + optional + "}";
        return Inputs.Spec.of(Json.json().readTree(json));
    }

    @Test
    void validAndInvalidExamples() {
        Path base = Path.of(System.getProperty("porter.repoRoot", "..")).toAbsolutePath().normalize().resolve("contracts/examples/port-config");
        assertTrue(ContractValidator.get().validateFile(base.resolve("valid/with-inputs.port.yaml"), Contract.PORT_CONFIG).valid());
        assertFalse(ContractValidator.get().validateFile(base.resolve("invalid/inputs-bad-hash-and-page.port.yaml"), Contract.PORT_CONFIG).valid());
    }

    @Test
    void correctFileIsAccepted() throws Exception {
        Path f = tmp.resolve("mod-1.0.jar");
        Files.writeString(f, "content");
        var found = Inputs.resolve(List.of(spec(f, false, Net.sha256(f))), Map.of("original", f), List.of(tmp), tmp.resolve("dl"), tmp.resolve("un"), sink);
        assertEquals(f, found.get("original").file());
    }

    @Test
    void wrongChecksumNamesPageAndFile() throws Exception {
        Path f = tmp.resolve("mod-1.0.jar");
        Files.writeString(f, "content");
        Fail e = assertThrows(Fail.class, () -> Inputs.resolve(List.of(spec(f, false, "0".repeat(64))), Map.of("original", f),
                List.of(tmp), tmp.resolve("dl"), tmp.resolve("un"), sink));
        assertTrue(e.getMessage().contains("https://example.org/dl"));
        assertTrue(e.getMessage().contains("mod-1.0.jar"));
        assertTrue(e.getMessage().contains("not the expected"));
    }

    @Test
    void missingFileIsExplainedUnlessOptional() throws Exception {
        Path f = tmp.resolve("absent-1.0.jar");
        Fail e = assertThrows(Fail.class, () -> Inputs.resolve(List.of(spec(f, false, "0".repeat(64))), Map.of(), List.of(tmp.resolve("inputs")),
                tmp.resolve("dl"), tmp.resolve("un"), sink));
        assertTrue(e.getMessage().contains("is missing"));
        assertTrue(e.getMessage().contains("https://example.org/dl"));
        assertTrue(Inputs.resolve(List.of(spec(f, true, "0".repeat(64))), Map.of(), List.of(tmp), tmp.resolve("dl"), tmp.resolve("un"), sink).isEmpty());
    }

    @Test
    void extraVariablesReachTheConfig() {
        Text.write(tmp.resolve("a.port.yaml"), "schemaVersion: 1\nname: a\nmode: full\nfrom: forge-1.19.2\nto: forge-1.7.10\nproject: \"${RUN_DIR}/project\"\n");
        PortConfig c = PortConfig.load(tmp.resolve("a.port.yaml"), Map.of("RUN_DIR", tmp.resolve("run").toString()));
        assertEquals(tmp.resolve("run").resolve("project"), c.project());
    }

    @Test
    void typeTableReadsClassHeaders() throws Exception {
        Path jar = tmp.resolve("t.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("java/lang/Object.class"));
            try (var in = Object.class.getResourceAsStream("/java/lang/Object.class")) {
                z.write(in.readAllBytes());
            }
            z.closeEntry();
            z.putNextEntry(new ZipEntry("dev/porter/port/Text.class"));
            try (var in = Text.class.getResourceAsStream("Text.class")) {
                z.write(in.readAllBytes());
            }
            z.closeEntry();
        }
        Path out = tmp.resolve("facts.tsv");
        assertEquals(1, TypeTable.write(out, List.of(jar), List.of("dev.porter.")));
        assertTrue(Files.readString(out).contains("dev.porter.port.Text\tjava.lang.Object\t"));
    }

    @Test
    void catalogTableListsPorts() {
        var cat = Json.yaml().readTree("ports:\n  - {id: x, mod: {name: X, version: '1', url: 'https://x.org'}, from: forge-1.19.2, to: forge-1.7.10,"
                + " mode: full, status: scenarios, scenarioPassRate: 1.0, licence: MIT, distributable: true, profile: ports/x}\n");
        String t = CatalogCommand.table(cat);
        assertTrue(t.contains("| [X](https://x.org) | 1 | 1.19.2 → 1.7.10 | full | scenarios | 100% | MIT | yes | [x](x/README.md) |"));
    }
}
