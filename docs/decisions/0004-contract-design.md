# 0004: Contract design choices

- Status: accepted

Choices left open when the contracts were first drafted:

1. **One IR document type** `{schemaVersion, source, nodes[]}`; extractors may split output into
   several files. Nodes are discriminated by `kind` (`if/then` per kind, so errors are specific).
2. **Strict schemas** (`additionalProperties`/`unevaluatedProperties: false`). Room for growth is
   `extensions` (IR) and RFCs. Reason: a silently ignored typo in a field name is exactly the
   "quiet guess" the project avoids.
3. **Ids are namespaced** (`minecraft:air`); `mod` uses the bare mod id. `lang` nodes are per
   namespace and locale (`examplemod:en_us`).
4. **`confidence` optional (default 1.0)**; `unknown` has none. `provenance.file` and `version`
   are required, `line` optional (runtime exports have none).
5. **Multiblock geometry**: `size = [x, y, z]`, layers bottom-to-top, rows along z, chars along x,
   positions from the min corner; consistency is a semantic check. Palette entries may be a
   wildcard (`{any: true}`).
6. **Menu**: only machine slots go in `slots`; the player inventory is one `playerInventory`
   anchor. Bars reference synced values by `dataSlots` name.
7. **Rules**: one rule per YAML document (several documents per file instead of a wrapper list);
   `match` has exactly one selector; `tests` has `minItems: 1`.
8. **Symbol graph**: one schema with two document types (`graph`, `queryResult`); symbolRefs use
   the version's canonical namespace declared in `versions[]`.
9. **Candidates** are JSON Lines logs, one candidate per line, each with `schemaVersion`.
10. **Issue locations** are JSON Pointers (`/nodes/0/id`).
11. **Vocabularies owned by modules**, not frozen here: rule `preconditions`, scenario sample
    probes and the `assert` expression syntax. Owners document them in their module README; the
    contract fixes only the shape.
12. **Java models** exist in `core` for IR, rules and candidates (the documents that several
    modules bind). Symbol graph, scenario and trace are validated in `core` but bound by their
    owners (graph, testkit); typed models can move into `core` later if a second consumer needs
    them.
