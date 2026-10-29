# The upfront layer in one command (`porter upfront`)

A port normally finds the platform symbols it needs one failed compile at a time. `porter upfront` finds them all
before the first run: it lists every symbol of the source platform (Minecraft, Forge, Mojang libraries) that the mod
uses, decides for each what it becomes on the target version, writes the rules and shim sources that follow from
that, and turns the rest into a work list.

Version pair: forge-1.19.2 to forge-1.7.10. The per-pair data (match tables, concepts, bodies) is public and lives in
`rules/upfront/1.19.2__1.7.10/`. Everything that comes from Minecraft itself (jars, mappings, `sounds.json`) is read
from the user's own files at run time; nothing of it is shipped.

## Run it

```
porter upfront --jar <mod 1.19.2 jar> --src <decompiled sources> --classes <classes.txt> \
  --rules rules/composite --rules rules/packs/adapters --rules <profile rules> \
  --data rules/upfront/1.19.2__1.7.10 --shims rewrite/shim/src/main/java \
  [--eval-scope <the profile's own rules>] [--sounds <1.7.10 sounds.json>] [--workers 8] --out <dir>
```

Needs the porter cache (`$PORTER_CACHE`, else `~/.porter/cache`) with the symbol graph and the compile jars of both
versions, as `porter run` leaves it. `--classes` limits the inventory to the classes you port (the closure list);
without it every class of the jar counts. `--rules` are the hand rules that already exist; the more you pass the
more of the inventory is "resolved before".

It prints one line per stage, then the report:

```
[1/7] index      api-index up to date ...
[2/7] inventory  api-inventory: ... types 462, members 1936 ...
[3/7] flattening rows ...
[4/7] match      match: 2398 symbols, 1194 resolved by rules/graph, plans {...}
[5/7] emit-rules rules: ..., skipped rows: ...
[6/7] shimgen    shimgen: ... files, ... members, ... todo ...
[7/7] todo-batch todo-batch: ... entries in 8 batches, weight ...
upfront report ...
```

A stage that fails stops the run; its full output is `<out>/logs/<stage>.log`. Two runs with the same inputs give
byte-identical tables, rules, shim sources and batches.

## What each output is

| Path in `<out>` | What | Used by |
|---|---|---|
| `inventory/api-inventory.json` | every distinct source-platform symbol the mod references (bytecode first, sources second), with use counts and the classes that use them | match, shimgen, the report |
| `registry/registry.tsv` | `Items.X`, `Blocks.X`, `SoundEvents.X`, ... to a 1.7.10 expression, derived from the client jar and the 1.7.10 jar; sounds only with `--sounds` | match (`--registry`) |
| `match/match-table.tsv`, `.json` | one row per symbol: plan (MAP, EXPAND, MIRROR, REGISTRY, HINT, TODO), target, confidence, method | emit-rules, shimgen |
| `match/eval.txt` | with `--rules`: precision and recall of the table against the hand rules, and how many hand rules it reproduces by itself | the report |
| `rules/upfront-*.rule.yaml` | ordinary porter rules for the rows no hand rule resolved (map, expand, registry, mirror, ext) | `porter rewrite --rules <out>/rules` |
| `rules/upfront-skipped.tsv` | rows that cannot be a rule, with the reason | the report |
| `gen/src/` | Java 8 shim sources (`dev.porter.shim.gen...`): mirror classes, `<Name>Ext` helpers; members carry their key | install, with the adapter pack |
| `gen/fragments/` | members to add to an existing shim, for review | review |
| `gen/todo.tsv` | members whose body is still `porter:todo` | todo-batch |
| `batches/<n>.md`, `index.tsv` | one batch per person or session filling bodies, owners never split, loads balanced | the workers ([upfront-fill.md](upfront-fill.md)) |
| `upfront-report.txt`, `.json` | the metric below | you |
| `adapters-src/` | the bundled adapter pack, extracted: shim sources that members must not duplicate | shimgen |

Generated rules go after the hand rules in the rule order: where a hand rule exists it wins, upfront only fills the
gaps. A port run uses them through the config:

```yaml
upfront:
  jar: ../mod-1.19.2.jar          # required; the closure classes are the inventory scope
  data: ../porter/rules/upfront/1.19.2__1.7.10
  workers: 8
  # tables, concepts, bodies, sounds, emitRules: true, shimgen: true, args: []
```

The stage runs before `rewrite`: its `rules/` join the rewrite rule set, its `gen/src` is installed relocated to
`rewrite.shimPackage` next to the adapter pack, `batches/` is for the fill workers.

## The metric

`upfront-report.txt` counts the symbols of the inventory, each in exactly one bucket, for the whole inventory and for
the subset that appears in the sources. That subset is the universe `porter coverage` counts, so the "before" number
matches coverage within a few symbols; the rest of the inventory (Forge members, constructors, overridden methods)
only exists in the bytecode and was never counted before.

```
before upfront (hand + graph rules)      hand rules, graph rules
+ emitted map rules, concepts (expand rules), flattening (registry rules),
  mirror (shim types and members), shim delegates, bodies DB or existing shim
after upfront (resolved)
- todo (body needed), registry (no 1.7.10 counterpart), emitter skipped, no shim
remaining open
```

"Resolved" after upfront means a rule or a generated member exists; a member generated with a `porter:todo` body is
open until a body is filled and checked. The last lines say how many of your hand rules (with `--eval-scope`, those
of your profile) the table reproduces without them: a rule counts only if every symbol it covers is reproduced.

## After the run

1. Compile the generated shims with the adapter pack against the 1.7.10 jar and its libraries; fix classes of error
   in shimgen, not in the output.
2. Fill the TODO batches (one worker per `batches/<n>.md`), then `porter bodies check` ([upfront-fill.md](upfront-fill.md)).
3. Rerun `porter upfront` (the bodies are now in the bodies DB and are spliced in) and `porter port`.
4. Each javac symbol that still fails is looked up in `match/match-table.tsv` and fixed in the tables
   (`match-tables.yaml`, `concepts.yaml`, the bodies DB), never with a one-symbol rule.

Known limits: a registry constant that needs a damage value (`Blocks.GRANITE` is stone with metadata 1) has no target;
sound names exist only where the 1.7.10 `sounds.json` has a name of the same shape; a generated class that extends a
class whose constructors need arguments is written without the `extends`; a generated override whose return type
differs from its super type's does not compile.
