package dev.porter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractValidator;
import dev.porter.core.contract.ContractValidator.FileResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Every contracts/examples/<contract>/valid file must validate, every invalid one must fail. */
class ContractExamplesTest {
    static Path repoRoot() {
        return Path.of(System.getProperty("porter.repoRoot", "..")).toAbsolutePath().normalize();
    }

    static Path examples() {
        return repoRoot().resolve("contracts/examples");
    }

    static List<Path> files(Contract c, String kind) throws IOException {
        Path dir = examples().resolve(c.id()).resolve(kind);
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void everyContractHasEnoughExamples() throws IOException {
        for (Contract c : Contract.values()) {
            assertTrue(files(c, "valid").size() >= 2, c.id() + ": needs >= 2 valid examples");
            assertTrue(files(c, "invalid").size() >= 1, c.id() + ": needs >= 1 invalid example");
        }
    }

    @TestFactory
    Stream<DynamicTest> examplesValidateAsExpected() throws IOException {
        Stream.Builder<DynamicTest> tests = Stream.builder();
        for (Contract c : Contract.values()) {
            for (Path f : files(c, "valid")) {
                tests.add(DynamicTest.dynamicTest("valid " + c.id() + "/" + f.getFileName(), () -> {
                    FileResult r = ContractValidator.get().validateFile(f, null);
                    assertEquals(c, r.contract().orElse(null), "detected contract");
                    assertTrue(r.valid(), () -> f + " should be valid: " + r.issues());
                }));
            }
            for (Path f : files(c, "invalid")) {
                tests.add(DynamicTest.dynamicTest("invalid " + c.id() + "/" + f.getFileName(), () -> {
                    FileResult r = ContractValidator.get().validateFile(f, null);
                    assertEquals(c, r.contract().orElse(null), "detected contract");
                    assertFalse(r.valid(), () -> f + " should be invalid");
                    System.out.println(f.getFileName() + " -> " + r.issues());
                }));
            }
        }
        return tests.build();
    }
}
