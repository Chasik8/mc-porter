# Changelog

All notable changes. Versions follow [Semantic Versioning](https://semver.org/); before 1.0 minor versions may
break the CLI and the non-frozen formats.

## [0.2.0] - 2026-10-22

### Added
- Port profiles that ship nothing of the original mod (RFC 009): new stages `setup` (one-time steps, skipped when what
  they create exists) and `derive` (every run) with steps `porter`, `command`, `download` (sha256-pinned), `unzip`,
  `copy`, `composite` (an image edit shipped as its changed pixels) and `delete`; `patches` applied after a stage,
  from one tree to another (e.g. the user's own decompile -> the target project); `plugins` (`PORTER_PLUGINS`: extra
  jars such as a mod-specific extract pattern pack); `generate.langSections`; install pass `assetns`
  (`assetNamespaces`) and `install.langKeys`; `${NAME:-default}` and `${CONFIG_DIR}` in configs.
- `porter decompile` (CFR 0.152 from Maven Central, sha256-pinned; resources copied), `porter select` (one unit's IR
  from a whole-mod extract + an IR overlay; `--make-overlay` records hand edits of an IR as data), `porter patch
  make|apply` (unified diffs across trees; `--blind` patches carry no text of their base, which is pinned by sha256),
  `porter assets --select` (only the assets an IR references).
- `scripts/gradle/porter-classpath.gradle`: Gradle init script for `build/porter-cp.txt` and the javac plugins.

### Changed
- Rewriter: two rules that write the same code under different placeholder names are no longer `ambiguous`.

## [0.1.0] - 2026-10-20

First public release: one version pair (Forge 1.19.2 → Forge 1.7.10) end to end, as one CLI.

### Added
- `porter port --config <mod>.port.yaml`: the whole port in stages (generate, closure, rewrite, install, loop,
  compile, build, test), resumable (`--status`, `--from`, `--to`, `--stage`, `--restart`, `--dry-run`), with
  `<work>/port-report.{md,json}` listing stage metrics, coverage and everything not ported, with a reason.
- Port modes `full` (mod absent on the target version) and `delta` (addon over an existing base; identical classes
  left to the base).
- Building blocks as commands: `closure` (recursive copy closure, hub frontier, identical-API check), `stubs` (hub
  stubs), `passes` (srgmoj, java8, tector, edits, imports), `castfix` (decompiler cast/generic artefacts),
  `symbols` (javac errors by distinct symbol), `obfcheck` (reobf collision check), `coverage` (share of a mod's
  distinct API symbols resolved by existing rules), `drafts` (draft rules → issue-ready markdown).
- Contracts (RFC 004, additive): `port-config` and `port-catalog` schemas with examples; `porter validate` knows
  both.
- 374 public adapter rules (`rules/packs/adapters/1.19.2__1.7.10/adapters-{mc,client,libs}.rule.yaml`, opt-in: use together with `install.adapters: bundled`) and the generic
  1.19.2-API adapter pack (81 classes, `rewrite/shim/src/adapters`), found by a real closure port; all with
  before/after tests.
- Distribution `porter-0.1.0.zip` (`./gradlew :cli:distZip`) with `bin/porter` and `bin/porter.bat`, the rule
  packs, schemas, examples and docs.
- Community files: `ports/catalog.yaml`, issue templates (port request, rule bug, new version pair), PR template,
  CODEOWNERS (placeholders), `scripts/publish-check.sh` (heuristic publish-safety scan, run in CI).
- NotEnoughItems integration in the 1.7.10 generator (recipe types, custom recipes, multiblock structures; on by
  default, `--no-nei` to skip; NEI 1.0.5.x + CodeChickenCore + CodeChickenLib as compileOnly). IR schemaVersion 2
  (RFC 003, additive): fluid ingredients/results, duration, machines, notes, output chances, hidden items.
- Rule language (RFC 006, additive to the rule contract): `match.pattern` statement/expression patterns with
  placeholders, `match.declaration` with `edit` (add supertypes/members, initializer, body, remove), position and type
  preconditions (`extends` position, receiver type), `$target(...)`, empty replacements for statement patterns.
  The reference closure port's 43 counted edits: 23 are rules now.
- GUI testkit (RFC 005): `open_gui` captures, `gui_input`, `close_gui`, `check`; GUI captures on 1.19.2 and 1.7.10
  clients, structural and image comparison, HTML/Markdown report. GUI fixes found by it: empty-collection hub stubs,
  1.7.10 sprite/atlas/texture shims, client tile position, screen titles, legacy names and icons.
- NEI generation: recipe types, custom recipes and multiblock structures for the generated 1.7.10 content (on by
  default, `--no-nei`), from IR schemaVersion 2 (RFC 003).
- TorchMaster profile (`ports/torchmaster/`, MIT mod, catalog entry): 28 classes, 6 scenarios, 67/67 samples equal,
  from-scratch `porter port` in 4 min 51 s, 113 → 0 distinct symbols in 4 rounds, 0 call-site edits; with it 139
  modern-API rules (`modern-api-*.rule.yaml`) and 44 adapter classes (125 in the pack), `testkit` probes
  `spawn_check` and `light`, `./gradlew` handled on Windows.
- Short console output by default: every command prints at most about 20 lines (success, or failure grouped by
  cause) and the path of the full log (`<reports-dir>/logs/`); `--verbose` (or `PORTER_VERBOSE=1`) restores the full
  output. `porter port` prints one line per stage (stage, status, duration, key metric). JSON reports unchanged.
- Batched scenarios: `porter test run|diff|roundtrip` run all server scenarios of a version and mod set in ONE
  Minecraft launch (world reset between scenarios, one warm-up tick each); `--no-batch` for one launch each. New
  public scenarios `furnace-smelt-glass` and `furnace-untouched`.
- Earlier modules, now released together: symbol graph (1.19.2/1.18.2/1.16.5/1.12.2/1.7.10), Forge 1.19.2
  extractor (13 IR concepts), Forge 1.7.10 and 1.19.2 generators, Spoon rewriter with tested YAML rules, compile
  loop with candidate log and rule drafts, asset converter chain, testkit (headless scenario runs and trace diff,
  GUI checks on 1.19.2 and 1.7.10 clients, RFC 005).

### Changed
- `porter loop` runs Gradle directly unless a queue wrapper is given (`--queue-wrapper` or
  `$PORTER_QUEUE_WRAPPER`); there is no machine-specific default any more.
- Decision 0008: closure ports may use the adapter pack (added one distinct symbol at a time) in addition to the thin
  core shim.

### Known limitations
- `porter report` is planned (prints "not implemented"); lambdas, loops, `switch` and anonymous classes do not
  match inside `pattern` rules.
- The adapter pack compiles only with a 1.7.10 MCP jar (`-Pporter.mc1710Jar`).

[0.1.0]: https://github.com/Chasik8/mc-porter/releases/tag/v0.1.0
