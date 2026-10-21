# Contract RFCs

The contracts in `contracts/` are frozen as of the first release. Any change (new field, new IR concept,
loosened or tightened constraint, new document type) goes through an RFC. Rule 2 of
the project rules: contracts are untouchable without one.

## Process

1. **Write** `contracts/rfc/NNN-<short-name>.md` (next free number, 3 digits) using the template
   below, status `proposed`. Anyone may propose; evidence usually comes from `unknown` nodes,
   TODO markers or candidates that do not fit.
2. **Implement on the same branch**, not merged yet: the schema change, the `schemaVersion` bump
   (`const` in the schema and `Contract.SCHEMA_VERSION` in `core`), updated/added examples (at
   least one valid example exercising the change, and an invalid one if a constraint changed),
   Java models in `core`, `contracts/README.md`.
3. **Review**: a maintainer checks consistency with the other contracts and reviews
   the tests and that no all-rights-reserved content entered examples.
4. **Approve**: a maintainer approves. Status becomes `accepted`, or `rejected` with the
   reason (rejected RFCs stay in the directory).
5. **Migrate**: the maintainers of producers and consumers update their modules; if the bump breaks existing
   files, the RFC lists the migration (script or manual) and the affected files.

`gradlew build` must stay green at every step: `ContractExamplesTest` validates all examples and
`SchemasTest` checks that each schema is valid draft 2020-12 with the expected `schemaVersion`.

Versioning: every accepted RFC that changes a schema bumps that schema's `schemaVersion` by 1.
Additive optional fields bump it too, so readers know which fields may appear.

## Template

```markdown
# RFC NNN: <title>

- Status: proposed | accepted | rejected
- Author: <contributor>
- Contracts: ir | rule | symbol-graph | scenario | trace | candidate
- schemaVersion: <old> -> <new>

## Motivation
What does not fit today? Evidence (unknown nodes, files, counts).

## Change
Exact schema diff (fields, types, required, constraints) and semantic checks.

## Compatibility and migration
Which producers/consumers change; how existing files are migrated.

## Alternatives
What else was considered (including `extensions` instead of a field).
```
