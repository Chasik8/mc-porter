# rewrite — the porter rule engine (`porter rewrite`)

Rewrites mod logic from one Minecraft/Forge version to another with YAML rules
(`contracts/rule.schema.json`). Spoon builds a typed model of the sources; matched code is rewritten by
position-based text edits, so everything a rule does not touch stays byte-identical (formatting, comments).
Every case the engine does not rewrite gets a `// TODO(porter:<reason>) <detail>` line comment above the
statement or declaration. Same input + same rules = same output bytes.

```
porter rewrite --src <mod-src> --from 1.19.2 --to 1.7.10 --out rw/
    [--rules <dir|file>]...        default ./rules (only rules with from/to = forge-<from>/forge-<to>)
    [--graph <file|dir>]...        symbol-graph documents: SRG/obf aliases + generated mechanical rules
    [--type-facts <tsv>]...        extra supertype tables (a curated 1.19.2 table is bundled)
    [--include <glob>]...          rewrite only these files (relative to --src); the rest only provides types
    [--classpath <jar>]...         libraries of the source version, for exact typing
    [--platform <prefix>]...       extra packages that must be rewritten (e.g. the host mod's package for an addon port)
    [--no-shim] [--shim-package <pkg>]
    [--emit-graph-rules <dir>]     write the rules generated from --graph as YAML
    [--check-rules]                only run the before/after tests of the loaded rules (exit 1 on failure)
```

Output: the rewritten files under `--out` (same relative paths), the shim sources if a rule needed them
(relocated to `--shim-package`), and `--out/porter-rewrite.json` (per-file rewrites and TODOs by reason, every
TODO with file/line/reason/detail; no timestamps, so it is deterministic too). The usual run report goes to
`reports/runs/rewrite.json`.

## What is matched

| Selector | Sites | Notes |
|---|---|---|
| `call: Owner#name(P...)` | method calls, `super.m()`, implicit-this calls; `Owner#<init>(P...)` matches `super(...)`/`this(...)` | receiver must be provably `Owner` or a subtype |
| `new: Owner(P...)` | `new Owner(...)` | anonymous subclasses are reported, not rewritten |
| `field: Owner#name` | reads and writes, static and instance, implicit `this` | |
| `type: a.b.C` | imports, declarations, casts, generics, `extends`/`implements`, static qualifiers | `replace` = new binary name; replace == match means "identical in the target" (no TODO) |
| `override: Owner#name(P...)` | method declarations in rewritten classes overriding it | `replace` = `[visibility] [returnType] newName` |
| `pattern:` | one expression, or a sequence of statements in a block | Java code with `$x` placeholders (RFC 006, below) |
| `declaration:` | `a.b.C`, `a.b.C#field`, `a.b.C#m(P...)` declared in the rewritten files | changed by `edit:` instead of `replace` (RFC 006) |

Type awareness is three-valued (YES / NO / UNKNOWN). A rule fires only when the receiver type is proven; when it
is unknown the site gets `TODO(porter:unresolved-type)` instead of a guess. Types come from the Spoon model of
all sources under `--src`, `--classpath`, the bundled supertype table
(`src/main/resources/porter/rewrite/typefacts/forge-1.19.2.tsv`, from the vanilla jar) and `--type-facts`.

**SRG names.** Decompiled Forge 1.17+ jars have mojmap class names but SRG member names (`m_128451_`,
`f_46441_`). Rules are written with mojmap names; `--graph` supplies the name table (symbols' `names{srg,
mojmap}`). SRG ids identify a member family (overrides share the id), so an SRG call matches its rule even when
the receiver type is unknown, and SRG return/field types are used to type chained calls.

Several rules can match one site: the highest `priority` wins, ties go by id; two different matching rules
at the same priority are `ambiguous` (TODO, nothing applied). A rule whose preconditions fail falls through to
the next one (e.g. inline `x, y, z` expansion at priority 10, the shim helper at 0). Rules generated from the
graph get priority -10, so hand-written rules win.

`$recv` placeholders bound to an implicit receiver keep it implicit (`level` -> `worldObj`, `getLevel()` ->
`getWorldObj()`); elsewhere an implicit receiver renders as `this`.

## Patterns and declaration edits (RFC 006)

`pattern` is Java code, matched on the AST. Text ending in `;` or `}` is a statement sequence (tried at every
position of every block, in priority order), anything else one expression.

| In the pattern | Matches |
|---|---|
| `$x` used as an expression | any expression; repeated uses must be the same code (casts ignored) |
| `$s;` alone as a statement, or as an `if`/`else` branch | one statement or block |
| `T $v = ...` | a local declaration; `$v` binds its name, later uses must use that local |
| type names | by simple name, generics erased |
| method/field names | by name or through an SRG alias (`--graph`) |
| casts | only when the pattern writes them (decompiler casts are ignored); parentheses never matter |
| `(T) $e`, `((T) $e).m($a)` | a cast the pattern writes must be there, and is consumed: `$e` binds the expression without it, and a root cast is replaced with the rest, so `(Pickup) $e` -> `$e` drops the cast |
| `$T` in a cast or a declared type | any type, written as in the target; repeated `$T` must name the same type; `$T` in `replace` writes it back |

Lambdas, loops, `switch`, anonymous classes do not match inside patterns. An empty `replace` (statement patterns
only) removes the statements with their lines. An expression pattern that matches owns the node: call/field/type
rules on that same node do not apply (rules inside the placeholders still do); code replaced outside the
placeholders loses its TODOs.

`declaration` + `edit`: `addSupertypes` (implements / interface extends, clause created if missing), `addMembers`
(after `{`), `appendMembers` (after the last member), `initializer` (field), `body` (method/constructor, the text
between the braces), `remove: true` (member with its annotations and lines). Texts are inserted verbatim.

`$target(a.b.C)` in a replacement or edit text writes `a.b.C` and protects it from `--relocate` (an addon whose host
mod has the same class names on the target version).

## TODO reasons

| Reason | Meaning |
|---|---|
| `no-rule` | a platform symbol (`net.minecraft.`, `net.minecraftforge.`, `com.mojang.`, `--platform`) without a rule |
| `removed`, `signature-changed`, `split`, `merged` | same, and the symbol graph says why no mechanical rule exists |
| `unresolved-type` | a rule (or platform check) needs a type the engine cannot prove |
| `precondition` | a matching rule's precondition is not met (detail names rule and condition) |
| `unknown-precondition` | a rule uses a condition outside the vocabulary below (it never applies) |
| `ambiguous` | two rules with the same priority match differently |
| `unsupported` | e.g. an anonymous subclass for a `new` rule, a malformed `override` replacement |
| `conflict`, `internal`, `parse` | overlapping source positions, an engine exception at that site, a file Spoon could not model |

Platform symbols declared in files being rewritten (`--include`) are never reported: they are ported together.
JDK members are not platform symbols, except when reached through a platform type that a rule renames away
(`ListTag` is a `java.util.List` in 1.19.2, `NBTTagList` is not).

## Precondition vocabulary (rule `preconditions`)

| Condition | Holds when |
|---|---|
| `shim:blockpos`, `shim:direction`, `shim:world` | the porter 1.7.10 shim is enabled (not `--no-shim`) |
| `pure:$x` | the expression bound to `$x` has no side effects and is cheap to repeat: literal, local/parameter, `this`, a type, or a field chain of those |
| `literal:$x` | `$x` is a literal |
| `statement` / `not-statement` | the matched expression is (is not) a whole expression statement (its value is unused) |
| `recv-subtype:<fqn>` | the receiver's static type is provably a subtype of `<fqn>` |
| `arg-subtype:<i>:<fqn>` | argument `i` is provably a subtype of `<fqn>` |
| `in-subtype-of:<fqn>` | the enclosing class is provably a subtype of `<fqn>` |
| `not-super` | the call is not a `super.m(...)` call (super calls stay virtual calls into the ported superclass) |
| `position:extends`, `position:implements` | `type` rules: the reference is the superclass / a super-interface of a declared type |
| `type:$x:<fqn>` | the expression bound to `$x` (pattern placeholder or `bind`) is provably a subtype of `<fqn>` |
| `extends` / `not-extends` | (older spelling of `position:extends`, plus its negation) the matched type reference is (is not) the direct superclass of a class declaration (`class X extends <here>`): redirects the root of a ported hierarchy to a 1.19.2-API adapter base |

Unprovable type conditions yield `unresolved-type`; anything else unknown yields `unknown-precondition`.

## Supertypes of the source version (`--type-facts`)

Rules declared on a supertype (`LivingEvent#getEntity`, `LevelReader#getHeight`, `BlockGetter#clip`, ...) only apply
when the receiver is provably a subtype. The bundled table is a curated vanilla subset; for a real port generate the
full table of the jars the mod compiles against and pass it with `--type-facts`:

```
python -I rewrite/scripts/typefacts_from_jars.py <cache>/forge-1.19.2.tsv <mojmap-minecraft.jar> <forge-universal.jar> --prefix net.minecraft. --prefix net.minecraftforge. --prefix com.mojang.
```

(class-file headers only; the output is derived from Mojang/Forge jars: keep it in a local cache, not here).

## Rule tests

`tests[].before` is wrapped into a synthetic class and rewritten with that one rule (platform TODOs off, rule
failures on); the result is compared with `after` ignoring whitespace. A `before` starting with `import` is put in
the import section; for `override` rules it is a member of a class extending the owner; for `declaration` rules it is the whole
declaration of the owner's top-level type (in the owner's package, one test per run); otherwise statements in a
method body. `context` is split on `;`: `import a.b.C` adds an import, `extends a.b.C` sets the superclass,
anything else becomes a field (a context containing `{` is used verbatim as class body). The rule's owner and
qualified parameter types are imported automatically. Run them with `porter rewrite --from 1.19.2 --to 1.7.10
--check-rules --rules <dir>` or `gradlew :rewrite:test` (all of `rules/`).

## Mechanical rules from the symbol graph

`GraphRuleGenerator` turns every source-version symbol whose chain to the target version is `same`/`renamed`/
`moved` (one result) into a type / call / override / constructor / field rule with a synthetic test. Chains come
from `queryResult` documents or are composed from adjacent `graph` edges (usable in both directions; worst
status, minimum confidence). Moved instance members, `signature_changed`, `split`, `merged` and `removed`
become diagnostics that explain the TODO. Until a full symbol graph is available,
`scripts/mojmap_srg_graph.py` builds a names-only 1.19.2 graph (and the supertype table) from the Mojang mappings,
MCPConfig `joined.tsrg` and the vanilla client jar; its output must stay out of the repository.

## The 1.7.10 shim (`rewrite/shim`, Java 8)

`dev.porter.shim.BlockPos` and `Direction` (+ `Axis`, `AxisDirection`, `Plane`) mirror the 1.19.2 API that rules
keep as-is (`BlockPos.asLong()` is bit-compatible); `Worlds` holds one-line static helpers over the 1.7.10 World
API for impure position arguments plus `ForgeDirection` conversions. Nothing else: no Level, BlockEntity or Menu
facade. `Worlds` compiles only with a 1.7.10 MCP jar (`-Pporter.mc1710Jar=<jar>` or `PORTER_MC1710_JAR`, e.g.
RFG's `build/rfg/recompiled_minecraft-1.7.10.jar`); CI builds the dependency-free part. The rewriter copies the
needed shim sources into `--out` and relocates them with `--shim-package` (use the mod's own package so two
ported mods never share classes).

## Rule sets

- `rules/composite/1.19.2__1.7.10/` — public hand-written rules (coordinates, directions, NBT, items, world and
  block entities, math, Forge fluids). Every source member was checked against the Mojang 1.19.2 mappings and
  every target member against the MCP 1.7.10 jar (`javap`), except the Forge 1.19.2 fluid API (no Forge 1.19.2 jar
  available; see provenance).
- `rules/packs/adapters/1.19.2__1.7.10/` — opt-in adapter rules (use with the adapter pack, `porter port`
  `install.adapters: bundled`).
- Mapping rules for a mod's own API (e.g. an addon's host mod between its versions) are a separate pack, kept with
  the port profile or locally.

## Networking bridge (adapter pack)

Forge 1.19.2 `SimpleChannel` code (`NetworkRegistry.newSimpleChannel`, `registerMessage`, `send`, `sendToServer`,
`reply`, `PacketDistributor.X.with(..)/noArg()`, `NetworkEvent.Context`) runs unchanged on 1.7.10 through
`dev.porter.shim.mc.SimpleChannel` and friends (rules: `modern-api-networking.rule.yaml`, generated from
`modern_api_spec.py`; one type rule per class plus an identity rule per member, because a member of a platform type
without a rule is a `no-rule` TODO).

- One 1.7.10 `SimpleNetworkWrapper` per channel (name = the `ResourceLocation`, cut to 20 characters with a hash
  suffix when longer, an FML limit). Every message is wrapped in the single `NetBridgeMessage` (varint id + the bytes
  the 1.19.2 encoder wrote), registered with discriminator 0 on both sides. The channel's `MessageTable` (id -> class,
  encoder, decoder, handler; dependency-free, unit-tested) decodes and dispatches by id in the receiving handler.
- Targets: `PLAYER`, `ALL`, `SERVER`, `DIMENSION`, `TRACKING_CHUNK` (players watching the chunk),
  `TRACKING_ENTITY` / `TRACKING_ENTITY_AND_SELF`, `NEAR` (`TargetPoint`, with the optional excluded player).
  A `DIMENSION`/`NEAR` key is a `ResourceKey` (`ResourceKey.dimension(world)`).
- `NetworkEvent.Context`: `getSender()` (the player on the server, `null` on the client), `getDirection()`,
  `enqueueWork(Runnable)` (a `CompletableFuture`; runs at the end of the next server tick on the server or client
  tick on the client), `setPacketHandled`/`getPacketHandled`.
- Not bridged: protocol version negotiation (predicates are ignored), `sendTo(msg, Connection, direction)`, the
  message builder API, login packets, `NMLIST`. The channel must be created during mod init (FML closes channel
  registration at server start). Handlers run on the network thread unless they call `enqueueWork`, as in 1.19.2.

## Known limits

- Without a 1.19.2 Forge classpath, Forge's own supertypes (`CapabilityProvider`, `IForgeBlockEntity`) are unknown:
  the bundled table is vanilla.
- Lambda parameters and `var` have no written types; calls on them are `unresolved-type` unless SRG-named.
