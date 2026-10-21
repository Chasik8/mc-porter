package dev.porter.core.contract;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Validated reading and writing of contract documents. Readers validate before binding; writers validate
 * before touching the disk, so nothing invalid is ever produced. Output is UTF-8 with LF line endings.
 */
public final class ContractIo {
    private ContractIo() {
    }

    /** Reads all documents of a file and validates them against {@code contract}. */
    public static List<JsonNode> readValidated(Path file, Contract contract) {
        Documents.Parsed parsed;
        try {
            parsed = Documents.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<ValidationIssue> issues = new ArrayList<>(parsed.issues());
        for (int i = 0; i < parsed.documents().size(); i++) {
            issues.addAll(ContractValidator.get().validate(contract, parsed.documents().get(i), i));
        }
        if (!issues.isEmpty()) {
            throw new ContractViolationException(file.toString(), issues);
        }
        return parsed.documents();
    }

    /** Converts a model object to a tree and validates it. */
    public static JsonNode toValidatedTree(Object model, Contract contract, String what) {
        JsonNode tree = Json.json().valueToTree(model);
        List<ValidationIssue> issues = ContractValidator.get().validate(contract, tree);
        if (!issues.isEmpty()) {
            throw new ContractViolationException(what, issues);
        }
        return tree;
    }

    /** Writes one validated JSON document (pretty-printed, trailing newline). */
    public static void writeJson(Path file, Object model, Contract contract) {
        JsonNode tree = toValidatedTree(model, contract, file.toString());
        write(file, Json.json().writeValueAsString(tree).replace("\r\n", "\n") + "\n", false);
    }

    /** Appends one validated document as a single JSON line (for *.jsonl logs). */
    public static void appendJsonLine(Path file, Object model, Contract contract) {
        JsonNode tree = toValidatedTree(model, contract, file.toString());
        String line = Json.json().writer().without(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(tree);
        write(file, line + "\n", true);
    }

    /** Writes validated YAML documents separated by {@code ---}. */
    public static void writeYaml(Path file, List<?> models, Contract contract) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            JsonNode tree = toValidatedTree(models.get(i), contract, file + " document " + i);
            String yaml = Json.yaml().writeValueAsString(tree).replace("\r\n", "\n");
            if (i > 0 && !yaml.startsWith("---")) {
                sb.append("---\n");
            }
            sb.append(yaml);
        }
        write(file, sb.toString(), false);
    }

    private static void write(Path file, String content, boolean append) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (append) {
                Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
