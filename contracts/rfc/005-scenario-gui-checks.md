# RFC 005: GUI checks in scenarios (`open_gui` captures, `gui_input`, `close_gui`, `check`)

- Status: accepted (implemented)
- Author: porter maintainers
- Contracts: scenario
- schemaVersion: 1 -> 1 (additive; see "Versioning" below)

## Motivation
A port used to end with "go into the game and look at the GUIs". testkit could open a container GUI on a 1.7.10
client and compare slot coordinates with the IR `menu` node, but it could not: open item GUIs (guide books), act on
an open screen (page flips, tabs), capture a screen twice to see which indicators react to state changes, nor say how
a capture is to be judged. Evidence from the private reference port (a large tech mod): 3 machine GUIs with gauges/bars (steam reactor,
fuel boiler, refinery), 5 blocks that must stay GUI-less, a guide book with ~40 page spreads and no 1.19.2
counterpart at the same layout.

## Change (scenario schema, additive)
- `open_gui`: `at` or `item` (anyOf). `item` = the client player holds and uses it. New optional `capture`
  (name; captures with the same name are compared across versions), `keepOpen`, `check`.
- New step `gui_input`: at most one of `click: [x, y]` (+ `button`), `key` (platform key name), `call {method, args}`
  (test hook: public method of the screen object), then an optional `capture` with its own `check`. Without input it
  only captures (after steps that changed the block's state while the screen stayed open).
- New step `close_gui: {}`.
- `$defs/guiCheck`: `mode` (`diff` default | `structural` | `absent`), `size` (GUI area of non-container screens,
  centred), `mask` (rects excluded from the image diff), `textBoxes` (every drawn text must lie inside one),
  `texts` (lang keys that must be drawn), `dynamicFrom` (earlier capture: changed regions = dynamic indicators),
  `pixelThreshold`, `maxDiff`. Coordinates: GUI pixels relative to the GUI's top-left corner.
- `gui_input` captures inherit the `check` of the `open_gui` that opened the screen (except `mode`/`dynamicFrom`
  overrides).

A port profile lists the GUIs to check as client scenarios (one document per GUI or group).

## Semantics (testkit)
- Both versions run a real client (1.19.2 client launch is new), GUI scale 1, 854x480, window off-screen.
- Per capture the client records one frame: framebuffer (cropped to the GUI rectangle), slots, every text draw
  (string, position, width, colour; through a recording font), render errors logged during the op.
- `diff`: structural diff (size, machine slots by position, player grid, texts identical or equivalent through a
  shared lang key/English value, dynamic indicators matched by position), then an image diff with 1 px shift
  tolerance, masks (texts, slot contents, `mask`), heatmap. Verdict equal / acceptable-diff / broken.
- `structural`: renders without errors, non-empty, texts inside `textBoxes`, no raw lang keys, `texts` present.
- `absent`: no screen may open within 40 ticks.

The trace contract is unchanged: `gui[]` keeps size + slots; the full capture data is a testkit-internal artefact
(`<scenario>-<version>.gui/gui.json` + PNGs next to the trace).

## Versioning
Every new field is optional and every v1 document stays valid, so the producers/consumers need no migration. The
RFC process asks for a bump, but `Contract.SCHEMA_VERSION` is one constant for all contracts (core,
`SchemasTest`): bumping only the scenario schema needs a per-contract version in core first. Proposal: bump when
a maintainer introduces per-contract versions (or together with RFC 001, which also touches the scenario).

## Compatibility and migration
None needed. Old `open_gui {at, menu, screenshot}` works as before (and now also yields a capture named
`<scenario>-step<N>`).

## Open questions
- `call` is a test hook into mod code (method names are version-specific); keep it, or allow only click/key?
- Should `dynamicFrom` indicators be written into the trace's `bars` (now always empty)?
