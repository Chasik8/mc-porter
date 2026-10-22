# porter contracts

Frozen data contracts between porter modules. They are
JSON Schemas, **draft 2020-12**, plus a few semantic checks that a schema cannot express. the first release they change **only via RFC** (see [rfc/README.md](rfc/README.md)).

| Contract | Schema | File suffix | Produced by | Consumed by |
|---|---|---|---|---|
| IR | [ir.schema.json](ir.schema.json) | `*.ir.json` (`*.ir.yaml`) | extract, runtime exports | generate, assets, testkit |
| Rule | [rule.schema.json](rule.schema.json) | `*.rule.yaml` | rewriter, rules curators, graph (mechanical), miner | rewrite, loop |
| Symbol graph | [symbol-graph.schema.json](symbol-graph.schema.json) | `*.graph.json` | graph | rewriter, loop |
| Scenario | [scenario.schema.json](scenario.schema.json) | `*.scenario.yaml` | testkit, humans | testkit runner |
| Trace | [trace.schema.json](trace.schema.json) | `*.trace.json` | testkit runner | testkit compare, report |
| Candidate | [candidate.schema.json](candidate.schema.json) | `*.candidates.jsonl`, `*.candidate.json` | loop, rewriter, anyone fixing code (human or AI agent) | loop (aggregation), rules-curator |

The schemas in this directory are the single source of truth: `core` bundles them into its jar at
build time (`porter/contracts/<id>.schema.json` on the classpath).

## Validating

```
porter validate <file-or-dir>...            # contract from suffix, else from content
porter validate --schema rule my-rules.yaml # force a contract
porter validate -q contracts/examples       # directories: only files with a contract suffix
```

Exit code 0 = everything valid, 1 = something invalid, 2 = usage error. Issues are printed as
`<JSON Pointer>: <message>`, semantic issues end in `[semantic]`, parse issues in `[parse]`.

Content detection (when the suffix says nothing): `nodes` → ir, `match`+`replace` → rule,
`type: graph|queryResult` → symbol-graph, `steps` → scenario, `samples` → trace,
`error`+`author` → candidate. Prefer suffixes.

Multiple documents per file: YAML files may contain several documents separated by `---`, JSONL
files one document per line. Each document is validated on its own and must carry `schemaVersion`.

From Java (module `core`):

```java
ContractValidator.get().validateFile(path, null);       // any contract, detection as above
ContractValidator.get().validate(Contract.IR, jsonNode); // one tree
IrDocument ir = IrIo.read(path);   IrIo.write(path, ir);    // validated both ways
List<Rule> rules = RuleIo.read(path);  RuleIo.readAll(dir);
CandidateIo.append(log, candidate);  CandidateIo.read(log);
```

Readers validate before binding; writers validate before writing — invalid documents are never
produced (`ContractViolationException` carries the issues).

## Common conventions

- **`schemaVersion`** (integer, currently `1`; IR `1` or `2`, see RFC 003) is required at the top of every document. A breaking
  or additive change bumps it (see RFC process). Readers reject unknown versions.
- **Strict shapes.** Unknown properties are errors everywhere (`additionalProperties` /
  `unevaluatedProperties: false`). Data that has no field yet goes into `extensions` (IR) or
  waits for an RFC.
- **Resource ids** are always namespaced: `namespace:path` (`minecraft:air`, never `air`). Tag
  references are `#namespace:path`.
- **Platform versions** (`forge-1.19.2`, `forge-1.7.10`, also `neoforge-*`, `fabric-*`,
  `vanilla-*`) in IR, rules, scenarios, traces and candidates. The symbol graph uses bare
  Minecraft versions (`1.19.2`) because it describes vanilla symbols.
- **Class names** are fully qualified, binary form for nested classes (`a.b.Outer$Inner`).
- **Never guess.** An extractor that is not certain emits `kind: unknown` with a `reason`; a rule
  whose preconditions cannot be evaluated does not fire (the rewriter writes a
  `TODO(porter:<reason>)`).
- **Determinism.** Producers emit nodes, lists and map entries in a stable order (source order, or
  sorted). `porter` writers keep insertion order, omit nulls, write UTF-8 with LF.

## IR (`ir.schema.json`)

```json
{ "schemaVersion": 1, "source": { "version": "forge-1.19.2", "modId": "examplemod" }, "nodes": [ ... ] }
```

`porter extract --out ir/` may write one or several IR files; each is a complete document.

Common fields of every concept node:

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | one of the kinds below |
| `id` | yes (except `unknown`) | resource id; for `mod` the bare mod id |
| `provenance` | yes | `{file, line?, version, via?, extractor?}`; `file` is relative to the mod source root or `runtime:<what>`; `via` ∈ source, bytecode, resource, runtime-export, manual |
| `confidence` | no | 0..1, default 1.0 |
| `extensions` | no | free-form object, keys namespaced `owner:name`; consumers ignore unknown keys |

Logic is never serialized: nodes reference classes (`logicClass`, `handlerClass`, `screenClass`)
that go through the rewriter.

| Kind | Required fields | Other fields |
|---|---|---|
| `mod` | `name`, `version` | `description`, `authors[]`, `license`, `mainClass`, `dependencies[{modId, versionRange, required, ordering}]` |
| `block` | — | `hidden` (RFC 003), `translationKey`, `logicClass`, `material`, `hardness`, `resistance`, `lightLevel`, `soundType`, `requiresTool`, `harvest{tool, level}`, `creativeTab`, `stateProperties[{name, type: boolean\|integer\|enum\|direction, values[], default}]`, `textures{all, down, up, north, south, west, east, particle}`, `model{ref, shape, renderType}`, `blockEntity`, `item`, `lootTable` |
| `item` | — | `hidden` (RFC 003), `translationKey`, `logicClass`, `maxStackSize`, `maxDamage`, `rarity`, `creativeTab`, `placesBlock`, `containerItem`, `textures{layerN}`, `food{nutrition, saturation, alwaysEdible, drink, effects[{effect, duration, amplifier, probability}]}` |
| `blockEntity` | `blocks[]`, `logicClass` | `ticking` (none\|server\|client\|both), `menu`, `inventorySize`, `nbt[{key, type}]` |
| `fluid` | — | `translationKey`, `logicClass`, `stillTexture`, `flowingTexture`, `color` (#RRGGBB[AA]), `density`, `viscosity`, `temperature` (K), `luminosity`, `gaseous`, `block`, `bucket` |
| `recipe` | `recipeType` | shaped: `pattern[]`, `key{char: ingredient}`, `result`; shapeless/smithing: `ingredients[]`, `result`; smelting/blasting/smoking/campfire/stonecutting: one `ingredients[0]`, `result`, `experience`, `cookingTime`; custom: `customType` + any of `ingredients`, `result`, `results[]`, `data{}`. Ingredient = `{item}` \| `{tag}` \| `{anyOf: [...]}` (+ `count`); item stack = `{item, count, nbt, chance}`. Recipe viewers (RFC 003, v2): `fluidIngredients[]`, `fluidResults[]` (`{fluid, amount mB, chance}`), `duration` (ticks), `machines[]`, `notes[]` (string \| `{key, text}`) |
| `menu` | `size{width, height}`, `slots[]` | `title`, `logicClass`, `screenClass`, `blockEntity`, `texture{path, textureWidth, textureHeight, u, v}`, `slots[{index, x, y, role, filter}]` (machine slots only), `playerInventory{x, y, hotbarY}`, `dataSlots[{name, index}]`, `bars[{id, x, y, width, height, direction, u, v, value, max}]`, `buttons[{id, x, y, width, height, label, packet}]`. Pixel coordinates relative to the GUI background's top-left |
| `packet` | `direction`, `fields[]` | `channel`, `discriminator`, `logicClass`, `handlerClass`; field = `{name, type, elementType (list), enumClass (enum), maxLength}`; types: boolean, byte, short, int, varInt, long, varLong, float, double, string, uuid, resourceId, blockPos, direction, itemStack, fluidStack, nbt, bytes, enum, list |
| `lang` | `locale`, `entries{key: text}` | one node per namespace and locale, `id` = `<namespace>:<locale>` |
| `sound` | `sounds[{name, volume, pitch, weight, stream}]` | `category`, `subtitle`, `replace`; `id` = sound event id |
| `effect` | `category` (beneficial\|harmful\|neutral) | `translationKey`, `logicClass`, `color`, `instant`, `icon`, `attributeModifiers[{attribute, uuid, amount, operation}]` |
| `multiblock` | `size[x, y, z]`, `palette{char: blockRef}`, `layers` | `controller{block, pos[x,y,z], facing}`, `rotatable`, `mirrorable`. `layers` bottom-to-top (y); each layer has z rows; each row is a string of x palette chars. blockRef = `"ns:id"` \| `{block, state{}}` \| `{any: true}`. Positions relative to the min corner |
| `tag` | `registry` (block\|item\|fluid\|entity_type), `values[]` | `replace`, `oreDict[]` (OreDictionary names for 1.7.10, e.g. `ingotCopper`); value = id \| `#tag` \| `{id, required}`; `id` = tag id without `#` |
| `unknown` | `reason`, `provenance` | `id`, `snippet`, `conceptHint` (suspected kind), `extensions`; no `confidence` |

New concepts are added only when `unknown` nodes show a real need (rule 8), via RFC.

## Rule (`rule.schema.json`, YAML)

Required: `schemaVersion`, `id` (`r-…`), `from`, `to`, `kind` (mechanical\|adapter\|idiom), `match`,
`replace`, `confidence`, `provenance{source: graph|manual|agent|mined, ref, chain[]}`,
`tests[{name?, context?, before, after}]` with **at least one test**.

`match` holds exactly one selector — `call: "Owner#name(Params)"`, `new: "Owner(Params)"`,
`field: "Owner#name"`, `type: "a.b.C"`, `override: "Owner#name(Params)"` or `pattern: <Spoon template>` —
plus optional `bind{recv, args[], type, result}` naming `$placeholders`. Optional: `description`,
`imports[]`, `preconditions[]` (vocabulary owned by the rewriter), `reversible`, `priority`.
One rule per YAML document; several documents per file are allowed.

## Symbol graph (`symbol-graph.schema.json`)

Two document types, discriminated by `type`:

- `graph`: `versions[{id, canonical, namespaces[], sources[{name, version, url, sha256}]}]`,
  `symbols[{ref, names{namespace: name}, params[{index, name, source}], access}]`,
  `edges[{from, to, status, confidence, evidence[], note}]`.
- `queryResult`: `query{symbol, to}`, `path[edge]`, `status` (worst along the path),
  `confidence` (minimum along the path), `results[symbolRef]`, `missing{at, reason}`.

symbolRef = `{version, kind: class|method|field, owner, name, desc}` named in the version's
`canonical` namespace (e.g. mojmap for 1.19.2, mcp for 1.7.10); `desc` is the JVM descriptor
(required for methods). Status order, best to worst: same < renamed < moved < signature_changed <
split | merged < removed. `to` is absent exactly when `status = removed`; split/merged are
several edges sharing `from`/`to`.

## Scenario (`scenario.schema.json`, YAML)

Required: `schemaVersion`, `id`, `world` (`flat-void`, `flat`, …), `steps[]`. Optional:
`description`, `side` (server\|client), `maxTicks`, `tolerance{key-or-prefix|*: {abs, rel, tickShift,
ignore}}`, `keyMap{platformVersion: {key: canonicalKey}}`.
Each step is an object with exactly one key: `place_block`, `build_multiblock`, `fill_fluid`,
`insert_item`, `interact`, `open_gui`, `tick: N`, `sample{every?, what[]}`, `assert{expr, message?}`.
Sample keys look like `<probe>[:<target>][/<path>]` (`nbt:[1,65,0]/Progress`, `item:[0,64,0]#2`); the
probe vocabulary and assert expression syntax are owned by testkit. Scenarios reference IR ids,
never version-specific classes.
GUI checks (RFC 005, accepted, additive): `open_gui{at|item, menu?, capture?, keepOpen?, check?}`,
`gui_input{click[x,y]|key|call{method, args}, button?, capture?, check?}`, `close_gui{}`; `check` =
`{mode: diff|structural|absent, size[w,h], mask[[x,y,w,h]], textBoxes[[x,y,w,h]], texts[langKey],
dynamicFrom, pixelThreshold, maxDiff}` (GUI pixels relative to the GUI's top-left corner).

## Trace (`trace.schema.json`)

Required: `schemaVersion`, `scenario`, `version`, `status` (passed\|failed\|error\|timeout),
`samples[{tick, key, value}]` (sorted by tick, key). Optional: `subject{modId, jar, sha256}`,
`message`, `ticks`, `assertions[{step, tick, expr, passed, message}]`,
`gui[{step, menu, size, slots[{index, x, y, player}], bars[{id, x, y, width, height}], screenshot}]`.

## Candidate (`candidate.schema.json`)

Required: `schemaVersion`, `error`, `file`, `before`, `after`, `author` (`agent:<name>` \|
`human:<name>` \| `rule:<id>`), `count` (≥ 1). Optional: `id` (`c-…`), `from`, `to`, `errorClass`
(the loop classifier's classes), `line`, `symbol`, `normalized` (aggregation key), `ruleId`.
Logs are JSON Lines; `loop` aggregates by `normalized` and drafts rules into `rules/_proposed/` at
`count >= 3`.

## Semantic checks

Run after a document passed its schema (`SemanticChecks` in `core`). They are part of the
contract:

- IR: no duplicate `(kind, id)` (except `unknown`); RFC 003 fields only with `schemaVersion: 2`; `lang` id path equals `locale`;
  `multiblock`: `layers.length == size[1]`, every layer has `size[2]` rows, every row has
  `size[0]` chars, every char is in the palette, `controller.pos` inside `size`;
  shaped `recipe`: rows of equal length, every non-space pattern char has a key and every key is used.
- Rule: every `$placeholder` in `replace` is bound by `match.bind` or appears in `match.pattern`;
  `from != to`.

## Examples

`examples/<contract>/valid/*` must validate and `examples/<contract>/invalid/*` must fail; this is
enforced by `ContractExamplesTest` (part of `gradlew build`) and by
`scripts/validate-examples.sh` (CI). Examples use invented (`examplemod`, `tinymod`) or vanilla
ids only — never content from ARR mods. Rule examples are illustrations, not production rules.
