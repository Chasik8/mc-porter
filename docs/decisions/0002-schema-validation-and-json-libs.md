# 0002: networknt json-schema-validator 3.x + Jackson 3

- Status: accepted

## Decision
- JSON Schema **draft 2020-12**, validated with `com.networknt:json-schema-validator` 3.0.8
  (library-first, rule 7; supports `unevaluatedProperties`, `if/then`, `$defs`). Messages are
  forced to `Locale.ROOT` (English) so output does not depend on the OS locale.
- networknt 3.x is built on **Jackson 3** (`tools.jackson.*`), so porter uses Jackson 3.2.x
  everywhere (`jackson-databind`, `jackson-dataformat-yaml`). Annotations stay in
  `com.fasterxml.jackson.annotation` (Jackson 3 keeps the 2.x annotations artifact).
  For module authors: Jackson 3 exceptions are unchecked (`JacksonException`),
  `JsonNode#asText()` became `asString()`, object field iteration is `properties()`.
- Shared mappers: `dev.porter.core.json.Json.json()` / `.yaml()` (pretty JSON, nulls omitted,
  insertion order kept). Use them rather than ad-hoc mappers so outputs are byte-identical.
- Schemas are self-contained (no cross-file `$ref`) and use `urn:porter:contract:<id>` as `$id`,
  so no validator ever tries to fetch anything over the network.
- Constraints a schema cannot express live in `SemanticChecks` and are documented in
  contracts/README.md as part of the contract.
- CLI: picocli 4.7.7. networknt logs via slf4j; the CLI ships `slf4j-nop`.
- Tests: JUnit 6.1 (Jupiter API).
