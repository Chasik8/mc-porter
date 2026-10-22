# RFC 008: `smokeOnly` in the port catalog (additive)

- Status: accepted (implemented, CI)
- Author: porter maintainers
- Contracts: port-catalog
- schemaVersion: 1 -> 1 (additive: one new optional entry key)

## Motivation
CI runs every contributed port. A port without scenario files is allowed only if the automatic smoke scenario passes
(the server starts with the mod, all its blocks and items register, no exceptions in the log). The catalog must say
which ports have only that, so readers do not mistake them for scenario-tested ports.

## Change
`ports[].smokeOnly` (optional boolean). It must be `true` exactly when `ports/<mod>/scenarios/` holds no
`*.scenario.yaml`; `scripts/ci/ports.py catalog-check` (run by `scripts/check.sh` and nightly) enforces both directions.

## Examples
`contracts/examples/port-catalog/valid/entries.catalog.yaml` (second entry).

## Migration
None; entries without the key mean "has scenarios".
