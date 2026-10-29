# RFC 009: profile steps, patches, plugins and install passes in the port config

- Status: accepted (implemented, released in 0.2.0)
- Contracts: port-config
- schemaVersion: 1 -> 1 (additive: optional keys only; every existing config stays valid)
- Relation to RFC 007 (`inputs`, `prepare`, `porter run`): complementary. RFC 007 gets the player's files and the
  shared one-time setup of a version pair; this RFC lets one profile derive everything else it needs from those
  files inside `porter port`, without shipping anything derived from them.

## Motivation
A profile of a mod whose licence forbids redistribution (decompiled code, textures) can ship rules, selections and
hand-written interop code, but not the original's code or assets, and not code adapted from it. The pilot port of
such a mod needed private scripts outside the config for: decompiling the user's jar, selecting a subsystem from the
whole-mod IR plus hand-checked IR facts, picking the subsystem's assets, copying textures from the jar, moving asset
namespaces in the ported code, copying lang keys the ported GUIs look up, merging generated lang files, and putting
hand-adapted classes in place. This RFC makes each of these a declarative part of the config or a generic command.

## Change (port-config schema)
- `plugins`: jars or class directories added to child porter steps (`PORTER_PLUGINS`), e.g. a mod-specific extract
  pattern pack; a plugin pack with the id of a built-in one replaces it.
- `setup` and `derive` (new stages before `generate`): `{steps: [...]}`. A step is one of `porter: [args]`,
  `command: [exe, args]` (`cwd`, `env`, `queue`), `download: {url, sha256, to}`, `unzip: {zip, to, prefix, classes,
  stripFirst}`, `copy: {from, to}`, `composite: {base, overlay, to}` (an image: the base with the overlay's opaque
  pixels, i.e. an edit of a texture of the user's jar shipped as the changed pixels only) or `delete: [paths]`, plus
  `name` and `creates: [paths]` (skipped when all exist). `setup` is for one-time work, `derive` runs every time.
- `patches`: `[{name, after: <stage>, patches, base, target}]`: unified diffs applied after a stage. `---` paths are
  relative to `base` (default: the target), `+++` paths to `target` (default: the project), so a patch can turn a
  class of the user's own decompile into the ported class at another path. Patches made with
  `porter patch make --blind` carry no text of the base at all (no context, no removed lines; the base is pinned by
  its sha256 instead).
- `generate.langSections`: generated `.lang` files are merged into the project's under
  `# --- porter:<unit> ---` sections (in place; keys defined outside the section win).
- `install.passes` gains `assetns` with `install.assetNamespaces: [{from, to, paths}]`:
  `new ResourceLocation("<from>", "<path prefix>...")` gets the port's own namespace.
- `install.langKeys`: after the install, lang keys that the project's sources look up literally (a regex) are copied
  from the original's JSON lang files (the user's decompile) into a marked section of the existing `.lang` files.

## New commands (not contracts)
- `porter decompile --jar <jar> --sha256 <hex> --out <dir> [--java <java>]`: CFR 0.152 (downloaded from Maven
  Central, sha256-pinned) -> `<dir>/java`, resources -> `<dir>/res`. Same jar + same CFR + same Java major gives
  byte-identical sources (measured: Java 8 builds 8u51 and 8u504 agree; Java 21 differs in 10 of 2016 files).
- `porter select --ir <extract> --spec <unit.select.yaml> --out <dir>`: one unit's IR from a whole-mod extract (seed
  ids, references to a fixpoint, recipes, unknowns, class relocation, legacy names, a screen-class convention) plus
  an overlay (merge patches / additions / removals per node); `--make-overlay <ir>` writes the overlay that turns the
  plain selection into an existing IR, so hand edits of an IR become data.
- `porter assets --select <ns> [--include <glob>] [--literals <list> --literals-root <dir>]`: convert only the assets The literal scan also keeps texture and sound path literals (`"textures/gui/x.png"`, `new ResourceLocation(ns, "...")`, `"ns:..."`) whose file exists in the source assets.
  the given IR references (texture packages and their sprites, menu/block/item/fluid textures, lang keys of the IR and
  string literals of the unit's logic sources).
- `porter patch make|apply`.

## Examples
`contracts/examples/port-config/valid/delta-all-stages.port.yaml` (every new key), invalid
`setup-step-without-action.port.yaml`.

## Migration
None.
