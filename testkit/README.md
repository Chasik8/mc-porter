# testkit — scenario runs, traces and differential tests

`testkit` runs version-independent scenarios (`contracts/scenario.schema.json`) inside real Minecraft
servers/clients of two platform versions, records traces (`contracts/trace.schema.json`) and compares them.

```
testkit/
  runner/        Java 21 module :testkit:runner — porter test run | diff | roundtrip
  mod-common/    executor shared by both test mods (Java 8, Gson only)
  mod-1.19.2/    test mod for Forge 1.19.2 (43.5.2)  — setup.sh + build.sh, no ForgeGradle
  mod-1.7.10/    test mod for Forge 1.7.10 (10.13.4.1614) — setup.sh + build.sh, reuses RFG's decompiled MC
  scenarios/     public scenarios (vanilla blocks only)
  ir/            hand-written IR used by the public scenarios (vanilla furnace menu)
  env.example.properties   launch configuration (copy to env.local.properties)
```

## How a run works

1. **Plan.** The runner compiles the scenario for one version: IR ids -> registry names of that version (id map),
   canonical sample keys -> keys of that version (inverse `keyMap`), `build_multiblock` -> `place` ops from the IR
   `multiblock` node (rotated to `facing`, controller placed last), and every key an `assert` reads is sampled right
   before the assert (implicit sample). The plan is a flat JSON list of ops (`place`, `fill`, `insert`, `interact`,
   `open_gui`, `tick`, `sample`).
2. **Launch.** A fresh run directory (`--work`, default `<tmp>/porter-testkit/<scenario>-<version>/attempt-N`) gets
   `mods/` (test mod + mod under test + `--mods`), `server.properties` (void flat world), `plan.json`. Logic
   scenarios run on a **dedicated server**; `side: client` scenarios run on a **client** (GUI checks).
3. **Execute.** The test mod is inert unless `-Dporter.plan` is set. On a dedicated server it runs the whole plan
   from the server-started event in a tight loop of real server ticks (`MinecraftServer.tick()` /
   `tickServer()` — every world, entity, tile entity and event tick happens, but without the 50 ms pacing):
   the 96 000-tick barrel scenario runs in ~2 s (1.7.10) / ~8 s (1.19.2). Chunks touched by the plan are force-loaded
   (ForgeChunkManager ticket / `setChunkForced`), entity ticking never pauses. It writes `raw.json` and stops.
4. **Trace.** The runner normalises values back to IR ids (id map), evaluates assertions, compares GUI dumps with the
   IR `menu` node, copies screenshots to `reports/screens/` and writes `<scenario>-<version>.trace.json`.
5. **Diff.** Traces are compared per canonical key with the scenario's tolerances.

**Batches.** `test run` / `test diff` / `test roundtrip` with several `side: server` scenarios run them all in ONE
Minecraft launch per version and mod set (two launches for a diff, however many scenarios): the runner writes a batch
plan (`{"format": "porter-testkit-batch/1", "plans": [...]}`, every plan with its own raw result file), the test mod runs
the plans one after another in the same server and resets the world in between: every block in the chunks the plans
touched (and their neighbours) is removed through `setBlock` (so the mods under test see the removals), entities are
removed, the fake player's inventory is emptied, two server ticks flush tile entities. Each scenario starts with one
warm-up tick after its chunks are loaded (chunks become block-entity ticking during the first tick; this way a scenario
starts in the same state as the first one of a launch). Traces are the same as with one launch per scenario
(`--no-batch`): the public furnace scenarios are the check (`furnace-untouched` fails if anything leaks from the
smelting runs before it). Client scenarios (GUI) are never batched. If the game dies part way, the scenarios without a
result are launched again (the likely culprit last).

Timeouts and retries: every launch has a wall-clock limit (`--timeout`, default 600 s; the mod also stops itself
30 s earlier with status `timeout`), a hung process tree is killed, and the launch is retried (`--retries`, default 1)
when it hung or crashed without a result. `maxTicks` in the scenario is the tick budget (status `timeout` beyond it).

## Commands

```
porter test run   --scenario <f|dir>[,...] --version forge-1.7.10 [--mod <jar>] [--mods <jar>,...] [--ids <idmap>]
                  [--ir <f|dir>] [--env <f>] [--work <dir>] [--out-dir <dir>] [--screens <dir>] [--timeout s] [--retries n]
                  [--no-batch]
porter test diff  --scenario <f|dir> [--a <jar|trace>] [--b <jar|trace>] [--a-version v] [--b-version v]
                  [--a-mods ..] [--b-mods ..] [--a-ids f] [--b-ids f] (+ the common options above)
porter test roundtrip --scenario <f|dir> --mod <original jar|trace> --roundtrip <rebuilt jar|trace> [--version v] ...
```

- `--a/--b` default versions: from the jar (`mods.toml` -> forge-1.19.2, `mcmod.info` -> forge-1.7.10), else
  forge-1.19.2 / forge-1.7.10. Without a jar the scenario runs on vanilla + test mod. A `*.trace.json` reuses an
  existing trace instead of launching Minecraft.
- Outputs: traces `<out-dir>/<scenario>-<version>.trace.json` (roundtrip: `-original`/`-roundtrip` suffix),
  diff reports `<out-dir>/<scenario>.diff.json`, run report `reports/runs/test-<cmd>.json`.
- Exit code 0 only if every scenario passed on every side and (diff/roundtrip) the traces are equal.
- `test roundtrip` compares an original mod with its rebuild on the same version; building the rebuild
  (extract -> generate -> compile) is the extract-generate-compile pipeline, so `--roundtrip` must be given (exit 3 otherwise).
- Wrap every invocation that launches Minecraft in the machine queue (`mc-run.sh testkit porter test ...`).

## Sample keys (probe vocabulary)

Key = `<probe>[:<target>][/<path>]`; target `[x,y,z]` may be followed by `#<index>` (slot / tank) and `@<side>`
(`down up north south west east`). `/path` navigates into the value (object keys / array indices, `/`-separated).
Values are JSON; `null` = absent / empty. Item stacks are `{item, count[, damage][, nbt]}` with ids normalised to IR
ids; fluids `{fluid, amount, capacity}`.

| Probe | Value |
|---|---|
| `block:[x,y,z]` | registry id of the block (1.7.10 `lit_furnace` vs 1.19.2 `furnace[lit=true]` differ: ignore or map) |
| `block_state:[x,y,z][/prop]` | 1.19.2: state properties `{name: value}`; 1.7.10: `{meta: n}` |
| `nbt:[x,y,z][/path]` | block-entity NBT without id/x/y/z (compounds -> objects with sorted keys, lists -> arrays) |
| `item:[x,y,z]#slot` | stack in a slot (Container/IInventory, else `getStackInSlot(int)`, else the item capability; `@side` = sided view) |
| `inv:[x,y,z][@side]` | all slots as an array |
| `fluid:[x,y,z][#tank][@side]` | tanks (IFluidHandler / fluid capability) |
| `energy:[x,y,z][@side]` | Forge Energy stored (1.19.2), `getEnergyStored(ForgeDirection)` (1.7.10, RF style); other energy APIs (e.g. EU): use `field:` |
| `field:[x,y,z]/name[/name...]` | reflective field chain on the block entity (numbers, strings, booleans, enums lower-case, arrays) |
| `call:[x,y,z]/method[/method...]` | reflective no-argument method chain |
| `player:inv`, `player:held` | the scenario's fake player: non-empty slots `{..., slot}` / held stack |
| `drops:[x,y,z]` | item entities within 3 blocks, sorted |
| `registry:<blocks\|items\|fluids>[/prefix]` | sorted registry names (for writing id maps) |
| `spawn_check:[x,y,z]/<entity>` | Forge natural-spawn check (`CheckSpawn`, 1.19.2: `MobSpawnType.NATURAL`) for a fresh entity at the block centre: `allow` / `deny` / `default`. Entity = 1.19.2 id (`minecraft:zombie`) or 1.7.10 `EntityList` name (`Zombie`): map one to the other with `keyMap` |
| `light:[x,y,z]` | block light at a position (0..15; the 1.19.2 light engine is settled after every tick) |

Field/method names are version-specific: write the canonical key in the scenario and map the other version's key
in `keyMap` (e.g. `forge-1.19.2: {"field:[1,65,0]/isValid": "field:[1,65,0]/formed"}`).

Sampling: `sample {what}` samples once; `sample {every: N, what}` samples now and every N ticks during later `tick`
steps (samplers accumulate). Several samples of one key in the same tick are kept in order (before/after an
interaction); exact repeats are dropped.

## Assert expressions

```
expr := or ;  or := and ('||' and)* ;  and := not ('&&' not)* ;  not := '!' not | cmp
cmp  := sum (('=='|'!='|'<'|'<='|'>'|'>=') sum)?
sum  := prod (('+'|'-') prod)* ;  prod := unary (('*'|'/'|'%') unary)* ;  unary := '-' unary | primary
primary := number | 'string' | "string" | true | false | null | tick | '(' expr ')' | func '(' args ')' | ref
ref  := <sample key>[.field|[index]]*   |  `any key`  |  bare probe name (the only sampled key with that probe)
```

- A key ends at whitespace or one of `( ) = ! < > & | + * % ,` outside brackets; `.` outside brackets starts an
  accessor (`item:[0,64,0]#2.count`). Write binary minus with spaces.
- Keys are canonical; the value is the latest sample taken at or before the assert (same tick: this or an earlier
  step). Keys read directly are sampled implicitly; keys used only inside series functions are not.
- Comparisons: numbers numerically, strings lexically; ordering against `null` is false; `==`/`!=` work on anything.
- Functions over a key's samples (`from`/`to` ticks optional, default all / up to now): `avg min max sum count
  first last (key[, from[, to]])`, `delta(key, from, to)` = last - first in the window, `at(key, tick)`.
  Scalar: `abs round floor len exists contains(haystack, needle)` (contains on the JSON text).
- An assert must evaluate to a boolean; failures carry the values of every referenced key in the trace message.

## Id maps (testkit-owned, YAML/JSON)

```yaml
version: forge-1.7.10
blocks:  { "examplemod:boiler": "examplemod:boiler@2", "hostmod:battery": { id: "HostMod:blockElectric", meta: 2 } }
items:   { "examplemod:mug_beer": "examplemod:mugDrink@16",
           "alias:fuel_can_full": { id: "HostMod:item.fuelCan", nbt: { value: 10000, maxValue: 10000 } } }
fluids:  { "examplemod:steam": "steam" }
```

`"id@meta"` or `{id, meta, nbt, count, state}`. Unmapped ids pass through (vanilla ids mostly match;
`minecraft:<fluid>` becomes the bare 1.7.10 fluid name automatically). Typed NBT literals: `"5b" "3s" "7L" "1.5f"
"2.0d"`; plain integers are int, decimals double. The same table maps sampled values back to IR ids (meta-exact
first; entries with `nbt`/`count` are aliases and are not used backwards). `alias:*` ids stand for stacks with count
or NBT until RFC 001 lands (contracts/rfc/001-scenario-item-stacks.md).

## GUI checks (`side: client`, RFC 005)

Client scenarios run a real dev client of either version (1.7.10 and 1.19.2), 854x480 at **GUI scale 1**, with the
window moved off-screen (1.7.10: before it is created, by a no-transformer loading plugin in the test mod; 1.19.2:
during mod construction, it is visible for a few seconds) and the mouse never grabbed (`-Dfml.noGrab`, released
every tick on 1.19.2). The client creates a flat creative world; the plan runs on the integrated server; GUI ops are
executed **on the client like a player** (`GuiDriver` in mod-common):

| step | what happens |
|---|---|
| `open_gui {at}` | real right-click on the block's top face with an empty hand (events, packets, server-side `openGui`) |
| `open_gui {item}` | the item is put into hotbar slot 0 and used (`sendUseItem` / `useItem`): item GUIs such as books |
| `gui_input {click \| key \| call}` | mouse click (GUI-relative), key press (LWJGL2 / GLFW key name) or a public method of the screen (test hook) |
| `gui_input {}` | just a new capture of the open screen (after steps that changed the block's state) |
| `close_gui` | closes the screen |

`keepOpen: true` keeps the screen open after the capture (`gui_input` captures always keep it). After 10 ticks of
settling, the next frame is recorded: a recording font is swapped in for that frame (every drawn string with
position and width), the framebuffer is saved, and the structure is dumped (screen class, GUI rectangle, slots).
The runner crops the frame to the GUI rectangle and writes `<out-dir>/<scenario>-<version>.gui/` (`gui.json` +
`<capture>.png`; error lines logged while the op ran are kept as `renderErrors`). Non-container screens use
`check.size` (centred) as GUI rectangle.

**Comparator** (`test diff` of a client scenario, or `test run` for one version; reusing traces only re-compares):
- `mode: diff` — structural diff first: GUI size, machine slots matched by position (container order may differ),
  player inventory grid, texts (identical with digits collapsed, else equivalent through a lang key whose
  translation produces both texts; lang read from the mods' jars), dynamic indicators (`dynamicFrom`: changed
  regions between two captures of the same screen, outside slots/texts, matched by position). Then the image diff:
  redmean colour distance with 1 px shift tolerance, masked texts, slot contents and `check.mask`; heatmap.
  Verdict `equal` (<= 0.5 % differing pixels, no notes), `acceptable-diff` (notes, <= `maxDiff`, default 10 %),
  `broken` (missing slots/texts/indicators, render errors, raw lang keys, overflowing text in the port, failed op).
  Problems that the original shows too are notes, not port bugs. A `check.mask` region is not compared at all:
  neither its pixels nor a text of A drawn there (a missing one is an acceptable note) - for parts of the original
  the target cannot have (document why in the scenario).
- `mode: structural` — one version alone (e.g. a book without counterpart): renders without logged errors,
  non-empty, every text inside `textBoxes`, no raw lang keys, `texts` (lang keys) drawn. Verdict `ok` / `broken`.
- `mode: absent` — no screen may open within 40 ticks (blocks without GUI).

Report: `<out-dir>/gui-report/gui-report.html` (side-by-side A/B at 2x, heatmap, findings) and `gui-report.md`
(verdict table). A broken GUI makes the command fail. Example: `testkit/scenarios/furnace-gui-visual.scenario.yaml`
(`equal` on both versions); schema examples in `contracts/examples/scenario/*/gui-check*.yaml`.

The older IR-menu check still runs for `open_gui {menu}` (size + slots vs the IR `menu` node, trace `gui`).

1.19.2 client install (once): `testkit/mod-1.19.2/setup-client.sh` (vanilla libraries sha1-checked, Forge
`--installClient`, no assets: no sounds, English only) also builds the client compile jar; `build.sh` then includes
the client driver. Env keys: `forge-1.19.2.client.dir`, `.client.versionId`, optional `.client.assets`,
`<version>.client.window` (`offscreen` default, `normal`), `<version>.client.guiScale` (1).

## Building the test mods (local Windows)

Every Gradle/heavy job goes through the machine queue (`heavy-run.sh testkit ...`), every Minecraft launch through
`mc-run.sh testkit ...` (at most one Minecraft on the machine).

1. Install the Forge servers once (shared cache `%PORTER_CACHE%`):
   `java17 -jar forge-1.19.2-43.5.2-installer.jar --installServer <cache>/forge-1.19.2-server`,
   `java8 -jar forge-1.7.10-10.13.4.1614-1.7.10-installer.jar --installServer` (in `<cache>/forge-1.7.10-server`,
   with `minecraft_server.1.7.10.jar` pre-copied from RFG's cache).
2. `testkit/mod-1.19.2/setup.sh` (+ `setup-client.sh` for GUI scenarios) — builds `moj2srg.tsrg` (Mojang client.txt + MCPConfig joined.tsrg) and a
   mojmap-named compile jar from the installed SRG server + Forge patches with ForgeAutoRenamingTool `--reverse`
   (no decompilation; ~1 min, < 1.5 GB heap). `testkit/mod-1.7.10/setup.sh` — copies RFG's MCP-named
   `recompiled_minecraft-1.7.10.jar` (from any RFG 1.7.10 workspace) and builds `mcp2srg.tsrg` from Forge's
   `packaged.srg` + FML conf csvs.
3. `testkit/mod-*/build.sh` — javac (`--release 17` / `--release 8`) + reobfuscation with ForgeAutoRenamingTool;
   each build fails if any Minecraft member reference survived unmapped (NoSuchMethodError guard).
4. Copy `testkit/env.example.properties` to `testkit/env.local.properties` and adjust paths.
5. `gradlew :cli:installDist`, then e.g.
   `mc-run.sh testkit cli/build/install/porter/bin/porter test diff --scenario testkit/scenarios/furnace-smelt.scenario.yaml`
   and `... test run --version forge-1.7.10 --scenario testkit/scenarios/furnace-gui.scenario.yaml --ir testkit/ir`.
   The client opens a window for ~20 s (pause-on-lost-focus is disabled in its options.txt).

Why no ForgeGradle 5 / second RFG workspace: both decompile Minecraft (minutes, 3-4 GB heap) per project; the test
mods only need to compile against named classes and be reobfuscated, which the installer's own tooling does.

## Linux CI notes

- Same scripts (POSIX shell; `cygpath` is only used on Windows). Install JDK 8, 17, 21; install both Forge servers
  into a cache directory and point `PORTER_CACHE`/the env file at it. The 1.7.10 setup needs an MCP-named 1.7.10 jar
  (run RetroFuturaGradle once in any 1.7.10 project, e.g. the GTNH ExampleMod template, and set `RFG_RECOMPILED`).
- Logic scenarios need no display. GUI scenarios: run the client under Xvfb with Mesa's software rasterizer:
  `Xvfb :99 -screen 0 1280x720x24 & export DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe`
  (packages: `xvfb mesa-utils libgl1-mesa-dri`). LWJGL 2 natives for Linux come from
  `lwjgl-platform-2.9.x-natives-linux.jar` (set `forge-1.7.10.client.natives` to the extracted directory) and need
  `libxrandr2 libxxf86vm1` and an `xrandr`-capable X server (Xvfb provides it).
- Keep `--timeout` generous on CI (llvmpipe start-up is slow) and `--retries 1`: 1.7.10 clients are known to hang
  in CI occasionally; a hung run is killed and relaunched in a fresh directory.
- Scenarios of mods that are not open-licence stay local and never run in public CI; only `testkit/scenarios` does.

