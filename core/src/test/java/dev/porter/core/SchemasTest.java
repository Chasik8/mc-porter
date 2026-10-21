package dev.porter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.porter.core.contract.Contract;
import dev.porter.core.json.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The schemas themselves: valid draft 2020-12, versioned, with stable ids. */
class SchemasTest {
    @Test
    void schemasAreValidDraft202012AndVersioned() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        for (Contract c : Contract.values()) {
            Path file = ContractExamplesTest.repoRoot().resolve("contracts").resolve(c.id() + ".schema.json");
            JsonNode schema = Json.json().readTree(file.toFile());
            List<Error> errors = meta.validate(schema);
            assertTrue(errors.isEmpty(), c.id() + ": " + errors);
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asString());
            assertEquals("urn:porter:contract:" + c.id(), schema.path("$id").asString());
            JsonNode version = schema.path("properties").path("schemaVersion");
            if (c == Contract.IR) {
                // RFC 003: IR accepts 1 (documents without RFC 003 fields) and the current version
                assertEquals("[1,2]", version.path("enum").toString(), "ir: schemaVersion enum");
                assertEquals(2, Contract.IR.schemaVersion());
            } else {
                assertEquals(Contract.SCHEMA_VERSION, version.path("const").asInt(), c.id() + ": schemaVersion const");
            }
            assertTrue(schema.path("required").toString().contains("\"schemaVersion\""), c.id() + ": schemaVersion required");
        }
    }
}
