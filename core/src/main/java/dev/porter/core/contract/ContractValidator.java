package dev.porter.core.contract;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Validates documents against the bundled JSON Schemas (draft 2020-12) and the semantic checks that a
 * schema cannot express ({@link SemanticChecks}). Thread-safe; use {@link #get()}.
 */
public final class ContractValidator {
    private static final ContractValidator INSTANCE = new ContractValidator();

    private final Map<Contract, Schema> schemas = new EnumMap<>(Contract.class);

    private ContractValidator() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (Contract c : Contract.values()) {
            try (InputStream in = ContractValidator.class.getResourceAsStream(c.schemaResource())) {
                if (in == null) {
                    throw new IllegalStateException("schema resource missing: " + c.schemaResource());
                }
                Schema schema = registry.getSchema(in);
                schema.initializeValidators();
                schemas.put(c, schema);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static ContractValidator get() {
        return INSTANCE;
    }

    /** Result of validating one file. {@code contract} is empty when it could not be detected. */
    public record FileResult(Path file, Optional<Contract> contract, int documents, List<ValidationIssue> issues) {
        public boolean valid() {
            return contract.isPresent() && issues.isEmpty();
        }
    }

    /** Schema + semantic validation of a single document. Semantic checks run only if the schema passes. */
    public List<ValidationIssue> validate(Contract contract, JsonNode document, int documentIndex) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<Error> errors = schemas.get(contract).validate(document,
                ctx -> ctx.executionConfig(cfg -> cfg.locale(Locale.ROOT)));
        for (Error e : errors) {
            issues.add(new ValidationIssue(documentIndex, String.valueOf(e.getInstanceLocation()), e.getMessage(), ValidationIssue.SCHEMA));
        }
        if (issues.isEmpty()) {
            issues.addAll(SemanticChecks.check(contract, document, documentIndex));
        }
        return issues;
    }

    public List<ValidationIssue> validate(Contract contract, JsonNode document) {
        return validate(contract, document, 0);
    }

    /**
     * Validates a file. The contract is {@code explicit} if given, else detected from the file name suffix
     * ({@link Contract#bySuffix}), else from the content ({@link #detect(JsonNode)}).
     */
    public FileResult validateFile(Path file, Contract explicit) {
        Documents.Parsed parsed;
        try {
            parsed = Documents.read(file);
        } catch (IOException e) {
            return new FileResult(file, Optional.ofNullable(explicit), 0,
                    List.of(new ValidationIssue(0, "", "cannot read file: " + e.getMessage(), ValidationIssue.PARSE)));
        }
        List<ValidationIssue> issues = new ArrayList<>(parsed.issues());
        Optional<Contract> contract = Optional.ofNullable(explicit)
                .or(() -> Contract.bySuffix(file.getFileName().toString()))
                .or(() -> parsed.documents().isEmpty() ? Optional.empty() : detect(parsed.documents().get(0)));
        if (contract.isEmpty()) {
            issues.add(new ValidationIssue(0, "",
                    "cannot detect contract; use a suffix like .ir.json/.rule.yaml or pass --schema", ValidationIssue.PARSE));
            return new FileResult(file, contract, parsed.documents().size(), issues);
        }
        if (!Documents.isYaml(file) && !Documents.isJsonLines(file) && parsed.documents().size() > 1) {
            issues.add(new ValidationIssue(1, "", "a .json file must contain exactly one document", ValidationIssue.PARSE));
        }
        for (int i = 0; i < parsed.documents().size(); i++) {
            issues.addAll(validate(contract.get(), parsed.documents().get(i), i));
        }
        return new FileResult(file, contract, parsed.documents().size(), issues);
    }

    /** Content-based detection by characteristic top-level fields; empty if ambiguous or unknown. */
    public static Optional<Contract> detect(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            return Optional.empty();
        }
        List<Contract> hits = new ArrayList<>();
        if (doc.has("nodes")) {
            hits.add(Contract.IR);
        }
        if (doc.has("match") && doc.has("replace")) {
            hits.add(Contract.RULE);
        }
        String type = doc.path("type").asString("");
        if (type.equals("graph") || type.equals("queryResult")) {
            hits.add(Contract.SYMBOL_GRAPH);
        }
        if (doc.has("steps")) {
            hits.add(Contract.SCENARIO);
        }
        if (doc.has("samples")) {
            hits.add(Contract.TRACE);
        }
        if (doc.has("error") && doc.has("author")) {
            hits.add(Contract.CANDIDATE);
        }
        if (doc.has("project") && (doc.has("closure") || doc.has("generate"))) {
            hits.add(Contract.PORT_CONFIG);
        }
        if (doc.has("ports") && doc.path("ports").isArray()) {
            hits.add(Contract.PORT_CATALOG);
        }
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }
}
