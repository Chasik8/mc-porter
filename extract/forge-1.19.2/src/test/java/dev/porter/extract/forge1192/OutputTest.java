package dev.porter.extract.forge1192;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.ContractValidator;
import dev.porter.core.contract.ValidationIssue;
import dev.porter.core.ir.IrIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Written IR is contract-valid and byte-identical across runs (rule 3). */
class OutputTest {
    @TempDir
    Path tmp;

    @Test
    void validAndDeterministic() throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Extractor.run(new Extractor.Options(Fixtures.path("examplemod"), a, null, Fixtures.vanilla(), List.of(), true,
                List.of()));
        Extractor.run(new Extractor.Options(Fixtures.path("examplemod"), b, null, Fixtures.vanilla(), List.of(), true,
                List.of()));
        List<Path> files;
        try (Stream<Path> s = Files.list(a)) {
            files = s.sorted().toList();
        }
        assertTrue(files.size() >= 13, files.toString());
        for (Path f : files) {
            if (f.getFileName().toString().endsWith(".ir.json")) {
                ContractValidator.FileResult res = ContractValidator.get().validateFile(f, null);
                List<ValidationIssue> issues = res.issues();
                assertTrue(issues.isEmpty(), f + ": " + issues);
                IrIo.read(f);
                assertArrayEquals(Files.readAllBytes(f), Files.readAllBytes(b.resolve(f.getFileName())), f.toString());
            }
        }
        String report = Files.readString(a.resolve("extract-report.json"));
        assertTrue(report.contains("\"unknownShare\""));
    }

    @Test
    void reportShares() {
        Map<String, Object> r = Extractor.report(Fixtures.example());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> c = (Map<String, Map<String, Object>>) r.get("concepts");
        assertEquals(0, c.get("block").get("unknown"));
        assertEquals(1, c.get("item").get("unknown"));
    }
}
