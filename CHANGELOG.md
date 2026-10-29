# Changelog

All notable changes. Versions follow [Semantic Versioning](https://semver.org/); before 1.0 minor versions may
break the CLI and the non-frozen formats.

## [Unreleased]

## [0.3.0] - 2026-10-29

### Fixed
- `assets --select --literals` keeps texture and sound path literals of the listed sources (`"textures/gui/x.png"`, `ResourceLocation(ns, "textures/...")`, `"ns:..."`, with or without `textures/` prefix and `.png`/`.ogg`) when the file exists in the source assets, with its `.mcmeta`; before only lang keys were kept and GUI backgrounds were missing (white panels).
- testkit GUI structural check: a widget with an empty label (width 0, e.g. an icon tab / `IconButton` drawing an item) is not reported as "text overflows its box" even when it sits outside the text frame (false "broken" on tab icons above the panel).
- Adapter pack: `ResourceKey` instances are interned per (registry, location) in `create`, `createRegistryKey` and `dimension`; 1.19.2 code compares keys with `==`, so a dimension decoded twice was not the same object (teleporter targets never matched).
- Adapter pack: `Items.MELON` is the melon block item in 1.19.2 (`Item.getItemFromBlock(Blocks.melon_block)`), `Items.MELON_SLICE` is `Items.melon`; the old rule mapped the block to the slice (a recipe with both read slice -> slices).
- `porter precheck`: the scratch rewrite source root also holds the sources that the closure and frontier classes import (two rounds), so a hub member's type (for example a config field) resolves and the rules fire as in the full run; before, the counted edits of such sites reported "no longer matches".
- scengen: `insert_item` steps now come before the `insert_energy` run, so a long power run overlaps the processing instead of filling the buffer before the input exists (the induction furnaces never finished).
- `bodies check`: the setter marker `// porter:body <key> set` of an Ext field now splices. A bodies entry carries the setter body in the optional `set-body:` field (shimgen uses it too); without it the generated setter stays.
- shimgen: a generated override whose 1.19.2 return type is a covariant subtype uses the return type of the nearest generated ancestor method, so the generated code is a valid override (fixes `AbstractSelectionList.getFocused()`).
- `bodies check` and `upfront --check-bodies` need no `--cp`: the default 1.7.10 class path is the cache MCP jar, the libraries of the Forge 1.7.10 launcher version json (`--mc-dir`, `--version-id`) and the adapter source dirs (`--adapters`).

### Added
- `porter port` install stage: every call site in kept code that targets an inert hub-stub member (`// porter:hub-stub inert`) is listed (`inert-hub-calls.json`, `inertHubCalls`/`inertHubCallCount` in `port-report.json`, a section in `port-report.md`, a WARNING summary at the end of the stage). Catches silently missing sounds and GUI item icons at build time. Unit test `InertHubCallsTest`.
- `--id-map` accepts a `tags:` section (`{tagId: oreDictName}`): the OreDictionary name for an ingredient tag with no conventional name. Before, such a tag was used as the ore name, which nothing registers, so every recipe using it could never be crafted.
- Testkit probe `recipes:<registry name>/craftable` (1.7.10) counts only crafting recipes whose ingredients all resolve (no empty OreDictionary entry); `gen-block-smoke.py --recipes` asserts it.
- Testkit client mode (1.7.10): never touches the user's mouse. The driver replaces `Minecraft.mouseHelper` with a no-op helper (vanilla `ungrabMouseCursor` warps the OS cursor to the window centre when a GUI opens; `-Dfml.noGrab` only guards the grab), next to `-Dfml.noGrab=true` and `pauseOnLostFocus:false`. A click without capture that closes the screen (a teleport row) is no longer an error.
- Testkit client mode: `gui_input: {press: {id|index|label|kind, via?}}` presses a control of the open screen like a player
  (`GuiButton.actionPerformed`, or a mouse click on the centre of a 1.19.2-style widget of a ported screen), so the real client
  packet is sent; every GUI capture dumps the screen's `buttons` into the trace; probe `real_player:pos` / `real_player:container`
  for the player the client controls; `invoke_tile` arguments `{"$tile": [x,y,z]}` / `{"$new": "pkg.Class", "args": [...]}`.
  Unit test of the step compilation (`GuiCompareTest.compilesPressStep`), README "Pressing buttons".
- Adapter pack: generic Forge networking bridge. 1.19.2 `SimpleChannel` code (`newSimpleChannel`, `registerMessage`, `send`,
  `sendToServer`, `reply`, `PacketDistributor` PLAYER / ALL / SERVER / DIMENSION / TRACKING_CHUNK / TRACKING_ENTITY(_AND_SELF) /
  NEAR, `NetworkEvent.Context` `getSender` / `enqueueWork` / `setPacketHandled`) now ports without edits: one 1.7.10
  `SimpleNetworkWrapper` per channel, every message travels in one generic `IMessage` (id + encoded bytes) and is dispatched by
  id (`dev.porter.shim.mc.SimpleChannel`, `PacketDistributor`, `NetworkEvent`, `NetworkRegistry`, `net.MessageTable`). 32 rules
  in `modern-api-networking.rule.yaml` (spec `modern_api_spec.py`), unit tests for the id table (round trip, dispatch by id,
  null decode, duplicates). Replaces hand-written per-mod packet transports; see `rewrite/README.md`, "Networking bridge".
- Upfront bodies DB: 149 generic bodies for level-world and holder-registry members (`level-world-3.yaml`, `holder-registry-4.yaml`) and shared helpers `bodies/helpers/B*.java` (NBT mirror tags, random source, private event bus, registry registration).
- Upfront bodies batches 5-6 (rendering, network): 153 of 207 TODO bodies filled, generic helper `CSlots` for field-less mirrors; compile-checked with `porter bodies check`.
- Upfront bodies DB: bodies for batches 7 (client GUI, block, capability members; 100 keys) and 8 (entity/food builders,
  positions, boxes, command and particle types; 83 keys) with generic helpers `Dd*` (per-instance state, reflection,
  text conversion, block drops). All compile in one `porter bodies check`.
- `scripts/recurring/promote_rules.py`: the report lists `overrides` (profile rules that match like a pack rule but replace differently) and `--drop-ids` removes them from a profile together with the promoted ones, so a pack rule is never matched twice.
- Testkit: `interact` takes `use: finish` (1.7.10): the held item is used in the air and the use is completed like a held
  right-click, so food and drinks apply their effects; probe `player:effects` lists the fake player's potion effects.
  `invoke_tile` converts a string argument to an enum parameter (a direction: `"west"`).
- `scripts/ci/gen-machine-scenarios.py`: writes functional scenarios for machines from a table (a spec of cases: block,
  items per slot, fluids, energy, expected inventory content or any assert, raw steps), laid out at chunk-interior
  positions (some tiles, e.g. reactors, only tick when the chunks around them exist); `--group N` bounds the scenario
  size. Self-test: `scripts/ci/test-gen-machine-scenarios.py`.
- Adapter pack: generic Minecraft/Forge 1.19.2 -> 1.7.10 mappings that ports had written by hand are now public rules
  (`rules/packs/adapters/1.19.2__1.7.10/generic-*.rule.yaml`, vanilla item/block constants, entity/world/NBT/sound calls,
  client and library types) together with the helper classes they call (`dev.porter.shim.mc`: placeholders for
  absent modern items, saved data, ingredient io, chunk loading, pipe/world helpers, ...). The next port gets them for free.
  `scripts/recurring/promote_rules.py` does the move from a profile (classification, scrubbing, duplicate check, scratch-copy
  drop), `classify_rules.py` / `mine.py` measure how much of a profile is generic.
- Compile stage (castfix rounds): declaration fixes for decompiler artefacts, keyed on javac errors: a lost local
  declaration (`g = new T(..)` used outside its block) gets `T g;` before the shallowest enclosing statement; a leftover
  generic name (`List<K>[]`) loses its argument; `for (X v : (Object) e)` takes the element type of `e`'s declaration; an
  iterator or map entry declared as `Object` becomes a raw `Iterator` / `Map.Entry`; generic arguments that only compiled
  against the source's types get a raw cast; `T[] a = new Object[n]` gets `(T[])`. Each replaces counted edits that ports
  kept by hand.
- `porter upfront --jar <mod.jar> [--src <dir>] [--classes <list>]... [--rules <dir>]... [--eval-scope <dir>] [--data <dir>] [--sounds <sounds.json>] [--workers 8] --out <dir>`
 : the whole upfront layer in one command: api-index, inventory, flattening, match, emit-rules, shimgen and todo-batch, one line per stage,
  then `upfront-report.txt/.json`, the one metric: every symbol of the inventory (bytecode first) is in exactly one bucket (hand rules, graph rules,
  emitted rules, concepts, flattening, mirror, bodies, open), reported for the whole inventory and for the source-origin subset that
  `porter coverage` counts, plus how many hand rules the table reproduces by itself. Two runs give byte-identical tables. Guide:
  `docs/guides/upfront.md`.
- `porter port`: optional stage `upfront` before `rewrite` (config section `upfront:`, schema additive). The emitted rules are added to the rewrite
  rule set (after the hand rules), the generated shim sources are installed with the adapter pack, relocated to `rewrite.shimPackage`.
- `porter match --registry <registry.tsv>` joins the flattening table into the match table: REGISTRY rows get the 1.7.10 expression as target, so
  `emit-rules` writes registry rules for them (before, all of them were skipped as `registry-needs-field-target`). Rows that need a damage value or
  have no counterpart stay open. `--eval-scope <dir>` counts how many rules of one rule set the table reproduces by itself.
- `porter flattening --sounds <1.7.10 sounds.json>`: `SoundEvents` rows get the 1.7.10 sound name (a string, as the hand rules do) when the user's own
  `sounds.json` has a name of the same shape (`entity.zombie.hurt` to `mob.zombie.hurt`, `block.stone.break` to `dig.stone`, a short alias list for
  `random.*`); the others become `ABSENT`.
- `porter shimgen --target <target.tsv>`: constructors and interface flags of the 1.7.10 classes.
- `testkit/scengen/scengen.py`: generates functional testkit scenarios (machine processes recipe: place, power, fluids, ingredients, tick, assert the result; plus storage, charging bench, discharging station and generator cases) from the recipe and menu IR and a machine table. Input and output slots come from the menu IR, ids are resolved through the id map, a registry dump (`--known`) and prefix fallbacks, recipes that cannot work by construction are skipped with a reason (`scengen-report.json`). Tests on a synthetic IR in `testkit/scengen/test_scengen.py`; see `testkit/README.md`.
- `porter flattening --client-jar <1.19.2 client.jar> --tiny <1.19.2.tiny> --target-jar <mcp.jar> --out registry.tsv [--compare table.tsv] [--force]`: builds the registry-constant table (`Items.X`, `Blocks.X`, `SoundEvents.X`, `Enchantments.X`, `MobEffects.X`, `Fluids.X` to a 1.7.10 expression) at run time from the user's own jars: flattening string pairs and block-state ids are found by constant-pool content, 1.19.2 fields come from the registration initialisers via the tiny mappings, and targets from the 1.7.10 `init.Blocks`/`init.Items` registrations. Rows without a counterpart are `ABSENT` with a reason; sounds are TODO rows. Cached by input hash.
- `porter todo-batch` (split the shimgen TODO list into one markdown batch per fill worker, owners never split, balanced by weight, with candidates and call sites) and `porter bodies check` (one javac over all bodies of the bodies DB, reports failing keys, sets `verified: compile`); worker brief template in `docs/guides/upfront-fill.md`.
- `porter emit-rules --table <match-table.json|tsv> [--concepts concepts.yaml] --out <dir>` (upfront rule emitter, `RuleEmitter`):
  turns the unresolved rows of a match table into ordinary rule files, so the rewriter uses the table without engine
  changes: `upfront-map` (type/call/field/constructor/override), `upfront-expand` (concept arguments expanded, result
  wrapped, `pure:` precondition plus a helper rule for impure arguments), `upfront-registry`, `upfront-mirror`,
  `upfront-ext`; a no-argument getter whose target is a field becomes `$r.field` (or `Owner.field`). Rows already resolved by a rule or the graph are skipped; rows that cannot be a rule are listed with
  the reason. Rule provenance source `upfront` (added to `rule.schema.json`). The mechanical rule builders moved from
  `GraphRuleGenerator` into the public `MechanicalRules` (graph output unchanged).
- Upfront translation tables, formats only (RFC 011): the symbol key (`type:`/`method:`/`field:`, mojmap owners, JVM
  descriptors) with a parser, the API inventory (`contracts/upfront-inventory.schema.json`) and the match table
  (`contracts/upfront-match-table.schema.json`, TSV and JSON) in `dev.porter.port.upfront.model`, and two contract ids
  (`upfront-inventory`, `upfront-match-table`) with valid and invalid examples. Nothing reads or writes them yet.
- Upfront layer, concepts and type plan (`dev.porter.port.upfront`): `rules/upfront/1.19.2__1.7.10/concepts.yaml` (21 signature concepts: BlockPos/Vec3i, Direction, BlockState, InteractionHand, InteractionResult(+Holder), RandomSource, ItemLike, NonNullList, Component, BlockGetter, Level family, ResourceKey<Level>, Vec3; each with argument-unfold and result-wrap expressions checked against the shim sources), `Concepts` (load, `conceptDesc`, `expand`, `wrap`, shim check), `TypePlanner` (every referenced type gets MAP, an existing shim, or a generated mirror; nested classes follow the outer class) and `ConceptAcceptance` for real-data numbers. Adapter pack: `InteractionResult.of(boolean)`.
- `porter match --inventory <api-inventory.json> --index <api-index dir> --tables <match-tables.yaml> [--concepts <concepts.yaml>] [--rules <dir|file>]... [--graph <dir>] --out <dir> [--eval]`:
  decides for every API symbol of an inventory what it becomes on the target version (`match-table.tsv`/`.json`: plan MAP, EXPAND,
  MIRROR, REGISTRY, HINT or TODO, target, confidence, method, scored features and alternatives). Stages: composed version lineage
  (checked against the target jar), registry owners, scored candidates in the mapped owner (name tokens with synonyms, arity,
  return and parameter types), mirror owners. Every weight, threshold, synonym and owner map lives in
  `rules/upfront/1.19.2__1.7.10/match-tables.yaml`; the code names no symbol. `--eval` reports precision/recall against the hand rules.

- `porter api-inventory --jar <mod.jar> [--src dir] [--classes list.txt] [--srg-tiny 1.19.2.tiny] [--hier tsv] [--platform-jar jar] [--platform prefix] --out api-inventory.json`:
  the distinct 1.19.2 platform symbols (vanilla, Forge, Mojang libraries) a mod references, from its bytecode (field/method refs incl.
  lambdas, descriptor types, constructors, platform methods the mod overrides) and optionally its sources, each with use count and
  the declaring class found by walking the platform hierarchy. First step of the upfront translation layer.

- `porter api-index [--graph dir] [--target-jar jar] [--source-jars jar,...] [--tiny-1710 file] [--out dir] [--force]`:
  builds the per-pair API indexes of the upfront translation layer once per machine and caches them by input hash
  (`<cache>/upfront/1.19.2__1.7.10/`): `lineage.tsv` (every 1.19.2 symbol composed through 1.18.2, 1.16.5, 1.12.2 to
  1.7.10 with the status of each hop, the confidence product and where the chain breaks), `target.tsv` (classes,
  hierarchy and members with access of the 1.7.10 jar), `source-hier.tsv`, `srg-1.7.10.tsv` and `manifest.json`.
  Edge files are streamed, one hop in memory at a time; a missing graph file names `porter graph build`.
- `porter api-inventory --jar <mod.jar> [--src dir] [--classes list.txt] [--srg-tiny 1.19.2.tiny] [--hier tsv] [--platform-jar jar] [--platform prefix] --out api-inventory.json`:
  the distinct 1.19.2 platform symbols (vanilla, Forge, Mojang libraries) a mod references, from its bytecode (field/method refs incl.
  lambdas, descriptor types, constructors, platform methods the mod overrides) and optionally its sources, each with use count and
  the declaring class found by walking the platform hierarchy. First step of the upfront translation layer.
- `porter shimgen --table match-table.json [--concepts f] --shims <dir>... [--bodies dir] [--inventory f] [--hierarchy f] --out <dir> [--todo todo.tsv]`:
  Java 8 shim skeletons for the match-table rows planned MIRROR, HINT and TODO (and MAP/EXPAND rows with a shim):
  new mirror classes under `dev.porter.shim.gen`, `<Shim>.members.java` fragments for types that already have a shim,
  `<Simple>Ext` static helpers for types that map to a 1.7.10 class. Existing shim members are never generated; bodies
  come from the bodies DB (`bodies/<area>-<n>.yaml`, one document per key), delegates, or typed
  `porter:todo` bodies between `porter:body`/`porter:end` markers, listed in `todo.tsv`. Formats in
  `docs/guides/upfront-shimgen.md`.
- `porter precheck --config <port.yaml> [--changed files] [--class a.b.C] [--baseline] [--rules-only] [--max-classes N]`:
  the fast check before a full `porter port` run (about a minute instead of six). It validates the rule files (schema,
  duplicate ids, rules the engine cannot apply, same-priority conflicts), finds the rules/overlays/edits that changed
  since the last full output (`work/precheck/baseline.json`, written by the rewrite and install stages), rewrites only the
  classes they touch (found from the last rewrite report plus a scan for the selected symbols), runs the install passes,
  the loop and javac with castfix on a scratch project against the last build, and exits 1 with the same stage lines
  the full run prints. The normalised error classes (`rules:*`, `edit:*`, `rewrite:*`, `javac:<symbol>`) are written to
  `work/precheck/error-classes.tsv` so repeating classes can be counted and fixed in porter. Real project and `work/rw`
  are never written.
- `porter rewrite --relocate-known <file>`: classes of the port that are not `--include`d in this run still count as ported
  (no `TODO(porter:no-rule)` imports) and are relocated; makes a partial rerun produce the same text as a full one.
- `RuleBook.canonicalReplace` is public (shared by the rewriter's ambiguity check and the precheck's conflict check).

### Fixed
- testkit GUI structural check: a widget with an empty label (width 0, e.g. an icon tab / `IconButton` drawing an item) is not reported as "text overflows its box" even when it sits outside the text frame (false "broken" on tab icons above the panel).
- Adapter pack `generic-lib-config`: the config types map to `dev.porter.shim.mc.*` where the helper classes live (the rules named a `shim.lib` package that the shim does not have; a port only compiled when an older run had left those classes in its project).
- Upfront (found by running it on a real addon,): the match table gave a registry constant whose 1.7.10 field has a narrower type (`Items.BOW`,
  `ItemBow`) the plan HINT, so shimgen wrote a field setter that did not compile; it is REGISTRY now and the flattening table answers.
  Shimgen: generated classes no longer extend `java.lang.Enum`/`Record`, a super class without a no-argument constructor (platform class from
  `--target`, shim source with only a protected/public constructor with arguments, generated class with constructors) or a class where an
  interface is expected (the super class moves to `implements`, a class in `implements` is dropped, each with a warning), are written
  `abstract` when they have super types, and reference Mojang-only libraries (`com.mojang.blaze3d.*`) by their generated mirror instead of
  the 1.19.2 name (other library types that do not exist on 1.7.10 become `Object` with a warning); constructors in a fragment carry the
  name of the existing shim; the setter of a HINT field is a TODO. `--hierarchy` reads the api-index `source-hier.tsv` as well. On the addon
  the generated sources went from 188 javac errors to 1.
- `porter bodies check`: a fragment is compiled in a class (an interface for `default` members) named like the shim, so constructors in it
  are valid declarations.
- `porter todo-batch`: after the greedy assignment owners are moved or swapped between the heaviest and the lightest batch while the gap narrows
  (max/min weight on the addon 1.30 to 1.01).
- The two plan enums are one: `Concepts.Plan` is gone, `concepts.yaml` plans use `upfront.model.Plan`.
- `porter emit-rules` documentation no longer mentions a non-existent `porter match --emit-rules`.
- `castfix` never edits a file outside the root it was given (a file found on a source path).

- `cropgen.py` in the `cropgen/` folder of the steam-and-barrels port: data-driven generator of crops for a crop-card host mod (Forge 1.7.10). A YAML data file (properties,
  growth conditions, drops, base seeds, one source texture per growth stage) becomes Java `CropCard` classes (a shared
  base, one template per family: flower, sapling, ore, fungus, and a condition template for single crops with gates for
  light, humidity, ore-dictionary block below, neighbour block and moon phase), the registry call, per-stage icons copied
  from the source assets, lang fragments, a report, and testkit scenarios (a harvest scenario per family plus a growth
  scenario). `build-smoke-mod.sh` packs the output into a standalone test mod. Example data and tests are in
  the `cropgen/` folder of that port; the extractor that reads one particular mod stays in that mod's profile.
- Testkit `invoke_tile` passes numbers to `short` and `byte` parameters (block-entity setters such as `setSize(byte)`).
- Adapter pack: `MetaBlock` (a block constant that is one metadata subtype of a shared 1.7.10 block, e.g. a port's holder for
  a machine that 1.7.10 stores as `blockMachine@12`). `ItemLikes.item/stack` build the stack with the host block's item
  and the right damage, `BlockState.of(block, meta)` and `BlockState.is(block)` unwrap it (is() compares block and meta), so
  stacks, recipes, drops, filters and block checks that take the holder get the right subtype. `MetaBlock.of(host, 0)`
  is the host block itself, so existing identity checks keep working for meta 0.
- Testkit scenario steps `break_block` (a block broken by the scenario player through the normal harvest path, or by
  the world, with drops) and `explode` (an explosion at a position), on both 1.7.10 and 1.19.2, with schema, docs and
  a plan-compiler test.
- Rule patterns match and rewrite cast expressions: `(T) $e` and `((T) $e).m($a)` (the pattern's cast is consumed,
  the placeholder binds the expression without it), and type metavariables `$T` in casts and declared types
  (`$T $v = ($T) $e;`). A statement placeholder is no longer mistaken for `(T) $e;` after a cast.

### Changed
- Documentation consolidated: `README.md` (what porter is, a step-by-step quickstart for players on Windows and
  Linux, why ported jars are never published), `docs/ARCHITECTURE.md` (pipeline, parts, profiles, version packs,
  design decisions), the three guides, `CONTRIBUTING.md`, `ports/README.md` and each port's README. The GTNH template
  docs that one port had copied into its `project/` folder were removed (the template itself still provides them).
- `porter --version` prints the release version (it printed 0.1.0 in 0.2.0).

### Fixed
- testkit GUI structural check: a widget with an empty label (width 0, e.g. an icon tab / `IconButton` drawing an item) is not reported as "text overflows its box" even when it sits outside the text frame (false "broken" on tab icons above the panel).
- Adapter `ItemStacks.sameItemAs`: a key stack with the 1.7.10 wildcard damage (32767, how vanilla furnace recipes are keyed)
  matches every variant of the item. Before, a recipe list built from `FurnaceRecipes` never matched (a colossal furnace could not smelt).
- The compile stage's cast-artefact fixes run until a round no longer changes the javac errors (cap 10 rounds, default was 3; the error count may stay equal while flow errors surface), so one run no longer needs a manual `--from compile` after a larger port.
- Testkit `call:` probe finds public interface default methods (it only looked at the declared methods of the class chain).
- `scripts/gradle/porter-classpath.gradle` did not compile (a closure parameter shadowed a local variable); the init script failed with a Groovy internal error.
- `porter generate` no longer fails with "two different contents for <Class>.java" when one logic class serves several
  IR nodes that need different stubs (one container class for five menus, one block class for three pipes, each node
  with its own generated base). No stub is written for such a class (the port provides it) and a `shared-stub`
  warning names the nodes; a real clash of two generated files still fails, now with an explanation.
- `porter select` writes `<unit>.<kind>.ir.json` for every kind, empty ones included. Before, a unit whose seed
  selected no multiblock had no `multiblock` file, and a profile step naming it (`porter assets --ir ...`) failed with
  `NoSuchFileException`; the set of files a unit produces no longer depends on what its seed matches.
- `porter port` no longer keeps its peak heap while a child JVM works: before starting a child (rewrite, decompile,
  loop, Gradle) it runs a full GC, and the launcher's `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` let the
  collector return the free heap to the OS. Measured on a 584-class run (working set, sampled every 5 s): the parent
  peaks at 1.4 GB during closure/install and stays at about 150 MB while the rewrite/loop/compile child (up to 1.9 GB)
  works; the whole run peaks at 2.05 GB. Before, the parent kept its 1.4 GB next to the child, and three parallel runs
  made a 16 GB machine swap.
- Hub stubs: a member whose name contains `$` (decompiler lambdas such as `cfr$lambda$init$6`) was read as the call `6(`,
  so the text before it, `void cfr$lambda$init$`, became the return type and a void method got `return null;`
  (javac: "unexpected return value"). Member names are read whole, and block comments in the header
  (`private /* synthetic */ void`) are ignored.
- Loop: a rule that replaces a static field by a whole expression (`Blocks.FIRE` -> `Blocks.fire`) replaced only the
  name when the use was qualified, giving `Blocks.Blocks.fire`; the qualifier is now part of the replaced span.
- Java 8 pass: a record with a second constructor (`record T(a, b) { T(a) { this(a, 0); } }`) was taken to have its
  canonical constructor, so none was generated and `this(...)` recursed. A constructor counts as canonical only if its
  parameter count equals the number of record components (or it is the compact form).
- Adapter rules: `ItemStack#getMaxDamage()` / `Item#getMaxDamage()` stay `getMaxDamage()`. The symbol graph pairs them
  with a `getMaxDurability` that 1.7.10 does not have.
- Shim `EditBox` has the `setText`/`getText` names that the setValue/getValue mapping produces and the
  `(Font, x, y, w, h, previous, message)` constructor.
  Shim `Font` has `drawWordWrap(text, x, y, width, color)`.
  Shim `FluidStacks.withAmount(Fluid, int)` (the copy adapter now also takes a bare fluid, null-safe).
- Hub stubs: a constructor the closure calls was dropped when its signature named a type that does not exist on the
  target (`BlockEntityType<? extends CutTile>`), leaving a stub without any constructor its subclasses and the
  superclass could use. It is kept now, with those parameter types (and their type arguments) written as `Object`.
- Rewriter: a static field of an imported type (`Level.NETHER`) inside a lambda whose target type is unresolved (passed to
  a call on a cut hub) is an unbound variable in Spoon's model and no field rule fired on it. It is now matched by its
  written `Type.NAME` and the imports of the file.
- `scripts/gradle/porter-classpath.gradle` also writes `build/porter-javac.args`, a javac argfile with the classpath
  joined for the platform, without the Jabel plugin (it crashes a plain javac) and with `-proc:none`, for compiling
  single files against the project's classpath.
- Adapter rules: `InventoryMenu.BLOCK_ATLAS` (slot background atlas) became `TextureMap.locationBlocksTexture`, a
  client-only class, so building a container with such a slot crashed a dedicated server (`NoClassDefFoundError:
  TextureMap`). It is now `Atlases.BLOCKS` (shim, same location).
- Shim `StateEntityBlock`: a block built without its tile type (generated content could not name the 1.19.2 type) no
  longer fails with an NPE when placed: the tile class registered under the block's own name is used.
- Shim `Textures.get(manager, null)` returns the block atlas instead of failing (a GUI without a texture draws nothing).
- Shim `NonNullList.withSize(n, null).clear()` emptied the list instead of resetting its slots (1.7.10: EMPTY is null), so ported inventories lost their slots after `clear()`.
- Testkit probe `recipes:<registry name>` (1.7.10) counts crafting recipes that make an item or block; `gen-block-smoke.py --recipes` asserts every block has one.
- Testkit (1.7.10) registers a test fixture block `portertestkit:tank` (16000 mB, a plain Forge `IFluidHandler`, fills and drains from every side) when a plan runs, as source and target for fluid transport scenarios.
- Testkit scenario steps `insert_energy` (EU sink or RF receiver) and `invoke_tile` (call a public method of the block
  entity, `"$player"` = scenario player), probe `player:pos`; `gen-block-smoke.py --client` writes client-side
  scenarios that open each GUI (structural check).
- Testkit (1.7.10): new probes `tile:`, `player:container`, `entity_new:` and `registry:entities`; the scenario player
  opens GUI containers server-side (Forge's FakePlayer ignores `openGui`). New `scripts/ci/gen-block-smoke.py` writes one
  runtime smoke scenario per registered block (place, tick, right-click, 100 ticks); `smoke-log-check.sh` ignores the NPE
  of the player's missing network channel.

## [0.2.0] - 2026-10-22

### Added
- `porter port` install stage: every call site in kept code that targets an inert hub-stub member (`// porter:hub-stub inert`) is listed (`inert-hub-calls.json`, `inertHubCalls`/`inertHubCallCount` in `port-report.json`, a section in `port-report.md`, a WARNING summary at the end of the stage). Catches silently missing sounds and GUI item icons at build time. Unit test `InertHubCallsTest`.
- Upfront bodies batches 5-6 (rendering, network): 153 of 207 TODO bodies filled, generic helper `CSlots` for field-less mirrors; compile-checked with `porter bodies check`.
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
- `porter port` install stage: every call site in kept code that targets an inert hub-stub member (`// porter:hub-stub inert`) is listed (`inert-hub-calls.json`, `inertHubCalls`/`inertHubCallCount` in `port-report.json`, a section in `port-report.md`, a WARNING summary at the end of the stage). Catches silently missing sounds and GUI item icons at build time. Unit test `InertHubCallsTest`.
- Upfront bodies batches 5-6 (rendering, network): 153 of 207 TODO bodies filled, generic helper `CSlots` for field-less mirrors; compile-checked with `porter bodies check`.
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

[0.2.0]: https://github.com/Chasik8/mc-porter/releases/tag/v0.2.0
[0.1.0]: https://github.com/Chasik8/mc-porter/releases/tag/v0.1.0
