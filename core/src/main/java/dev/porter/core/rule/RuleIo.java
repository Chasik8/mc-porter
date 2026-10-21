package dev.porter.core.rule;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads/writes rule YAML files (one rule per YAML document; several documents per file allowed). */
public final class RuleIo {
    private RuleIo() {
    }

    /** Reads and validates every rule in a file. */
    public static List<Rule> read(Path file) {
        return ContractIo.readValidated(file, Contract.RULE).stream()
                .map(n -> Json.json().treeToValue(n, Rule.class))
                .toList();
    }

    /**
     * Reads every {@code *.rule.yaml}/{@code *.rule.yml} under a directory, in sorted path order (deterministic).
     */
    public static List<Rule> readAll(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .filter(p -> Contract.bySuffix(p.getFileName().toString()).orElse(null) == Contract.RULE)
                    .sorted()
                    .toList();
            List<Rule> rules = new ArrayList<>();
            for (Path p : sorted) {
                rules.addAll(read(p));
            }
            return rules;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, List<Rule> rules) {
        ContractIo.writeYaml(file, rules, Contract.RULE);
    }
}
