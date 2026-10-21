# RFC 001: item stacks with count/NBT in scenario steps

- Status: proposed (not implemented; testkit uses id-map aliases meanwhile)
- Author: porter maintainers
- Contracts: scenario
- schemaVersion: 1 -> 2

## Motivation
Scenario steps name items only by resource id. Real interactions need more:

- `interact.holding` has no count: "fill the barrel with 8 water cells in one click" or "one click with
  4 mugs" cannot be written (the reference scenarios needed 6 such stacks).
- `insert_item` has a count but no NBT: a filled fuel can is the item `examplemod:fuel_can` with
  `{fuel: 10000, max_fuel: 10000}` (1.19.2) / `{value: 10000, maxValue: 10000}` (1.7.10).

Workaround in place (testkit/README.md, "id maps"): per-version id maps define alias ids such as
`alias:fuel_can_10000` -> `{id, count, nbt}`. It works, but the scenario then references ids that are
not IR ids, and the version-independent part (the count) is duplicated in every id map.

## Change
1. `interact` gets an optional `count` (integer >= 1, default 1) for the held stack.
2. `insert_item` and `interact` get an optional `nbt` (object): canonical NBT of the stack. Version
   differences of NBT keys are expressed in the id map (testkit), not in the scenario.
3. (Optional, same bump) a `set_field` step `{at, path, value}` to force machine state
   (e.g. "heat forced to 24000" in the hand-port self test), restricted to testkit's field probe paths.

## Compatibility and migration
Additive; existing scenarios stay valid. testkit's plan compiler passes `count`/`nbt` through (the test
mods already accept both). Alias entries in the reference port's id maps can then be removed.

## Alternatives
- Keep aliases only (current state): no contract change, but scenarios name non-IR ids.
- Encode count in the id (`examplemod:cell_water*8`): breaks the resourceId pattern.
