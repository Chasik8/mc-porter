# RFC 004: port config and port catalog (additive contracts)

Status: accepted for v0.1.0. Additive: the six v1 contracts are unchanged.

## Motivation
`porter port` needs a per-port configuration file, and the community needs a machine-checkable list of ports
(which mod, which version pair, how far, may the result be distributed). Both are new document types; neither
changes an existing contract, so `schemaVersion` stays 1 for all.

## Change
- `contracts/port-config.schema.json` (`urn:porter:contract:port-config`), file suffix `.port.yaml|.port.yml|.port.json`,
  detected by top-level `project` + `closure`/`generate`. Sections: `mode` (`delta` | `full`), `classpath`, and one
  optional section per stage (`generate`, `closure`, `rewrite`, `install`, `loop`, `compile`, `build`, `test`).
  `delta` requires `closure.known`; `full` forbids it.
- `contracts/port-catalog.schema.json` (`urn:porter:contract:port-catalog`), suffix `.catalog.yaml|.catalog.yml|.catalog.json`,
  detected by a top-level `ports` array. Per entry: mod, version pair, mode, status, scenario pass rate, licence,
  `distributable` (an `ARR` licence with `distributable: true` requires `permission`).
- `Contract` gains `PORT_CONFIG` and `PORT_CATALOG`; `porter validate` handles both; examples in
  `contracts/examples/port-config` and `contracts/examples/port-catalog`.

## Compatibility
Existing documents and tools are unaffected.
