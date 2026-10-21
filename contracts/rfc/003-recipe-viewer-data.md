# RFC 003: recipe IO for recipe viewers, hidden objects

- Status: accepted (implemented)
- Author: porter maintainers
- Contracts: ir
- schemaVersion: 1 -> 2 (additive; documents without the new fields may keep 1)

## Motivation
1.7.10 players live in NotEnoughItems (NEI). NEI already shows crafting and smelting, but every machine/custom recipe,
every fluid and every multiblock of a ported mod is invisible unless the generator writes NEI handlers, and the IR does
not say what such a page needs:

- fluids consumed/produced by custom recipes sit in the type-specific `data` object under producer-specific keys
  (reference mod: `mainFluid`, `secondFluid`, `fluidInputs`, `fluidOutputs`), so a generator cannot find them;
- nothing says which block performs a recipe (NEI: "R/U on the machine", catalyst icon);
- processing/ageing time and human conditions ("quality rises after 1, 3, 9 in-game days", "2-6 per operation") have
  no place; output chances neither;
- technical objects (multiblock parts placed by code, debug items) cannot be marked, so they clutter item lists.

Evidence (private reference port on a large tech mod, counts only): 38 custom recipes (23 refining, 15 potion brewing) whose fluids are only in
`data`; 3 barrel brewing processes and 5 refinery recipes exist only as logic or `unknown` nodes; 0 of them have a
machine, a time or a readable condition in the IR.

## Change
`ir.schema.json`, all fields optional:

| Where | Field | Type | Meaning |
|---|---|---|---|
| `recipe` | `fluidIngredients` | `[fluidStack]`, min 1 | fluids consumed (custom recipes) |
| `recipe` | `fluidResults` | `[fluidStack]`, min 1 | fluids produced |
| `recipe` | `duration` | integer >= 0 | processing time in ticks at base speed (machine time, ageing...) |
| `recipe` | `machines` | `[resourceId]`, min 1, unique | blocks/items that perform the recipe, first = main |
| `recipe` | `notes` | `[recipeNote]`, min 1 | lines shown with the recipe (conditions, ranges, ageing) |
| `itemStack` | `chance` | number in (0, 1] | probability of an output, default 1 |
| `item`, `block` | `hidden` | boolean | internal/technical object: viewers and creative search hide it |

New `$defs`: `fluidStack = {fluid: resourceId, amount: integer >= 1 (mB), chance?: (0, 1]}`;
`recipeNote = string (min 1) | {key?: langKey, text: string}` (`text` = English fallback of `key`).

`schemaVersion`: `{"enum": [1, 2]}`. Semantic check: an RFC 003 field in a document that says `schemaVersion: 1`
is an error ("needs schemaVersion 2"). `core` writes the lowest version that fits (`IrDocument.versionFor`), so
producers that do not use the fields keep writing 1 and no existing file changes.

`data` keeps type-specific parameters for machine logic; the new fields are the version-neutral view of a recipe.
A producer may keep both (the reference port does: the ported Refinery still reads `data`).

## Compatibility and migration
- Readers: every v1 document stays valid. Java model: new record components with the old constructors kept
  (`IrNode.Recipe`, `Item`, `Block`, `ItemStack`), so existing producers compile unchanged.
- generate/forge-1.7.10: NEI pages from these fields (`--no-nei` to turn off); hidden objects get no creative tab.
- generate/forge-1.19.2: ignores the fields (JEI integration would read the same data; not done).
- extract: unchanged; a mod pattern pack could emit `fluidIngredients`/`fluidResults` from what it now puts into
  `data` (follow-up). The reference port adds them with a private overlay script.

## Alternatives
- `extensions["porter:nei"]`: free-form, unvalidated and NEI-specific; JEI/REI/GuideNH need the same data.
- A `recipeType` IR concept (title, machine, background per type): machines differ per recipe in practice; a title is
  derived from the type id and translatable, so a new kind is not needed yet.
- Fluids as ingredients (`{fluid, amount}` in `ingredient`): changes the meaning of crafting ingredients for every
  generator; kept separate until a fluid-in-crafting-grid case needs it.
