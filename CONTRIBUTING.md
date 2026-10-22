# Contributing to porter

Porter is meant to grow from its users' ports. Everyone who ports a mod runs into the same Minecraft/Forge API changes. When one person resolves a symbol once and shares it as a tested rule, nobody else has to resolve it again. This document covers what you can contribute, how it is checked, and what must never be committed.

> Repository: https://github.com/Chasik8/mc-porter. Licence: Apache-2.0 (`LICENSE`, `NOTICE`); contributions are accepted under the same licence (Apache-2.0 section 5). CI items that are still proposals are marked as such.

## Contents

1. [Ground rules](#ground-rules)
2. [Legal model](#legal-model)
3. [Contribution types](#contribution-types)
3. [Port a mod, then share the rules](#port-a-mod-then-share-the-rules)
4. [Writing rules](#writing-rules)
5. [Review and acceptance](#review-and-acceptance)
6. [Confidence, provenance and conflicts](#confidence-provenance-and-conflicts)
7. [Legal rules](#legal-rules)
8. [Rules registry layout](#rules-registry-layout)
9. [Governance](#governance)
10. [Good first issues](#good-first-issues)
11. [Development setup](#development-setup)

## Ground rules

These rules apply to everyone, human or AI agent.

1. **Don't invent API.** Every Minecraft/Forge class, method or field in a rule, generator or extractor must exist in the real jar. Check it with `javap` or `porter graph query`. If you can't verify it, emit `unknown` or a `TODO(porter:...)` instead.
2. **Never guess silently.** An extractor that isn't sure emits `kind: unknown` with a `reason`. A rule whose preconditions can't be proven doesn't fire.
3. **Determinism.** The same input must give the same output bytes. No timestamps, no hash-map iteration order, no network access during a run.
4. **Tests are mandatory.** No test means no merge. A rule without a before/after test fails schema validation.
5. **Contracts are frozen.** Changes to `contracts/*.schema.json` go through an RFC (`contracts/rfc/NNN-<name>.md`, see `contracts/rfc/README.md`) and bump `schemaVersion`.
6. **No over-engineering.** New IR concepts only when real `unknown` nodes show the need. The core shim stays thin (coordinates, directions, one-line world helpers). Adapters of the source-version API (`rewrite/shim/src/adapters`, decision 0008) are added **one distinct symbol at a time, when a real port needs it**, never as a speculative facade.
7. **Library first.** Look for an existing library before writing your own. If you do write your own, record why in `docs/decisions/`.

## Legal model

What is public, what stays on your machine:

| | Where | Examples |
|---|---|---|
| **Version-pair packs** (public) | this repository | rules (`rules/`), the core shim and the adapter pack (`rewrite/shim/`), extractors/generators, scenarios on vanilla blocks |
| **Mod profiles** (public) | `ports/<mod>/` + an entry in `ports/catalog.yaml` | the port config (`<mod>.port.yaml`: seeds, hub cuts, stage settings), scenarios for the mod, generic rules found while porting it |
| **Ported output of a non-open mod** (local only) | your own machine | the copied/rewritten closure, hub overrides, counted edits that quote the mod's code, built jars, candidate logs, IR/traces with the mod's ids |

- Everyone runs a profile **locally, against their own copy of the original mod jar/sources**. Porter never downloads or ships a mod.
- A ported **jar may be published only** for open-licence mods, or with the original author's written permission (`ports/catalog.yaml`: `distributable: true` requires `permission:` for `ARR`).
- A profile must not contain the mod's code. Seeds and hub prefixes are class names (facts about the mod's structure); counted edits whose patterns quote the mod's code stay in the local overlay directories, not in the profile.
- Mapping files (Mojang, MCP, SRG, Parchment, tiny) are never committed; porter builds what it needs in your cache.
- The one place that may name a closed-licence mod is its own public profile: the one profile directory named in `scripts/publish-check.sh` (publish-check lifts the private-mod-name rule there and nowhere else). Such a profile must contain no code or assets derived from the mod (`porter inventory` category C = 0). CI checks the static part (`ports.py catalog-check`: no `patches/`, `textures/`, images or archives for ports whose catalog licence is not open) and cannot run the content part, because it cannot download the original. The maintainer runs `porter inventory` locally and the PR template asks for its output. CI skips building such a port (no direct url) and says so in the run summary.
- CI runs `scripts/publish-check.sh` on every PR: it fails on `private/` paths, mapping files, decompiled code (decompiler banners, dense SRG names, CFR-style `var1` names, packages on `scripts/publish-denylist.txt` or your git-ignored `scripts/publish-denylist.local.txt`, and a built-in list of private-mod names, extendable with `$PUBLISH_FORBIDDEN_NAMES`), jars/classes, files over 1 MB, local absolute paths and e-mail addresses. It is a heuristic (reformatted code, renamed packages or mods missing from the denylist can pass); review still decides.

## Contribution types

### 0. Mod profiles

Short version in [docs/guides/share-your-port.md](docs/guides/share-your-port.md). A profile makes a mod portable for everyone who owns it. The layout of `ports/<mod>/`: `README.md` (what the mod is, author and licence, download links to the *pages* of every original file, the one command, expected time, what was verified), `<mod>.port.yaml` with `inputs:` (page, file name and sha256 of every original file; `url` only for open-licence files), `run.sh` and `run.bat` (copy them from `ports/_template`), `scenarios/`, and `overlay/`/`project/` when needed. The list in `ports/README.md` is generated: run `porter catalog --write` after editing `ports/catalog.yaml` (`scripts/check.sh` fails when it is stale). Before the PR run `scripts/check.sh` and `scripts/publish-check.sh`. The port itself: `ports/<mod>/<mod>.port.yaml` (schema `contracts/port-config.schema.json`; `mode: full` for a mod that does not exist on the target version, `mode: delta` for an addon over a base that does), scenario files, and any generic rules found on the way (those go to `rules/`). Add or update the entry in `ports/catalog.yaml` (status, scenario pass rate, licence, `distributable`). Machine-specific paths go into `vars:` or environment variables, never into the profile.

### 1. Rules (most wanted)

These are generic Minecraft/Forge API mappings for an existing version pair, for example `Level#getBlockEntity(BlockPos)` → `World#getTileEntity(int, int, int)`.

- Public rules cover **vanilla Minecraft and Forge APIs only**. Rules for a specific mod's API go into a [rule pack](#5-rule-packs-for-third-party-apis).
- **One rule per distinct symbol**, or per symbol + precondition variant. Don't bundle unrelated symbols into one rule. Don't write a rule for a whole statement when a symbol-level rule would cover it.
- Each rule has at least one before/after test. Add a second test for a precondition boundary when the rule has preconditions.
- Graph-generated mechanical rules (`provenance.source: graph`) aren't committed. They're regenerated from the local graph. Write a manual rule only where the graph says `removed`, `signature_changed`, `split` or `merged`, or where the graph is wrong.

### 2. New version pairs

A version pair means: an **extractor per IR concept** for the source version (`extract/<platform>-<version>/`) and a **generator per concept** for the target version (`generate/<platform>-<version>/`). If either version is new to the symbol graph, it also needs graph inputs.

- Add the version to `graph/src/main/resources/dev/porter/graph/sources.json` with URL and published checksum for every input (client jar, mappings). Nothing without a verified source.
- Extractors and generators can arrive one concept at a time, in this order: `mod`, `lang`, `item`, `block`, `blockEntity`, `recipe`, `menu`, `packet`, `fluid`, `sound`, `effect`, `tag`, `multiblock`. A missing concept becomes `unknown`, which is fine.
- Every extractor needs unit tests on a synthetic example mod. Every generator needs a synthetic IR whose output compiles against its Forge.
- New Gradle modules are registered in `settings.gradle.kts` and as `runtimeOnly` in `cli/build.gradle.kts` (decision 0003). Ask a maintainer to do this or include it in your PR.

### 3. Asset converters

Assets convert as a chain with one step per neighbouring `pack_format`. A new step, for example upward conversion or item models, goes into `assets/` with a synthetic test and a lossless verifier where the format allows one. Anything it can't convert must be listed explicitly, the way `needs-manual-render` is now.

### 4. Scenarios for vanilla behaviour

`testkit/scenarios/*.scenario.yaml` holds version-independent scenarios on **vanilla blocks only**. Each one should pass on both versions of a pair and diff equal within the tolerances it declares. Any tolerance you add (`tickShift`, `ignore`) needs a comment that explains the measured difference. The furnace scenario is the template. Hoppers, brewing stands, redstone timing, fluids and crafting tables are all open.

### 5. Rule packs for third-party APIs

These are rules mapping a library's or mod's API between its versions, for example a popular energy API or a GUI library.

- They live in a **separate pack directory** with its own `PACK.md` (name, upstream project, versions covered, maintainer) and its own `LICENSE`.
- They may only contain API mappings: owner/method/field names, signatures, and short synthetic tests. No method bodies, no decompiled code, no assets.
- Before publishing a pack for a mod whose API is not under an open licence, ask that mod's author. If the author says no, the pack stays private.
- Packs are opt-in: `porter rewrite --rules rules/ --rules packs/<pack>/rules`.

### 6. Code

Bug fixes and features in the modules (`core`, `graph`, `extract`, `generate`, `rewrite`, `loop`, `assets`, `testkit`). Each module has an owner (see [Governance](#governance)). Cross-module changes go through the contracts. Edits to another module's internals are allowed only when that module's owner agrees.

## Port a mod, then share the rules

This is the main way the rule base grows.

```
port your mod with porter
  -> porter loop logs every fix as a candidate (<work>/candidates/*.candidates.jsonl)
  -> recurring fixes become draft rules (<work>/proposed/, count >= 3)
  -> you curate: strip anything mod-specific, add tests, set provenance
  -> PR to rules/  (generic)  or  packs/<pack>/  (third-party API)
  -> CI + curator review -> merged -> everyone's next port closes that symbol automatically
```

Step by step:

1. **Port.** `porter coverage` first (how much of the mod's API the rules already cover), then `porter port --config <mod>.port.yaml`. The compile stage writes `<work>/symbols.json`: the remaining errors grouped by distinct symbol. Resolve each distinct symbol once (rule, adapter or counted edit) and re-run (`porter port --config ... --from rewrite`). When you or an AI agent fix an error, log the fix as a candidate (the loop's task files explain how). The schema is `contracts/candidate.schema.json`; it requires `error`, `file`, `before`, `after`, `author` and `count`, and `symbol` and `normalized` are recommended.
2. **Aggregate.** `porter loop --aggregate <work>/candidates --proposed <dir>` groups candidates by normalised form and drafts a rule for each group with `count >= 3`. Drafts get `confidence: 0.5`, `provenance.source: agent` and tests taken from the candidates. `porter drafts <dir> --out <issues-dir>` turns the drafts into issue-ready markdown (title, labels `good first issue` + `rules`, the observed fixes, a checklist) for the [good first issue flow](#good-first-issues).
3. **Curate.** Per draft:
   - is the symbol vanilla/Forge (public rule) or a library/mod API (pack)?
   - replace every mod-specific identifier in `tests` with a synthetic one (`examplemod`, `w`, `p`, `be`);
   - make sure `before`/`after` are short and synthetic, never copied from the mod you ported;
   - add preconditions where the rewrite is only safe under conditions (`pure:$x`, `recv-subtype:<fqn>`, …; vocabulary in `rewrite/README.md`);
   - set `provenance` (see [below](#confidence-provenance-and-conflicts)).
4. **Check locally.** `porter validate <file>` and `porter rewrite --from <v> --to <v> --check-rules --rules <file>`.
5. **PR.** Describe which mod you ported, only if its licence allows naming it, and how often each symbol occurred. **Never attach candidate logs from an ARR mod**: their `before`/`after` fields contain that mod's code. Submit only the curated rules.

A candidate log is not a public artifact. Raw `*.candidates.jsonl` files, `proposed/` drafts and `tasks/` stay in your work directory. Pass `--proposed rules/_proposed` only for open-licence projects.

## Writing rules

Schema: `contracts/rule.schema.json`. Semantics: `rewrite/README.md`. One rule per YAML document; several documents per file are fine.

```yaml
schemaVersion: 1
id: r-1.19.2-1.7.10-level-getblockentity
description: "Level#getBlockEntity(BlockPos) -> World#getTileEntity(x, y, z) when the position is pure."
from: forge-1.19.2
to: forge-1.7.10
kind: adapter                 # mechanical | adapter | idiom
match:
  call: "net.minecraft.world.level.Level#getBlockEntity(net.minecraft.core.BlockPos)"
  bind: { recv: "$w", args: ["$p"] }
replace: "$w.getTileEntity($p.getX(), $p.getY(), $p.getZ())"
preconditions: ["pure:$p"]
priority: 10
confidence: 1.0
provenance: { source: manual, ref: "manual: verified against Mojang 1.19.2 mappings and the MCP 1.7.10 jar (javap)" }
tests:
  - context: "Level w; BlockPos p; import net.minecraft.world.level.Level"
    before: "Object c = w.getBlockEntity(p);"
    after:  "Object c = w.getTileEntity(p.getX(), p.getY(), p.getZ());"
```

**IDs and naming**

- Format: `r-<from-mc>-<to-mc>-<slug>`, for example `r-1.19.2-1.7.10-level-getblockentity`. For packs: `r-<pack>-<from>-<to>-<slug>`.
- Slug: `<owner-simple-name>-<member>` in lower case, plus a variant suffix when needed (`-helper`, `-int`, `-double`). Type rules are `type-<simplename>`, constructors `new-<simplename>-<params>`, overrides `override-<owner>-<member>`.
- IDs are stable. Never reuse an ID for a different mapping; deprecate it and add a new one.
- Loop drafts are named `r-<from>-<to>-draft-<slug>-<sha8>`. Rename the draft when you curate it.

**Checklist**

- [ ] Source member checked against the source version's mappings, target member against the target jar (`javap`) or the symbol graph. Say how in `provenance.ref`.
- [ ] `match` has exactly one selector (`call`, `new`, `field`, `type`, `override`, `pattern` or `declaration`, RFC 006).
- [ ] Every `$placeholder` in `replace` is bound.
- [ ] Imports the replacement needs are in `imports`.
- [ ] At least one test, plus one per precondition branch. Tests are synthetic.
- [ ] `confidence` reflects how certain the mapping is (see below).

## Review and acceptance

### Automated checks (CI)

What runs on every push and PR (`.github/workflows/ci.yml`, locally `scripts/check.sh`): `./gradlew build` (all unit tests, every public rule's before/after test in `:rewrite:test`, `ContractExamplesTest`), `scripts/validate-examples.sh` (every contract example, port configs and catalog included), validation of `ports/catalog.yaml`, and `scripts/publish-check.sh` on the changed files (see [Legal model](#legal-model); its limits are documented in the script header).

### CI for ports (`.github/workflows/ci.yml`, `port.yml`, `nightly.yml`)

Where things go: **a new port lives in its own `ports/<mod>/` folder** (config, scenarios, README, `run.sh`/`run.bat`) with its own rule files if it needs any (a rule pack of its own, never edits to the shared ones), plus one entry in `ports/catalog.yaml`. Changes to reference files (`rules/composite`, `rules/packs`, `rewrite/`, `ports/torchmaster`, `ports/_template`, `.github/`, `scripts/`, contracts; see `.github/CODEOWNERS`) need the maintainer's review, and CI re-runs the reference port for them.

What a PR runs (the required status check is the job named `build`, which is green only when all of this is):

1. **throttle**: at most **one CI run per hour per contributor** (your GitHub login, counted over this workflow's pull-request runs). A second run inside the hour stops at once with `max 1 CI run per hour per contributor; push again after HH:MM UTC` and `build` is red with that message, nothing else runs. **To retry: push again (or click "Re-run all jobs") after the time shown.** A run that was throttled, or was cancelled by your own newer push to the same PR, does not count. The maintainer and scheduled runs are exempt. Pushing to a PR cancels its previous run.
2. **core**: `scripts/check.sh` (build, unit tests, every rule's before/after test, contract examples, catalog and its consistency) and `scripts/publish-check.sh`.
3. **ports**: for every `ports/<mod>/` the PR adds or changes, one job runs `porter run` (inputs are downloaded from the `url:` declared in the profile and their sha256 is checked), builds the port, then runs its scenarios on dedicated 1.7.10 / 1.19.2 servers in batches. **Every port must also pass the automatic smoke scenario** (generated by CI: server starts with the mod, all its blocks and items register, no exceptions in the log). A port with no scenario files is allowed only with `smokeOnly: true` in its catalog entry (checked both ways). If the PR touches `rules/`, `rewrite/`, `port/`, `testkit/` or `scripts/ci/`, the TorchMaster port runs too (fast regression).
4. **nightly** (and on demand: Actions -> nightly -> Run workflow): every port in the catalog, end to end; failures open or update one `nightly-failure` issue.

Inputs must be **direct file URLs** (CI cannot use download pages). Keep the page in `page:` and put the direct file link in `url:`, only for open-licence files: a Modrinth CDN link (`https://cdn.modrinth.com/data/...`), a GitHub release asset or source archive (`https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`, `.../archive/<commit>.zip`), or a CurseForge files CDN link (`https://mediafilez.forgecdn.net/files/<id>/<id>/<file>`, i.e. the link behind the "download" button / the API download URL of the file id). A `curseforge.com/.../files/<id>` or `modrinth.com/mod/...` page is HTML and fails with an explanation. Required inputs without a `url:` (non-open mods) cannot be checked by CI; the maintainer verifies such a port locally and merges it.

Built jars are attached to the run only for ports that are open-licence and `distributable` in the catalog, for 2 days. Jars of any other port are never uploaded.

Workflows use `pull_request` (never `pull_request_target`), so code from a fork never gets a write token or secrets; the top-level permission is `contents: read`. First-time accounts still need a maintainer's approval before the first run.

Run the same pieces yourself: `ports/<mod>/run.sh`, `scripts/ci/ports.py inputs ports/<mod> --dest <dir>`, `scripts/ci/run-scenarios.sh` (needs the testkit servers, `scripts/ci/testkit-setup.sh` shows the Linux setup).

Proposed additions, marked *proposed*:

- **Rule tests per file:** `porter rewrite --check-rules` per changed rule file, so failures name the rule.
- **Symbol existence:** every `match` owner/member resolves in the symbol graph for `from`, and every member in `replace` resolves for `to` (needs a cached graph in CI).
- **Rule size limits:** `tests[].before/after` and `replace` at most 3 statements / 300 characters; no package names outside `net.minecraft`, `net.minecraftforge`, `cpw.mods`, `com.mojang`, `java.*`, `dev.porter`, or the pack's declared API packages.
- **Determinism:** rerun the changed generator or extractor tests twice and compare bytes.

### Human review

A rule PR is accepted when:

1. CI is green.
2. One maintainer (or the rules curator for the area) reviews it. Rules that change behaviour of existing ports (new priority, changed `replace`) need two.
3. The mapping is **behaviour-preserving** or the difference is documented in `description`. Example: 1.7.10 has no equivalent, so the rule is an approximation, with `confidence < 0.9`.
4. It's an API mapping, not code: a reviewer could write the rule from the two versions' javadoc or mappings alone, without seeing any mod.
5. There's no duplicate. Search the registry for the same `match` selector first.

Extractor and generator PRs also need: tests on synthetic input, unchanged output for existing synthetic IR (or an explained diff), and an entry in the module README for any new option.

Scenario PRs need traces from both versions attached to the PR description (not committed, since `reports/traces/` is for maintained references) and a passing `porter test diff`.

## Confidence, provenance and conflicts

### Provenance

Every rule has `provenance: { source, ref, chain? }`:

| `source` | Meaning | `ref` should contain |
|---|---|---|
| `manual` | written by a person | how it was verified (mappings, `javap`, docs link) |
| `agent` | proposed by an AI agent or drafted by `porter loop` from candidates | candidate ids / count, the agent, who curated it |
| `mined` | derived from public multi-version ports (miner) | upstream repo + commit pair, licence |
| `graph` | generated from the symbol graph (not committed) | edge chain |

An `agent` rule that a human verified keeps `source: agent` and adds `curated-by: <name>` to `ref`. That way provenance shows how the rule was found, and `ref` shows who vouched for it.

### Confidence

| Value | When |
|---|---|
| 1.0 | exact equivalent, verified in both jars, test covers it |
| 0.9–0.95 | equivalent through the shim or with a known edge case documented in `description` |
| 0.6–0.85 | approximation (behaviour differs in documented cases) |
| 0.5 | uncurated loop draft. Never merged at this value |

### Conflicts

When several rules match one site, the engine decides (`rewrite/README.md`):

1. Rules whose **preconditions** fail fall through to the next one. This is the main way to express variants: an inline expansion at priority 10 with `pure:$p`, and a shim helper at priority 0 with no such condition.
2. The highest **priority** wins. Hand-written rules use 0–20, graph rules −10.
3. Two different rules at the same priority → `ambiguous`: TODO, nothing applied. **CI should treat new ambiguities in the synthetic test corpus as a failure** (*proposed*).

If your rule conflicts with an existing one, don't just raise your priority. Add a precondition that separates the cases, or change the existing rule in the same PR with its owner's review. Pack rules default to priority 5 so they don't override core rules by accident.

## Legal rules

The project only works if it stays clean. A PR that breaks these rules is closed and its history rewritten.

**Never commit:**

- decompiled code of any mod, or fragments of it, in sources, tests, rule tests, fixtures, logs, IR, traces, candidate logs or issue text;
- assets of All-Rights-Reserved mods (textures, sounds, lang files, wiki or guide-book text, models);
- Mojang mappings, MCP/SRG/Parchment data, or derived name tables: the symbol graph, `mappings/*.tiny`, vanilla indexes. These are built locally from official sources by `porter graph build` and live in the cache;
- Minecraft or Forge jars, or decompiled Minecraft source;
- anything from a local `private/` directory (git-ignored; CI fails if it shows up).

**OK to share:**

- rules that map **API names and signatures**, with short **synthetic** tests: the names of classes and members you call are facts about the API, not someone's implementation;
- scenarios on vanilla blocks, and IR/traces of the synthetic example mods (`examplemod`, `tinymod`);
- fixtures from **open-licence** mods (MIT, Apache, LGPL, …), with the licence in `fixtures/<mod>/LICENSE` and attribution;
- aggregate numbers from private ports ("3,167 errors, 610 distinct symbols"), without code.

Only port mods you have the rights to port, and keep ports of ARR mods private. If you're unsure whether something is OK, open an issue *without* the content and ask.

## Rules registry layout

Today the public rules live in `rules/composite/1.19.2__1.7.10/<area>.rule.yaml`; the adapter rules are an opt-in pack, `rules/packs/adapters/1.19.2__1.7.10/` (`adapters-mc`, `adapters-client`, `adapters-libs`), that targets the adapter pack in `rewrite/shim/src/adapters/java/dev/porter/shim/mc` and is used together with `install.adapters: bundled`. Mod profiles live in `ports/<mod>/` and are listed in `ports/catalog.yaml`. Proposed structure as the registry grows (to migrate after the first external contributions):

```
rules/
  1.19.2__1.18.2/            # adjacent pairs: small, mostly mechanical diffs
    world/ nbt/ items/ ...
  1.12.2__1.7.10/
    ...
  composite/                 # direct or chained rules for non-adjacent pairs
    1.19.2__1.7.10/
      coords/      blockpos.rule.yaml, direction.rule.yaml
      nbt/         compoundtag.rule.yaml, listtag.rule.yaml
      world/       level.rule.yaml, blockentity.rule.yaml
      items/  entities/  fluids/  math/  gui/  network/  capabilities/  text/
  _proposed/                 # loop drafts for open-licence projects only; curator-owned
packs/
  <pack-name>/               # third-party API packs, opt-in
    PACK.md                  # upstream, versions, maintainer, contact with the API's author
    LICENSE                  # the pack's own licence
    rules/<from>__<to>/<area>/*.rule.yaml
```

- `<from>__<to>` uses bare Minecraft versions; the platform (`forge-`, `neoforge-`) is in each rule's `from`/`to`.
- Areas follow the API, not the mod: `world`, `blockentity`, `nbt`, `items`, `entities`, `fluids`, `gui`, `network`, `capabilities`, `text`, `math`, `coords`, `registry`.
- One file per owner class, or per small group of closely related classes.

## Governance

Lightweight, until there are enough contributors to need more.

- **Maintainers** (@Chasik8): merge rights, releases, RFC decisions on contracts. Decisions go into `docs/decisions/NNNN-<topic>.md`.
- **Area owners**, one per module (`graph`, `extract/<version>`, `generate/<version>`, `rewrite`, `loop`, `assets`, `testkit`) plus **rules curators** per version pair. They review PRs in their area. Listed in `.github/CODEOWNERS` (@Chasik8 until area owners are named).
- **Pack maintainers** own their pack and its licence.
- **RFCs** for contract changes: open a PR with `contracts/rfc/NNN-<name>.md` (motivation, schema diff, migration). It's accepted by a maintainer after at least 7 days for comments.
- **AI-assisted contributions** are welcome and follow the same rules. Say so in the PR. Agent-found rules use `provenance.source: agent`. The human submitting the PR is responsible for verifying every symbol.

Labels:

| Label | Use |
|---|---|
| `rules` | rule additions/changes |
| `pack` | third-party API packs |
| `version-pair` | new extractor/generator/graph version |
| `scenario` | testkit scenarios |
| `contract` / `rfc` | schema changes |
| `legal-check` | needs a licence/content check before review |
| `good first issue` | small, well-scoped |
| `help wanted` | larger, maintainers won't get to it soon |

## Good first issues

The flow: a port's compile loop logs every fix as a candidate, recurring fixes become draft rules, and `porter drafts <proposed-dir> --out <dir>` exports each draft as an issue file with labels `good first issue` and `rules` (only from open-licence ports: drafts quote the code they were seen in). A maintainer files them; a contributor turns one draft into a tested, synthetic rule.

Standing list:

- **Rules for the 10 golden-set misses** the graph reports as `removed` but which are renames (e.g. `BlockBehaviour#tick` → `Block#updateTick`, `Screen` → `GuiScreen`, `ItemStack#of` → `ItemStack.loadItemStackFromNBT`). See `graph/README.md`.
- **Forge capabilities → 1.7.10 interfaces** (`IItemHandler`/`IFluidHandler` capability lookups → `IInventory`/`IFluidHandler`): adapter rules with preconditions.
- **`Component`/`ChatFormatting` → 1.7.10 chat/`StatCollector`**, one of the most common TODO reasons.
- **`BlockState` property reads → meta access**, coordinated with the generator's meta layout.
- **Scenario coverage:** hopper transfer timing, brewing stand, redstone repeater delays, bucket/fluid placement.
- **Version pair 1.12.2 ↔ 1.7.10:** the graph already covers both; needs an extractor for Forge 1.12.2, starting with `mod`, `lang`, `item`.
- **Version pair 1.16.5 → 1.7.10 or 1.12.2:** same, Forge 1.16.5 extractor.
- **1.19.2 client automation in testkit** (GUI dumps on 1.19.2).
- **`porter report`:** aggregate the run reports into the project's metrics (currently a stub).
- **Pattern rules:** lambdas, loops, `switch` and anonymous classes inside `match.pattern` (RFC 006 leaves them out).
- **Item models** (`item/generated` → item icons) in `assets`.

## Development setup

- JDK 21 for porter (Gradle wrapper pinned; `options.release = 21`). JDK 17 and 8 only for building test mods and ported mods.
- `./gradlew build` runs everything public, including all rule tests and contract examples. `scripts/check.sh` also validates the examples via the CLI.
- Minecraft launches and heavy Gradle jobs: see `testkit/README.md`. Logic scenarios run on dedicated servers without a display, and GUI scenarios need Xvfb on Linux.
- Commit messages: imperative, prefixed with the area (`rules:`, `graph:`, `testkit:`). One logical change per PR.
