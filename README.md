# porter

Porter ports Minecraft Forge mods between game versions. Declarative content (blocks, items, GUIs, recipes,
multiblocks) goes through a JSON intermediate representation and is generated for the target version. Logic code
is copied as a **dependency closure** and rewritten with type-aware rules that carry mandatory tests. Whatever the
rules don't cover shows up as compile errors, grouped by **distinct symbol**: each one is resolved once (a rule, an
adapter or a counted edit) and the deterministic pipeline re-runs. Differential tests run the same scenario on
headless servers of both versions and compare the traces.

First version pair: **Forge 1.19.2 → Forge 1.7.10 (10.13.4.1614)**. The architecture is meant to take other pairs
without changes to the core.

> Status: v0.1.0, early. One version pair works end to end; it was validated on a private port of a large tech
> mod. See [Status and limitations](#status-and-limitations).

## How it works

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

`porter port --config <mod>.port.yaml` runs all of it, stage by stage, resumable:

| Stage | What it does |
|---|---|
| `generate` | `porter generate` per unit (from extracted IR); installs generated files, never overwriting hand edits |
| `closure` | dependency closure of the seed classes: unknown classes are copied recursively, hub prefixes are cut (their referenced members become stubs), classes known on the target side with an identical API are left alone; reports **coverage** |
| `rewrite` | `porter rewrite` of closure + hubs with the rule packs, relocated into the mod's package |
| `install` | hub stubs, source passes (SRG → mojmap names, Java 9+ → 8 syntax, tile constructors, counted edits, unused imports), the adapter pack, your overlays → the target project |
| `loop` | `porter loop`: compile, classify, apply rules, write tasks for the rest |
| `compile` | javac, error-driven fixes of decompiler cast artefacts, aggregation of the remaining errors **by distinct symbol** |
| `build` | the project's own build, reobf collision check of the jar |
| `test` | scenario runs (`porter test diff ...`) |

After every stage porter writes `<work>/port-report.md` / `.json`: stage metrics, coverage, and an explicit
**not-ported** list — every generator skip (something the IR cannot express, e.g. a fluid in a crafting grid), every
dropped class, every hub whose feature is stubbed out, every unresolved symbol, each with a reason. Nothing is
dropped silently.

Two modes: **`full`** — the mod does not exist on the target version and is ported whole; **`delta`** — an addon
over a base that exists on the target version (e.g. a newer version's features backported as an addon to the old
version of the same mod): `closure.known` names the base's sources, and classes that are semantically identical
there are not copied.

## Requirements

| What | JDK | Notes |
|---|---|---|
| porter itself (CLI, build, tests) | **21** (Temurin 21 tested) | `JAVA_HOME` must point to it; Gradle 9.8.1 wrapper included |
| a Forge **1.19.2** mod (source side: build, testkit server) | **17** | ForgeGradle 5 |
| a Forge **1.7.10** mod (target side: RetroFuturaGradle) | **8** toolchain + **17–25** to run Gradle | GTNH/RFG convention; Jabel lets 1.7.10 code use Java 9+ syntax |
| NEI integration of generated 1.7.10 content (default; `--no-nei` to skip) | — | NotEnoughItems 1.0.5.x + CodeChickenCore + CodeChickenLib as `compileOnly` in the target project |
| testkit dedicated servers | 17 (1.19.2), 8 (1.7.10) | `testkit/env.example.properties` |

Also: Git, and bash (Git Bash on Windows) for the helper scripts. 16 GB RAM is enough; porter's own steps are
capped at 2–3 GB heap.

## Install

From a release: unzip `porter-0.1.0.zip` and put its `bin/` on your `PATH` (`bin/porter` for Linux/macOS,
`bin/porter.bat` for Windows). From source:

```sh
git clone https://github.com/Chasik8/mc-porter.git mc-porter && cd mc-porter
JAVA_HOME=/path/to/jdk-21 ./gradlew build          # tests + contract examples
JAVA_HOME=/path/to/jdk-21 ./gradlew :cli:distZip   # -> cli/build/distributions/porter-0.1.0.zip
export PATH="$PWD/cli/build/install/porter/bin:$PATH"
porter --help
```

Set `PORTER_CACHE` to a directory with a few GB free (downloads, symbol graph, mapping-derived indexes; never
committed anywhere). `PORTER_OPTS` passes JVM options to porter (e.g. `PORTER_OPTS=-Xmx3g`).

## Quickstart: port your mod

You need: your mod's **1.19.2 sources** (your own code, or an open-licence mod, or a decompile of a jar you own —
see [Legal note](#legal-note)), and an empty **1.7.10 mod project** (e.g. the GTNH ExampleMod template) that builds.

**1. Build the symbol graph once** (downloads and checksum-verifies the vanilla jars and mappings into the cache):

```sh
porter graph build --versions 1.19.2,1.18.2,1.16.5,1.12.2,1.7.10
porter graph query "net.minecraft.world.level.Level#getBlockEntity" --from 1.19.2 --to 1.7.10
```

**2. Let the target project tell porter its compile classpath.** Add to the 1.7.10 project's `build.gradle.kts`
and run `./gradlew porterClasspath` once:

```kotlin
tasks.register("porterClasspath") {
    val cp = sourceSets["main"].compileClasspath
    val out = layout.buildDirectory.file("porter-cp.txt")
    doLast { out.get().asFile.writeText(cp.files.joinToString("\n")) }
}
```

**3. Extract and generate the declarative content** (optional, for blocks/items/GUIs/recipes).
The 1.7.10 generator adds a NotEnoughItems integration (recipe and multiblock handlers in `<package>.nei`) by
default: the target project then needs NotEnoughItems 1.0.5.x + CodeChickenCore + CodeChickenLib as `compileOnly`
(RFG: `compileOnly(rfg.deobf(files("libs/NotEnoughItems-1.7.10-1.0.5.120-universal.jar", ...)))`); the mod still runs
without NEI installed. Pass `--no-nei` in the generate unit's `args` to leave it out:

```sh
porter extract --src my-mod-1.19.2/src/main/java --version 1.19.2 --out ir/
porter assets  --src my-mod-1.19.2/src/main/resources --from 1.19.2 --to 1.7.10 --ir ir/ --out assets-out/
```

**4. Write the port config.** Start from `contracts/examples/port-config/valid/full-minimal.port.yaml` (full port)
or `delta-all-stages.port.yaml` (every option). The core of it:

```yaml
schemaVersion: 1
name: mymod
mode: full
from: forge-1.19.2
to: forge-1.7.10
vars: { CACHE: /path/to/porter-cache }
project: ../mymod-1.7.10
classpath: { file: build/porter-cp.txt }
generate:
  units:
    - name: content
      args: [--version, "1.7.10", --ir, ir, --assets, assets-out, --modid, mymod, --package, com.example.mymod.gen]
closure:
  source: ../mymod-1.19.2/src/main/java
  prefixes: [com.example.mymod.]
  seeds: [com.example.mymod.machine.GrinderBlockEntity, com.example.mymod.machine.GrinderMenu]
  cut: [com.example.mymod.registry.]        # hubs: stop here, stub what the closure uses
rewrite:
  rules: [/path/to/porter/rules/composite, /path/to/porter/rules/packs/adapters]   # adapter rules go with adapters: bundled
  graph: ["${CACHE}/graph/out"]
  shimPackage: com.example.mymod.shim
install:
  srgMappings: "${CACHE}/graph/out/mappings/1.19.2.tiny"   # only for SRG-named (decompiled) sources
  adapters: bundled
compile:
  options: [--release, "8"]
build:
  command: [./gradlew, build]
  jar: build/libs/mymod-1.0.jar
  obfcheckPrefix: com/example/mymod/
```

`porter validate mymod.port.yaml` checks it against `contracts/port-config.schema.json`.

**5. Measure, then port:**

```sh
porter closure  --src ../mymod-1.19.2/src/main/java --prefix com.example.mymod. \
                --seed com.example.mymod.machine.GrinderBlockEntity --cut com.example.mymod.registry. --out closure/
porter coverage --src ../mymod-1.19.2/src/main/java --classes closure/port.txt --rules rules/composite --rules rules/packs/adapters --graph "$PORTER_CACHE/graph/out"
porter port --config mymod.port.yaml              # runs generate ... compile/build; resumes where it stopped
porter port --config mymod.port.yaml --status
```

**6. Close the remaining symbols.** `<work>/symbols.json` (also `porter symbols <work>/compile.log`) lists the
errors by distinct symbol, most frequent first. For each one: a rule in your rules dir (public if it is a plain
Minecraft/Forge mapping), an adapter, a hand-written hub (`install.stubs.overrides`) or a counted edit
(`install.edits`). Then `porter port --config mymod.port.yaml --from rewrite`. Repeat until 0; then `build` and
`test`. `<work>/port-report.md` says what was not ported and why.

All commands: `porter <command> --help`. Every command writes a JSON report to `reports/runs/` (or
`--reports-dir`) and exits 0 ok, 1 failed, 2 usage, 3 not implemented, 4 internal error.

Console output is short by default: at most about 20 lines (success, or the failure grouped by cause) and the path of
the full log in `<reports-dir>/logs/`; `porter port` prints one line per stage. `--verbose` (or `PORTER_VERBOSE=1`)
prints everything.

| Command | Purpose |
|---|---|
| `port` | the whole port from a config, resumable by stage |
| `closure`, `stubs`, `passes`, `castfix`, `symbols`, `obfcheck` | the building blocks of `port`, usable alone |
| `coverage` | share of a mod's distinct API symbols the existing rules already resolve |
| `drafts` | the loop's draft rules as issue-ready markdown |
| `extract`, `generate`, `assets` | IR from 1.19.2 sources; 1.7.10/1.19.2 code and assets from IR (1.7.10: NEI handlers unless `--no-nei`) |
| `rewrite`, `loop` | rule engine; compile/classify/fix loop |
| `graph build`, `graph query` | cross-version symbol graph |
| `test run`, `test diff`, `test roundtrip` | scenario tests on headless servers |
| `validate` | any contract document (IR, rules, graph, scenarios, traces, candidates, port configs, catalog) |
| `report` | aggregate metrics (planned; prints "not implemented") |

## Supported versions

| | Status |
|---|---|
| Extract: Forge 1.19.2 | works (13 concepts) |
| Generate: Forge 1.7.10 | works |
| Generate: Forge 1.19.2 | works (round-trip only) |
| Symbol graph: 1.19.2, 1.18.2, 1.16.5, 1.12.2, 1.7.10 | works |
| Rules: 1.19.2 → 1.7.10 | 767 hand-written/curated: 254 core (`rules/composite`) + 513 adapter rules (opt-in pack `rules/packs/adapters`: 374 from the reference closure port, 139 modern-API rules from the TorchMaster demo) + ~9,200 generated from the graph |
| Adapter pack 1.19.2 API → 1.7.10 | 125 classes (block states, block entities, menus/screens, capabilities, fluids, NBT, mod lifecycle, configs, deferred registers, entity types, commands, ...) |
| Testkit: Forge 1.19.2 server, Forge 1.7.10 server + client | works |
| Testkit: GUI checks on 1.19.2 and 1.7.10 clients (captures, structural + image diff) | works |
| Other pairs (1.12.2, 1.16.5, NeoForge, Fabric) | not started; contributions welcome |

## Validation

porter was validated on a private port of a large tech mod (not open-licence, so nothing of it is in this
repository): a 1.19.2 subsystem with its dependency closure, ported into a 1.7.10 addon (`mode: delta`).

- Closure: 281 classes / 28k lines copied, 45 hubs cut (28 generated stubs, 17 hand-written). The first pass made
  10,533 automatic rewrites and left 3,167 javac errors over **610 distinct symbols**; 14 resolution rounds took it
  to 0 (610 → 466 → 449 → 404 → 353 → 319 → 246 → 193 → 142 → 63 → 23 → 8 → 2 → 0).
- Scenarios: equal to the original 1.19.2 version tick for tick (363/363, 402/402 and 402/402 samples).
- What it left behind publicly: 374 generic adapter rules and the 81-class adapter pack. `porter coverage` on the same
  closure (733 distinct platform API symbols): 34.2% resolved by the core rules + symbol graph alone, 58.9% with the
  adapter pack (optimistic for that closure, since the pack was written for it).
- The whole port is one config; re-running `porter port` from a clean checkout rebuilds the same jar byte for byte.

## What it costs to port a mod

**Deterministic pipeline: 0 AI tokens, machine time only** (home PC, 4 threads, 16 GB): symbol graph ~7.5 min
(first run only), extract ~30 s, generate/assets seconds, closure + rewrite of ~300 classes a few minutes, one
compile round 1–2 min, one scenario ~1 min. A full pass is roughly 15–30 minutes.

**AI: only for symbols the rule base doesn't know yet.** Cost scales with the number of distinct unresolved API
symbols, not with lines of code. Measured on the closure above: 610 distinct symbols, ~0.33M tokens and ~4.4
hours of AI model time (an LLM, token counts as reported by the tool, approximate; billing depends on caching). A comparable mod on
the same version pair starts with the 374 adapter rules from that run; `porter coverage` tells you your starting
point before you spend anything.

## Status and limitations

- One version pair works end to end. Other pairs need extractors or generators per concept.
- Visuals need a human: models more complex than cubes are listed as `needs-manual-render`, and GUI looks are not
  checked. Some recipe shapes have no IR concept yet (fluids in crafting grids); they appear in the not-ported list.
- GUI checks (testkit, RFC 005) compare captures of both versions structurally and by image; looks still need a
  human verdict when the diff is `acceptable-diff`.
- `pattern` (Spoon template) rules are in the contract but not applied by the engine. `porter report` is planned.
- Coverage counts imported types and SRG-named members; sources with mojmap member names only count types.
- The adapter pack compiles only against a 1.7.10 MCP jar (`-Pporter.mc1710Jar`), so CI checks the rules but not the
  adapters' compilation.

## Contributing

The project grows from users' ports. Public: version-pair packs (rules, shims, adapters) and mod profiles (a port
config + scenarios + generic rules, listed in `ports/catalog.yaml`). Local only: the ported output of mods that are
not open-licence, which everyone runs themselves against their own copy of the original. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0 — see `LICENSE` and `NOTICE`. Copyright The mc-porter contributors.

## Legal note

- Porter ships **no mod code** and **no Mojang or MCP mappings**. The symbol graph and vanilla indexes are built
  locally from official sources and verified by checksum. They stay in your cache.
- Only port mods you have the rights to port: your own, open-licence ones, or ones whose licence or author allows
  it. Keep ports of All-Rights-Reserved mods private; publish a ported jar only for open-licence mods or with the
  author's permission.
- Minecraft is a trademark of Mojang Studios. Porter is not affiliated with Mojang or Microsoft.

## По-русски

Porter переносит моды Forge между версиями Minecraft. Декларативная часть (блоки, предметы, GUI, рецепты,
мультиблоки) извлекается в JSON IR и генерируется под целевую версию. Логика копируется замыканием зависимостей и
переписывается правилами с обязательными тестами; всё, что правила не покрыли, всплывает как ошибки компиляции,
сгруппированные по уникальным символам, — каждый решается один раз (правило, адаптер или учтённая правка), и
детерминированный конвейер прогоняется заново. Всё запускается одной командой `porter port --config <mod>.port.yaml`.
Первая пара: 1.19.2 → 1.7.10. Переносите только моды, на которые у вас есть
права. Новые правила и пары версий приветствуются, см. CONTRIBUTING.md.
