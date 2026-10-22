# How porter works

This page is for people who want to understand porter or extend it. To get a ported mod, see the
[README](../README.md); to port a mod, see [Port your own mod](guides/port-your-own-mod.md).

## The idea in one paragraph

A mod has two kinds of content. **Declarative content** (blocks, items, lang, recipes, GUIs, multiblocks) is read
into a version-independent JSON description (the **IR**) and generated fresh for the target version. **Logic code** is
copied as a **dependency closure** and rewritten with type-aware rules, each of which carries mandatory before/after
tests. Whatever the rules do not cover shows up as compile errors, grouped by **distinct symbol** (one entry per API
member, however many times it is used). Each symbol is resolved **once**, by a rule, an adapter or a counted edit, and
the deterministic pipeline runs again. **Differential tests** then run the same scenario on real headless servers of
both versions and compare what happened, tick for tick.

## Pipeline

```
mod (version A)
 ├─ declarative -> extract(A) -> IR (JSON) -> generate(B) -> code/assets for version B
 └─ logic code  -> closure (recursive copy, hubs cut and stubbed, known+identical classes left alone)
                    -> rewrite (symbol graph + rules) -> install (stubs, passes, adapters) -> project for version B
                                  |
                    compile -> errors by distinct symbol -> rule | adapter | edit (once per symbol) -> re-run
                                  |
                    checks: contracts, reobf check of the jar, differential scenario test A vs B
```

`porter port --config <mod>.port.yaml` runs the stages in order and remembers which ones finished, so a re-run resumes
(`--status`, `--from`, `--to`, `--stage`, `--restart`, `--dry-run`):

| Stage | What it does |
|---|---|
| `generate` | `porter generate` per unit, from the extracted IR; installs the generated files without overwriting hand edits |
| `closure` | dependency closure of the seed classes: unknown classes are copied recursively, hub prefixes are cut (the members the closure uses become stubs), classes known on the target side with an identical API are left alone; reports **coverage** |
| `rewrite` | `porter rewrite` of closure + hubs with the rule packs, relocated into the mod's package |
| `install` | hub stubs, source passes (SRG → Mojang names, Java 9+ → 8 syntax, tile-entity constructors, counted edits, unused imports), the adapter pack, overlays → the target project |
| `loop` | `porter loop`: compile, classify errors, apply rules, write tasks for the rest |
| `compile` | javac, fixes of decompiler cast artefacts, the remaining errors **by distinct symbol** (`symbols.json`) |
| `build` | the target project's own Gradle build, and a reobfuscation collision check of the jar |
| `test` | scenario runs (`porter test diff ...`) |

After every stage porter writes `port-report.md` / `.json` in the work directory (default
`<project>/build/porter-port/`): stage metrics, coverage, and an explicit **not-ported** list: every generator skip
(something the IR cannot express, such as a fluid in a crafting grid), every dropped class, every hub whose feature is
stubbed out, every unresolved symbol, each with a reason. Nothing is dropped silently.

Two modes: **`full`**, the mod does not exist on the target version and is ported whole; **`delta`**, an addon over a
base that exists on the target version (for example features of a newer version backported as an addon to the old
version of the same mod): `closure.known` names the base's sources, and classes that are identical there are not
copied.

`porter run <port-dir>` is the player-facing wrapper around `porter port`: it checks the declared input files
(sha256), does the one-time setup for the version pair (JDKs, Forge, mappings, symbol graph, the 1.7.10 Gradle
template), runs the profile's `setup`/`prepare` steps, then the port, and copies the jar to `output/`. The
`ports/<mod>/run.sh` and `run.bat` launchers start it and fetch a Java 21 first if there is none.

## The parts

| Part | Module | What it is |
|---|---|---|
| **Symbol graph** | `graph` | Classes and members of Minecraft 1.19.2, 1.18.2, 1.16.5, 1.12.2 and 1.7.10, matched between adjacent versions and built locally from the official jars and mappings (checksum-verified, never committed). `porter graph build` / `query`. It also generates ~9,000 mechanical rename rules on the fly. [graph/README.md](../graph/README.md) |
| **Extractor** | `extract/forge-1.19.2` | Reads 1.19.2 sources (or a decompile) into IR: 13 concepts (`mod`, `lang`, `item`, `block`, `blockEntity`, `recipe`, `menu`, `packet`, `fluid`, `sound`, `effect`, `tag`, `multiblock`). Anything it is not sure about becomes `kind: unknown` with a reason. Mod-specific patterns can be added as a plugin jar. |
| **Generators** | `generate/forge-1.7.10`, `generate/forge-1.19.2` | Code and assets for a version from IR. The 1.7.10 generator also writes NotEnoughItems recipe and multiblock pages (`--no-nei` to leave them out). The 1.19.2 one is used for round-trip tests. |
| **Assets** | `assets` | Converts resource packs between versions, one `pack_format` step at a time; lists what it cannot convert (`needs-manual-render`). |
| **Closure** | `port` | The recursive copy with hub cuts, hub stubs, source passes, cast fixes, the distinct-symbol report, the reobf check, `coverage`. [Decision 0008](decisions/0008-closure-port-and-port-command.md) |
| **Rules** | `rules/`, `rewrite` | YAML rules applied by a Spoon-based, type-aware rewriter. Selectors: `call`, `new`, `field`, `type`, `override`, `pattern`, `declaration`; preconditions; priorities. Every rule has before/after tests that run in the build. Core rules are in `rules/composite/1.19.2__1.7.10` (~260); the opt-in adapter pack is `rules/packs/adapters/1.19.2__1.7.10` (~650). [rewrite/README.md](../rewrite/README.md) |
| **Adapters** | `rewrite/shim` | ~140 small 1.7.10 classes that give ported code the 1.19.2 API it still calls (block states, block entities, menus and screens, capabilities, fluids, NBT, configs, deferred registers, commands...). Added one distinct symbol at a time, when a real port needs it; copied into the mod's own package by `install.adapters: bundled`. |
| **Loop** | `loop` | Compile, classify javac errors, apply rules; every manual fix is logged as a candidate, and recurring fixes become draft rules (`porter drafts` turns them into issue text). |
| **Testkit** | `testkit` | Runs version-independent scenarios (place blocks, tick, insert items, open GUIs, sample values, assert) on real dedicated servers or clients of both versions, records traces and compares them. Many scenarios share one Minecraft launch. [testkit/README.md](../testkit/README.md) |
| **Contracts** | `contracts/` | JSON schemas for every file porter exchanges: IR, rules, symbol graph, scenarios, traces, candidates, port configs, the port catalog. `porter validate` checks them. [contracts/README.md](../contracts/README.md) |

## Port profiles

A **profile** is everything needed to port one mod, and nothing of the mod itself. It lives in `ports/<mod>/`:

- `<mod>.port.yaml`: the port config ([schema](../contracts/port-config.schema.json)). It declares the `inputs` (the
  original files: download page, file name, sha256, and a direct `url` only for open-licence files), one-time `setup`
  steps and per-run `derive` steps (decompile, download, unzip, copy...), `prepare` steps (extract, assets), and the
  settings of each stage: seeds, prefixes and hub cuts of the closure, rule directories, passes, compile options, the
  build command.
- `scenarios/`: the port's checks. `overlay/`, `project/`: small hand-written files (the 1.7.10 `@Mod` class,
  `gradle.properties`, licence and credits). Optionally its own rules.
- `README.md`, `run.sh`, `run.bat`, and one entry in `ports/catalog.yaml`.

A profile for a mod that is not open-licence must not contain anything derived from the mod's code or assets. Class
names, seed lists and hub prefixes are facts about the mod's structure and are fine; changes to the mod's own code are
shipped as rules, or as "blind" patches that carry no text of the original (RFC 009). `porter inventory` measures what
a profile ships against the original's sources.

## Version packs

Everything specific to a version pair is a pack: the rules (`rules/composite/<from>__<to>`, `rules/packs/...`), the
adapters, the extractor of the source version and the generator of the target version, and the one-time setup in
`porter run` (`port/.../start/Forge1192To1710.java`). A new pair needs an extractor per IR concept and a generator per
concept (they can arrive one concept at a time; a missing concept becomes `unknown`), graph inputs for any new
version, and rules. See CONTRIBUTING.md, "New version pairs".

## Running the stages by hand

`porter run` sets everything up for Forge 1.19.2 → 1.7.10. To run the stages yourself (another layout, debugging):

| What | Version |
|---|---|
| porter (CLI, build, tests) | JDK **21**, `JAVA_HOME` pointing to it; Gradle wrapper included |
| a Forge 1.19.2 mod (source side, testkit server) | JDK 17 |
| a Forge 1.7.10 project (target side, RetroFuturaGradle) | Java 8 toolchain, JDK 17 to 25 to run Gradle |
| testkit servers | 17 (1.19.2), 8 (1.7.10); see `testkit/env.example.properties` |

```sh
git clone https://github.com/Chasik8/mc-porter.git && cd mc-porter
JAVA_HOME=/path/to/jdk-21 ./gradlew build              # tests, rule tests, contract examples
export PATH="$PWD/cli/build/install/porter/bin:$PATH"  # porter, porter.bat
export PORTER_CACHE=/path/with/some/GB/free            # downloads, symbol graph, mapping-derived indexes

porter graph build --versions 1.19.2,1.18.2,1.16.5,1.12.2,1.7.10
porter closure  --src mymod/src/main/java --prefix com.example.mymod. --seed com.example.mymod.MyMod \
                --cut com.example.mymod.registry. --out closure/
porter coverage --src mymod/src/main/java --classes closure/port.txt --rules rules --graph "$PORTER_CACHE/graph/out"
porter validate mymod.port.yaml
porter port --config mymod.port.yaml          # resumes where it stopped
porter port --config mymod.port.yaml --status
```

The target project must write its compile classpath to `build/porter-cp.txt` (see the `porterClasspath` task in
`ports/_template/project/dependencies.gradle`, or `scripts/gradle/porter-classpath.gradle`). Examples of complete
configs: `contracts/examples/port-config/valid/`, `ports/torchmaster/torchmaster.port.yaml`.

## Commands

`porter <command> --help` describes every option. Every command writes a JSON report to `reports/runs/` (or
`--reports-dir`), prints at most about 20 lines (full log in `<reports-dir>/logs/`, or `--verbose`) and exits 0 ok,
1 failed, 2 usage error, 3 not implemented, 4 internal error.

| Command | Purpose |
|---|---|
| `run` | a whole port for a player: inputs, one-time setup, port, jar in `output/` |
| `port` | the whole port from a config, resumable by stage |
| `closure`, `stubs`, `passes`, `castfix`, `symbols`, `obfcheck` | the building blocks of `port`, usable alone |
| `coverage` | share of a mod's distinct API symbols that the existing rules already resolve |
| `extract`, `select`, `generate`, `assets` | IR from 1.19.2 sources, one unit's IR from a whole-mod extract, code and assets from IR |
| `decompile`, `patch` | pinned CFR decompile of a jar; unified diffs between trees (`--blind`: no text of the base) |
| `rewrite`, `loop`, `drafts` | rule engine; compile/classify/fix loop; draft rules as issue text |
| `graph build`, `graph query` | cross-version symbol graph |
| `test run`, `test diff`, `test roundtrip`, `test obtainable` | scenario tests on real servers and clients |
| `validate` | any contract document |
| `catalog` | the table in `ports/README.md` from `ports/catalog.yaml` |
| `inventory` | what a profile ships and how much of it overlaps the original |
| `report` | aggregate metrics (planned; prints "not implemented") |

## Validation and cost

Porter was first validated on a private port of a large tech mod (not open-licence, so none of it is in this
repository): a 1.19.2 subsystem with its dependency closure, ported into a 1.7.10 addon (`mode: delta`).

- Closure: 281 classes / 28k lines copied, 45 hubs cut. The first pass made 10,533 automatic rewrites and left
  3,167 javac errors over **610 distinct symbols**; 14 resolution rounds took that to 0.
- Scenarios: equal to the original 1.19.2 mod tick for tick.
- What it left publicly: the first 374 adapter rules and the first adapter classes. A second, public port
  (Torchmaster, 28 classes) started from them: 113 → 0 distinct symbols in 4 rounds, 0 counted edits.
- Re-running a port from a clean checkout rebuilds the same jar byte for byte.

**Machine time** (home PC, 4 threads, 16 GB): the one-time setup (symbol graph, Forge, the 1.7.10 Gradle environment)
is 15 to 30 minutes, mostly downloads; a port of a few hundred classes then takes a few minutes per pass. The
deterministic pipeline uses no AI. **Human or AI time** scales with the number of distinct unresolved symbols, not with
lines of code: on the 610-symbol closure above it was about 4.4 hours of AI model time (~0.33M tokens). `porter
coverage` tells you your starting point before you spend anything.

## Current limits

- One version pair: Forge 1.19.2 → Forge 1.7.10. Graph data exists for 1.18.2, 1.16.5 and 1.12.2, but no extractors or
  generators yet.
- Models more complex than cubes are listed as `needs-manual-render`; some recipe shapes have no IR concept yet (fluids
  in crafting grids). Both appear in the not-ported list.
- Pattern rules do not match lambdas, loops, `switch` or anonymous classes yet (RFC 006).
- Coverage counts imported types and SRG-named members; sources with Mojang member names only count types.
- The adapter pack compiles only against a 1.7.10 MCP jar (`-Pporter.mc1710Jar`), so the public build checks the
  adapter rules' tests but not the adapters' compilation; the port CI builds them as part of each port.
- `porter run` sets up only Forge 1.19.2 → 1.7.10; `porter report` is planned.

## Design decisions

The reasoning behind the main choices. The full records stay in [docs/decisions/](decisions/) and
[contracts/rfc/](../contracts/rfc/).

- **Copy, don't re-implement** ([0008](decisions/0008-closure-port-and-port-command.md)). Logic is ported as a
  dependency closure: unknown classes are copied, known and identical ones left alone, the rest resolved once per
  distinct symbol. Hubs (registries, networking) are cut and stubbed to keep the copy finite. One config and one
  command (`porter port`) replace the glue scripts of the first port.
- **Adapters per symbol, not a facade** (0008). Ported code may keep calling the 1.19.2 API through small adapters,
  each added only when a real port needs that symbol.
- **Never guess silently.** Extractors emit `unknown` with a reason; rules whose preconditions cannot be proven do not
  fire; every skip is in the not-ported list.
- **Strict, versioned contracts** ([0004](decisions/0004-contract-design.md),
  [0002](decisions/0002-schema-validation-and-json-libs.md)). JSON Schema 2020-12, no unknown fields; changes only
  through an RFC.
- **Coverage is a definite answer** ([0006](decisions/0006-graph-coverage-definition.md)). A symbol counts as covered
  when the graph maps it or proves it removed, with evidence.
- **Extraction without a full Forge workspace** ([0007](decisions/0007-extract-noclasspath-interpreter.md)). Spoon in
  no-classpath mode plus a small interpreter for registration code, so extracting needs no ForgeGradle setup.
- **Build** ([0001](decisions/0001-java-21-toolchain.md), [0003](decisions/0003-gradle-layout-and-cli-spi.md)). Java 21
  (Temurin); one Gradle project per module; subcommands found through a service interface.

| RFC | What | Status |
|---|---|---|
| [001](../contracts/rfc/001-scenario-item-stacks.md) | item stacks with count/NBT in scenarios | proposed |
| [002](../contracts/rfc/002-guide-book.md) | guide-book chapters in the IR | proposed |
| [003](../contracts/rfc/003-recipe-viewer-data.md) | recipe-viewer data in the IR (NEI) | implemented |
| [004](../contracts/rfc/004-port-config-and-catalog.md) | port config and port catalog | implemented |
| [005](../contracts/rfc/005-scenario-gui-checks.md) | GUI checks in scenarios | implemented |
| [006](../contracts/rfc/006-rule-patterns-and-declarations.md) | pattern rules, declaration edits, type preconditions | implemented |
| [007](../contracts/rfc/007-port-inputs-and-run.md) | `inputs`, `prepare` and `porter run` | implemented |
| [008](../contracts/rfc/008-catalog-smoke-only.md) | `smokeOnly` ports in the catalog | implemented |
| [009](../contracts/rfc/009-profile-steps-and-patches.md) | profile steps, blind patches, plugins | implemented |
| [010](../contracts/rfc/010-hub-keep-and-source-constructors.md) | kept hub members, source constructors | implemented |
